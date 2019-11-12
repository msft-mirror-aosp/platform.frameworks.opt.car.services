/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Class to control the assignment of a diplsay for Car while launching a Activity.
 *
 * <p>This one controls which displays users are allowed to launch.
 * The policy should be passed from car service through
 * {@link com.android.internal.car.ICarServiceHelper} binder interfaces. If no policy is set,
 * this module will not change anything for launch process.</p>
 *
 * <p> The policy can only affect which display passenger users can use. Currnt user, assumed
 * to be a driver user, is allowed to launch any display always.</p>
 */
public final class CarLaunchParamsModifier implements LaunchParamsController.LaunchParamsModifier {

    private static final String TAG = "CAR.LAUNCH";

    private final Context mContext;

    private DisplayManager mDisplayManager;  // set only from init()

    private final Object mLock = new Object();

    // Always start with USER_SYSTEM as the timing of handleCurrentUserSwitching(USER_SYSTEM) is not
    // guaranteed to be earler than 1st Activity launch.
    @GuardedBy("mLock")
    private int mCurrentDriverUser = UserHandle.USER_SYSTEM;

    /**
     * This one is for holding all passenger displays which are mostly static unless displays are
     * added / removed. Note that {@link #mPassengerUserDisplayMapping} can be empty while user is
     * assigned and that cannot always tell if specific display is for driver or not.
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mPassengerDisplays = new ArrayList<>();

    @GuardedBy("mLock")
    private final SparseArray<ArrayList<Integer>> mPassengerUserDisplayMapping = new SparseArray<>();


    @VisibleForTesting
    final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            // ignore. car service should update whiltelist.
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                for (int i = 0; i < mPassengerUserDisplayMapping.size(); i++) {
                    ArrayList<Integer> displays = mPassengerUserDisplayMapping.valueAt(i);
                    displays.remove(Integer.valueOf(displayId));  // should pass Object, not int
                }
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            // ignore
        }
    };

    /** Constructor. Can be constructed any time. */
    public CarLaunchParamsModifier(Context context) {
        // This can be very early stage. So postpone interaction with other system until init.
        mContext = context;
    }

    /**
     * Initializes all internal stuffs. This should be called only after ATMS, DisplayManagerService
     * are ready.
     */
    public void init() {
        ActivityTaskManagerService service =
                (ActivityTaskManagerService) ActivityTaskManager.getService();
        LaunchParamsController controller = service.mStackSupervisor.getLaunchParamsController();
        controller.registerModifier(this);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener,
                new Handler(Looper.getMainLooper()));
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(int newUserId) {
        synchronized (mLock) {
            mCurrentDriverUser = newUserId;
            mPassengerUserDisplayMapping.remove(newUserId);
        }
    }

    /** Notifies user stopped. */
    public void handleUserStopped(int stoppedUser) {
        // Note that the current user is never stopped. It always takes switching into
        // non-current user before stopping the user.
        synchronized (mLock) {
            mPassengerUserDisplayMapping.remove(stoppedUser);
        }
    }

    /**
     * Sets display whiltelist for the userId. For passenger user, activity will be always launched
     * to a display in the whitelist. If requested display is not in whitelist, the 1st display
     * in the whitelist will be selected as target display.
     */
    public void setDisplayWhitelistForUser(int userId, int[] displayIds) {
        synchronized (mLock) {
            ArrayList<Integer> displays = new ArrayList<Integer>(displayIds.length);
            for (int id : displayIds) {
                displays.add(id);
            }
            mPassengerUserDisplayMapping.put(userId, displays);
        }
    }

    /**
     * Sets displays assigned to passenger. All other displays will be treated as assigned to
     * driver.
     *
     * <p>The 1st display in the array will be considered as a default display to assign
     * for any non-driver user if there is no display assigned for the user. </p>
     */
    public void setPassengerDisplays(int[] displayIdsForPassenger) {
        synchronized (mLock) {
            mPassengerDisplays.clear();
            mPassengerDisplays.ensureCapacity(displayIdsForPassenger.length);
            for (int id : displayIdsForPassenger) {
                mPassengerDisplays.add(id);
            }
        }
    }

    /**
     * Decides display to assign while an Activity is launched.
     *
     * <p>For current user (=driver), launching to any display is allowed as long as system
     * allows it.</p>
     *
     * <p>For private display, do not change anything as private display has its own logic.</p>
     *
     * <p>For passenger displays, only run in allowed displays. If requested display is not
     * allowed, change to the 1st allowed display.</p>
     */
    @Override
    public int onCalculate(Task task, ActivityInfo.WindowLayout layout, ActivityRecord activity,
            ActivityRecord source, ActivityOptions options, int phase,
            LaunchParamsController.LaunchParams currentParams,
            LaunchParamsController.LaunchParams outParams) {
        int userId;
        if (task != null) {
            userId = task.mUserId;
        } else if (activity != null) {
            userId = activity.mUserId;
        } else {
            Slog.w(TAG, "onCalculate, cannot decide user");
            return RESULT_SKIP;
        }
        final int originalDisplayId = currentParams.mPreferredDisplayId;
        int newDisplayId = currentParams.mPreferredDisplayId;
        synchronized (mLock) {
            if (userId == mCurrentDriverUser) {
                // Do not touch, always allow.
                return RESULT_SKIP;
            }
            if (mPassengerDisplays.isEmpty()) {
                // No displays for passengers. This could be old user and do not do anything.
                return RESULT_SKIP;
            }
            Display display = mDisplayManager.getDisplay(originalDisplayId);
            // This check is only for preventing NPE. AMS / WMS is supposed to handle the removed
            // display case properly.
            if (display == null) {
                return RESULT_SKIP;
            }
            if ((display.getFlags() & Display.FLAG_PRIVATE) != 0) {
                // private display should follow its own restriction rule.
                return RESULT_SKIP;
            }
            if (display.getType() == Display.TYPE_VIRTUAL) {
                // TODO(b/132903422) : We need to update this after the bug is resolved.
                // For now, don't change anything.
                return RESULT_SKIP;
            }
            ArrayList<Integer> displaysForUser = mPassengerUserDisplayMapping.get(userId);
            if (displaysForUser == null || displaysForUser.size() == 0) {
                // If there is no assigned displays for the user, pick up 1st passenger display so
                // that it does not go to driver's display.
                if (mPassengerDisplays.contains(originalDisplayId)) {
                    Slog.w(TAG, "Launching without whitelist, user:" + userId
                            + " display:" + originalDisplayId);
                } else {
                    newDisplayId = mPassengerDisplays.get(0);  // pick 1st passenger display
                    Slog.w(TAG, "Launching passenger without whitelisted displays, user:"
                            + userId + " requested display:" + originalDisplayId
                            +" changed display:" + newDisplayId);
                }
            } else {
                // If there are displays assigned to the user while excluding the requested display,
                // move to the 1at assigned display.
                if (!displaysForUser.contains(originalDisplayId)) {
                    newDisplayId = displaysForUser.get(0);  // pick up 1st one
                    Slog.w(TAG, "Launching passenger to not allowed displays, user:"
                            + userId + " requested display:" + originalDisplayId
                            +" changed display:" + newDisplayId);
                }
            }
        }
        if (originalDisplayId != newDisplayId) {
            outParams.mPreferredDisplayId = newDisplayId;
            return RESULT_CONTINUE;
        } else {
            return RESULT_SKIP;
        }
    }
}
