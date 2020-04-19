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

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseIntArray;
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
    private ActivityTaskManagerService mAtm;  // set only from init()

    private final Object mLock = new Object();

    // Always start with USER_SYSTEM as the timing of handleCurrentUserSwitching(USER_SYSTEM) is not
    // guaranteed to be earler than 1st Activity launch.
    @GuardedBy("mLock")
    private int mCurrentDriverUser = UserHandle.USER_SYSTEM;

    // TODO: Switch from tracking displays to tracking display areas instead
    /**
     * This one is for holding all passenger (=profile user) displays which are mostly static unless
     * displays are added / removed. Note that {@link #mDisplayToProfileUserMapping} can be empty
     * while user is assigned and that cannot always tell if specific display is for driver or not.
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mPassengerDisplays = new ArrayList<>();

    /** key: display id, value: profile user id */
    @GuardedBy("mLock")
    private final SparseIntArray mDisplayToProfileUserMapping = new SparseIntArray();

    /** key: profile user id, value: display id */
    @GuardedBy("mLock")
    private final SparseIntArray mDefaultDisplayForProfileUser = new SparseIntArray();


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
                mPassengerDisplays.remove(Integer.valueOf(displayId));
                updateProfileUserConfigForDisplayRemovalLocked(displayId);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            // ignore
        }
    };

    private void updateProfileUserConfigForDisplayRemovalLocked(int displayId) {
        mDisplayToProfileUserMapping.delete(displayId);
        int i = mDefaultDisplayForProfileUser.indexOfValue(displayId);
        if (i >= 0) {
            mDefaultDisplayForProfileUser.removeAt(i);
        }
    }

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
        mAtm = (ActivityTaskManagerService) ActivityTaskManager.getService();
        LaunchParamsController controller = mAtm.mStackSupervisor.getLaunchParamsController();
        controller.registerModifier(this);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener,
                new Handler(Looper.getMainLooper()));
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(int newUserId) {
        synchronized (mLock) {
            mCurrentDriverUser = newUserId;
            mDefaultDisplayForProfileUser.clear();
            mDisplayToProfileUserMapping.clear();
        }
    }

    private void removeUserFromWhitelistsLocked(int userId) {
        for (int i = mDisplayToProfileUserMapping.size() - 1; i >= 0; i--) {
            if (mDisplayToProfileUserMapping.valueAt(i) == userId) {
                mDisplayToProfileUserMapping.removeAt(i);
            }
        }
        mDefaultDisplayForProfileUser.delete(userId);
    }

    /** Notifies user stopped. */
    public void handleUserStopped(int stoppedUser) {
        // Note that the current user is never stopped. It always takes switching into
        // non-current user before stopping the user.
        synchronized (mLock) {
            removeUserFromWhitelistsLocked(stoppedUser);
        }
    }

    /**
     * Sets display whiltelist for the userId. For passenger user, activity will be always launched
     * to a display in the whitelist. If requested display is not in the whitelist, the 1st display
     * in the whitelist will be selected as target display.
     *
     * <p>The whitelist is kept only for profile user. Assigning the current user unassigns users
     * for the given displays.
     */
    public void setDisplayWhitelistForUser(int userId, int[] displayIds) {
        synchronized (mLock) {
            for (int displayId : displayIds) {
                if (!mPassengerDisplays.contains(displayId)) {
                    Slog.w(TAG, "setDisplayWhitelistForUser called with display:" + displayId
                            + " not in passenger display list:" + mPassengerDisplays);
                    continue;
                }
                if (userId == mCurrentDriverUser) {
                    mDisplayToProfileUserMapping.delete(displayId);
                } else {
                    mDisplayToProfileUserMapping.put(displayId, userId);
                }
                // now the display cannot be a default display for other user
                int i = mDefaultDisplayForProfileUser.indexOfValue(displayId);
                if (i >= 0) {
                    mDefaultDisplayForProfileUser.removeAt(i);
                }
            }
            if (displayIds.length > 0) {
                mDefaultDisplayForProfileUser.put(userId, displayIds[0]);
            } else {
                removeUserFromWhitelistsLocked(userId);
            }
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
            for (int id : displayIdsForPassenger) {
                mPassengerDisplays.remove(Integer.valueOf(id));
            }
            // handle removed displays
            for (int i = 0; i < mPassengerDisplays.size(); i++) {
                int displayId = mPassengerDisplays.get(i);
                updateProfileUserConfigForDisplayRemovalLocked(displayId);
            }
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
        final TaskDisplayArea originalDisplayArea = currentParams.mPreferredTaskDisplayArea;
        TaskDisplayArea newDisplayArea = currentParams.mPreferredTaskDisplayArea;
        synchronized (mLock) {
            if (userId == mCurrentDriverUser) {
                // Do not touch, always allow.
                return RESULT_SKIP;
            }
            if (userId == UserHandle.USER_SYSTEM) {
                // This will be only allowed if it has FLAG_SHOW_FOR_ALL_USERS.
                // The flag is not immediately accessible here so skip the check.
                // But other WM policy will enforce it.
                return RESULT_SKIP;
            }
            if (mPassengerDisplays.isEmpty()) {
                // No displays for passengers. This could be old user and do not do anything.
                return RESULT_SKIP;
            }

            // This check is only for preventing NPE. AMS / WMS is supposed to handle the removed
            // display case properly.
            if (originalDisplayArea == null) {
                return RESULT_SKIP;
            }
            Display display = originalDisplayArea.mDisplayContent.getDisplay();
            if ((display.getFlags() & Display.FLAG_PRIVATE) != 0) {
                // private display should follow its own restriction rule.
                return RESULT_SKIP;
            }
            if (display.getType() == Display.TYPE_VIRTUAL) {
                // TODO(b/132903422) : We need to update this after the bug is resolved.
                // For now, don't change anything.
                return RESULT_SKIP;
            }
            int userForDisplay = mDisplayToProfileUserMapping.get(display.getDisplayId(),
                    UserHandle.USER_NULL);
            if (userForDisplay == userId) {
                return RESULT_SKIP;
            }
            newDisplayArea = getAlternativeDisplayAreaForPassengerLocked(userId,
                    originalDisplayArea);
        }
        if (newDisplayArea != null && originalDisplayArea != newDisplayArea) {
            Slog.w(TAG, "Launching passenger to not allowed displays, user:"
                    + userId + " requested display area:" + originalDisplayArea
                    +" changed display area:" + newDisplayArea);
            outParams.mPreferredTaskDisplayArea = newDisplayArea;
            return RESULT_CONTINUE;
        } else {
            return RESULT_SKIP;
        }
    }

    @Nullable
    private TaskDisplayArea getAlternativeDisplayAreaForPassengerLocked(int userId,
            TaskDisplayArea originalDisplayArea) {
        int displayId = mDefaultDisplayForProfileUser.get(userId, Display.INVALID_DISPLAY);
        if (displayId != Display.INVALID_DISPLAY) {
            return getDefaultTaskDisplayAreaOnDisplay(displayId);
        }
        // return the 1st passenger display area if it exists
        if (!mPassengerDisplays.isEmpty()) {
            Slog.w(TAG, "No default display area for user:" + userId
                    + " reassign to 1st passenger display area");
            return getDefaultTaskDisplayAreaOnDisplay(mPassengerDisplays.get(0));
        }
        Slog.w(TAG, "No default display for user:" + userId
                + " and no passenger display, keep the requested display area:"
                + originalDisplayArea);
        return originalDisplayArea;
    }

    @VisibleForTesting
    @Nullable
    TaskDisplayArea getDefaultTaskDisplayAreaOnDisplay(int displayId) {
        DisplayContent dc = mAtm.mRootWindowContainer.getDisplayContentOrCreate(displayId);
        if (dc == null) {
            return null;
        }
        return dc.getDefaultTaskDisplayArea();
    }
}
