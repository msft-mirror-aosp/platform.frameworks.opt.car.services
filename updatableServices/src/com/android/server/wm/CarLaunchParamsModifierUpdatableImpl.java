/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.car.app.CarActivityManager;
import android.car.builtin.os.UserManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.builtin.view.DisplayHelper;
import android.car.builtin.window.DisplayAreaOrganizerHelper;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;
import android.view.Display;

import com.android.car.internal.dep.Trace;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link CarLaunchParamsModifierUpdatable}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class CarLaunchParamsModifierUpdatableImpl
        implements CarLaunchParamsModifierUpdatable {
    private static final String TAG = CarLaunchParamsModifierUpdatableImpl.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    // Comes from android.os.UserHandle.USER_NULL.
    private static final int USER_NULL = -10000;

    private final CarLaunchParamsModifierInterface mBuiltin;
    @NonNull
    private final CarDisplayCompatScaleProviderUpdatableImpl mDisplayCompatProvider;
    private final CarServiceHelperTaskStackRepository mTaskStackRepository;
    private final Object mLock = new Object();

    // Always start with USER_SYSTEM as the timing of handleCurrentUserSwitching(USER_SYSTEM) is not
    // guaranteed to be earler than 1st Activity launch.
    @GuardedBy("mLock")
    private int mDriverUser = UserManagerHelper.USER_SYSTEM;

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

    /** key: Activity, value: TaskDisplayAreaWrapper */
    @GuardedBy("mLock")
    private final ArrayMap<ComponentName, TaskDisplayAreaWrapper> mPersistentActivities =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final Map<IBinder, Integer> mRootTaskLaunchBehaviors = new ArrayMap<>();

    public CarLaunchParamsModifierUpdatableImpl(CarLaunchParamsModifierInterface builtin,
            @NonNull CarDisplayCompatScaleProviderUpdatableImpl carDisplayCompatProvider,
            CarServiceHelperTaskStackRepository carServiceHelperTaskStackRepository) {
        mBuiltin = builtin;
        mDisplayCompatProvider = carDisplayCompatProvider;
        mTaskStackRepository = carServiceHelperTaskStackRepository;
    }

    private boolean requiresDisplayCompat(ComponentName launchIntent, int userId) {
        return mDisplayCompatProvider
                .requiresDisplayCompat(launchIntent.getPackageName(), userId);
    }

    public DisplayManager.DisplayListener getDisplayListener() {
        return mDisplayListener;
    }

    private final DisplayManager.DisplayListener mDisplayListener =
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

    @GuardedBy("mLock")
    private void updateProfileUserConfigForDisplayRemovalLocked(int displayId) {
        mDisplayToProfileUserMapping.delete(displayId);
        int i = mDefaultDisplayForProfileUser.indexOfValue(displayId);
        if (i >= 0) {
            mDefaultDisplayForProfileUser.removeAt(i);
        }
    }

    @Override
    public void handleUserVisibilityChanged(int userId, boolean visible) {
        synchronized (mLock) {
            if (DBG) {
                Slogf.d(TAG, "handleUserVisibilityChanged user=%d, visible=%b",
                        userId, visible);
            }
            if (userId != mDriverUser || visible) {
                return;
            }
            int currentOrTargetUserId = getCurrentOrTargetUserId();
            maySwitchCurrentDriver(currentOrTargetUserId);
        }
    }

    private int getCurrentOrTargetUserId() {
        Pair<Integer, Integer> currentAndTargetUserIds = mBuiltin.getCurrentAndTargetUserIds();
        int currentUserId = currentAndTargetUserIds.first;
        int targetUserId = currentAndTargetUserIds.second;
        int currentOrTargetUserId = targetUserId != USER_NULL ? targetUserId : currentUserId;
        return currentOrTargetUserId;
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(@UserIdInt int newUserId) {
        if (DBG) Slogf.d(TAG, "handleCurrentUserSwitching user=%d", newUserId);
        maySwitchCurrentDriver(newUserId);
    }

    private void maySwitchCurrentDriver(int userId) {
        synchronized (mLock) {
            if (DBG) {
                Slogf.d(TAG, "maySwitchCurrentDriver old=%d, new=%d", mDriverUser, userId);
            }
            if (mDriverUser == userId) {
                return;
            }
            mDriverUser = userId;
            mDefaultDisplayForProfileUser.clear();
            mDisplayToProfileUserMapping.clear();
        }
    }

    /** Notifies user starting. */
    public void handleUserStarting(int startingUser) {
        if (DBG) Slogf.d(TAG, "handleUserStarting user=%d", startingUser);
        // Do nothing
    }

    /** Notifies user stopped. */
    public void handleUserStopped(@UserIdInt int stoppedUser) {
        if (DBG) Slogf.d(TAG, "handleUserStopped user=%d", stoppedUser);
        // Note that the current user is never stopped. It always takes switching into
        // non-current user before stopping the user.
        synchronized (mLock) {
            removeUserFromAllowlistsLocked(stoppedUser);
        }
    }

    @GuardedBy("mLock")
    private void removeUserFromAllowlistsLocked(int userId) {
        for (int i = mDisplayToProfileUserMapping.size() - 1; i >= 0; i--) {
            if (mDisplayToProfileUserMapping.valueAt(i) == userId) {
                mDisplayToProfileUserMapping.removeAt(i);
            }
        }
        mDefaultDisplayForProfileUser.delete(userId);
    }

    /**
     * Sets display allowlist for the {@code userId}. For passenger user, activity will be always
     * launched to a display in the allowlist. If requested display is not in the allowlist, the 1st
     * display in the allowlist will be selected as target display.
     *
     * <p>The allowlist is kept only for profile user. Assigning the current user unassigns users
     * for the given displays.
     */
    public void setDisplayAllowListForUser(@UserIdInt int userId, int[] displayIds) {
        if (DBG) {
            Slogf.d(TAG, "setDisplayAllowlistForUser userId:%d displays:%s",
                    userId, Arrays.toString(displayIds));
        }
        synchronized (mLock) {
            for (int displayId : displayIds) {
                if (!mPassengerDisplays.contains(displayId)) {
                    Slogf.w(TAG, "setDisplayAllowlistForUser called with display:%d"
                            + " not in passenger display list:%s", displayId, mPassengerDisplays);
                    continue;
                }
                if (userId == mDriverUser) {
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
                removeUserFromAllowlistsLocked(userId);
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
        if (DBG) {
            Slogf.d(TAG, "setPassengerDisplays displays:%s",
                    Arrays.toString(displayIdsForPassenger));
        }
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

    private boolean isLaunchAdjacent(RequestWrapper request) {
        return request != null && request.getIntent() != null
                && (request.getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0;
    }

    @GuardedBy("mLock")
    @Nullable
    private TaskWrapper getPreferredRootTaskLocked(@Nullable TaskWrapper task,
            @Nullable ActivityRecordWrapper source, @Nullable RequestWrapper request) {
        if (isLaunchAdjacent(request)) {
            Slogf.i(TAG, "LaunchAdjacent detected, the routing behavior will be altered.");
            return null;
        }
        if (source == null) {
            return null;
        }
        TaskWrapper sourceTask = source.getTask();
        if (sourceTask == null) {
            return null;
        }
        TaskWrapper sourceRootTask = sourceTask.getRootTask();
        if (sourceRootTask == null) {
            return null;
        }
        boolean launchLeadsToANewTask = task == null;
        Integer behavior = mRootTaskLaunchBehaviors.get(sourceRootTask.getBinder());
        if (behavior == null) {
            return null;
        }
        boolean shouldReparentToSource = behavior
                == CarActivityManager.LAUNCH_BEHAVIOR_REPARENT_TO_SOURCE_ROOT_TASK;
        boolean shouldRemainInSource = launchLeadsToANewTask && behavior
                == CarActivityManager.LAUNCH_BEHAVIOR_REMAIN_IN_SOURCE_ROOT_TASK;
        if (shouldReparentToSource || shouldRemainInSource) {
            Slogf.i(TAG, "Applying root task behavior, preferred root task=%s", sourceRootTask);
            return sourceRootTask;
        }
        return null;
    }

    /**
     * Calculates {@code outParams} based on the given arguments.
     * See {@code LaunchParamsController.LaunchParamsModifier.onCalculate()} for the detail.
     */
    public int calculate(CalculateParams params) {
        Trace.beginSection("CarLaunchParamsModifier-calculate");
        TaskWrapper task = params.getTask();
        ActivityRecordWrapper activity = params.getActivity();
        ActivityRecordWrapper source = params.getSource();
        ActivityOptionsWrapper options = params.getOptions();
        RequestWrapper request = params.getRequest();
        LaunchParamsWrapper currentParams = params.getCurrentParams();
        LaunchParamsWrapper outParams = params.getOutParams();

        int userId;
        if (task != null) {
            userId = task.getUserId();
        } else if (activity != null) {
            userId = activity.getUserId();
        } else if (request != null) {
            // task and activity could be null when starting via adb shell.
            userId = request.getUserId();
        } else {
            Slogf.w(TAG, "onCalculate, cannot decide user");
            Trace.endSection();
            return LaunchParamsWrapper.RESULT_SKIP;
        }
        // DisplayArea where user wants to launch the Activity.
        TaskDisplayAreaWrapper originalDisplayArea = currentParams.getPreferredTaskDisplayArea();
        // DisplayArea where CarLaunchParamsModifier targets to launch the Activity.
        TaskDisplayAreaWrapper targetDisplayArea = null;
        ComponentName activityName = null;
        if (activity != null) {
            activityName = activity.getComponentName();
        } else if (request != null) {
            activityName = request.getActivityComponentName();
        }
        if (DBG) {
            Slogf.d(TAG, "onCalculate, userId:%d original displayArea:%s activity:%s options:%s",
                    userId, originalDisplayArea, activityName, options);
        }
        // TODO(b/429003504): refactor this.
        decision:
        synchronized (mLock) {
            // If originalDisplayArea is set, respect that before ActivityOptions check.
            if (originalDisplayArea == null && options != null) {
                originalDisplayArea = options.getLaunchTaskDisplayArea();
                if (originalDisplayArea == null) {
                    // If task display area is not specified in options - try launch display id
                    originalDisplayArea = mBuiltin.getDefaultTaskDisplayAreaOnDisplay(
                            options.getOptions().getLaunchDisplayId());
                }
            }
            if (originalDisplayArea == null && source != null) {
                // try the display area of the source
                TaskDisplayAreaWrapper sourceDisplayArea = source.getDisplayArea();
                int sourceDisplayId = sourceDisplayArea == null
                        ? Display.INVALID_DISPLAY : sourceDisplayArea.getDisplay().getDisplayId();
                if (userId == getUserForDisplayLocked(sourceDisplayId)) {
                    originalDisplayArea = sourceDisplayArea;
                }
            }
            if (originalDisplayArea == null && options != null) {
                // try the caller display id
                int callerDisplayId = options.getCallerDisplayId();
                if (userId == getUserForDisplayLocked(callerDisplayId)) {
                    originalDisplayArea = mBuiltin.getDefaultTaskDisplayAreaOnDisplay(
                            callerDisplayId);
                }
            }
            if (mPersistentActivities.containsKey(activityName)) {
                targetDisplayArea = mPersistentActivities.get(activityName);
            } else if (originalDisplayArea == null
                    && task == null  // launching as a new task
                    && source != null && !source.isDisplayTrusted()
                    && !source.allowingEmbedded()) {
                if (DBG) {
                    Slogf.d(TAG, "Disallow launch on virtual display for not-embedded activity.");
                }
                targetDisplayArea = mBuiltin.getDefaultTaskDisplayAreaOnDisplay(
                        Display.DEFAULT_DISPLAY);
            }

            if (userId == mDriverUser) {
                // Let Core policy select display based on last focused display but check if the
                // selected display is assigned to the launching user. Redirect the launch to the
                // user's main assigned display.
                if (originalDisplayArea != null) {
                    targetDisplayArea = originalDisplayArea;
                }
                if (targetDisplayArea != null) {
                    int displayId = targetDisplayArea.getDisplay().getDisplayId();
                    int userForDisplay = getUserForDisplayLocked(displayId);
                    if (userForDisplay != userId) {
                        int mainDisplayId = mBuiltin.getMainDisplayAssignedToUser(userId);
                        if (mainDisplayId == Display.INVALID_DISPLAY) {
                            if (DBG) {
                                Slogf.d(TAG, "Selected display %d is not assigned to user %d,"
                                        + " falling back to default display as no assigned display"
                                        + " is found.", displayId, userId);
                            }
                            mainDisplayId = Display.DEFAULT_DISPLAY;
                        } else {
                            if (DBG) {
                                Slogf.d(TAG, "Selected display %d is not assigned to user %d,"
                                        + " falling back to main display %d assigned to the user.",
                                        displayId, userId, mainDisplayId);
                            }
                        }
                        targetDisplayArea = mBuiltin.getDefaultTaskDisplayAreaOnDisplay(
                                mainDisplayId);
                    }
                }
                if (DBG) Slogf.d(TAG, "Skip the further check for Driver");
                break decision;
            }

            if (userId == UserManagerHelper.USER_SYSTEM) {
                // This will be only allowed if it has FLAG_SHOW_FOR_ALL_USERS.
                // The flag is not immediately accessible here so skip the check.
                // But other WM policy will enforce it.
                if (DBG) Slogf.d(TAG, "Skip the further check for SystemUser");
                break decision;
            }
            // Now user is a passenger.
            if (mPassengerDisplays.isEmpty()) {
                // No displays for passengers. This could be old user and do not do anything.
                if (DBG) Slogf.d(TAG, "Skip the further check for no PassengerDisplays");
                break decision;
            }
            if (targetDisplayArea == null) {
                if (originalDisplayArea != null) {
                    targetDisplayArea = originalDisplayArea;
                } else {
                    targetDisplayArea = mBuiltin.getDefaultTaskDisplayAreaOnDisplay(
                            Display.DEFAULT_DISPLAY);
                }
            }
            Display display = targetDisplayArea.getDisplay();
            if ((display.getFlags() & Display.FLAG_PRIVATE) != 0) {
                // private display should follow its own restriction rule.
                if (DBG) Slogf.d(TAG, "Skip the further check for the private display");
                break decision;
            }
            if (DisplayHelper.getType(display) == DisplayHelper.TYPE_VIRTUAL) {
                // TODO(b/132903422) : We need to update this after the bug is resolved.
                // For now, don't change anything.
                if (DBG) Slogf.d(TAG, "Skip the further check for the virtual display");
                break decision;
            }
            int userForDisplay = getUserForDisplayLocked(display.getDisplayId());
            if (userForDisplay == userId) {
                if (DBG) Slogf.d(TAG, "The display is assigned for the user");
                break decision;
            }
            targetDisplayArea = getAlternativeDisplayAreaForPassengerLocked(
                    userId, activity, request);
        }

        // Modify the outParams now and track what all outParams were updated to send the correct
        // result later
        boolean needsSafeRegionApplied = false;
        boolean displayAreaChanged = false;
        boolean preferredRootTaskApplied = false;

        if (needsSafeRegionBounds(activity)) {
            outParams.setNeedsSafeRegionBounds(true);
            needsSafeRegionApplied = true;
        }
        if (targetDisplayArea != null && originalDisplayArea != targetDisplayArea) {
            Slogf.i(TAG, "Changed launching display, user:%d requested display area:%s"
                    + " target display area:%s", userId, originalDisplayArea, targetDisplayArea);
            outParams.setPreferredTaskDisplayArea(targetDisplayArea);
            if (options != null
                    && options.getLaunchWindowingMode()
                    != ActivityOptionsWrapper.WINDOWING_MODE_UNDEFINED) {
                outParams.setWindowingMode(options.getLaunchWindowingMode());
            }
            displayAreaChanged = true;
        } else {
            // Reaching here means that all the user boundary checks resulted into the launch
            // resolution on the same display. The task routing for-now only needs to work in such
            // cases.
            // TODO(b/441782369): Handle launch behaviors on different displays belonging to the
            // same user.
            TaskWrapper preferredRootTask = getPreferredRootTaskLocked(task, source, request);
            if (preferredRootTask != null) {
                outParams.setPreferredRootTask(preferredRootTask);
                preferredRootTaskApplied = true;
            }
        }

        final int result;
        if (displayAreaChanged || preferredRootTaskApplied) {
            result = LaunchParamsWrapper.RESULT_DONE;
        } else if (needsSafeRegionApplied) {
            // For a launch that doesn't involve overriding display or task, RESULT_SKIP can't be
            // used as that won't apply the safe region that is set in the outParams.
            // Even RESULT_DONE can't be applied as the default LaunchParamsModifier will miss out
            // on applying the default policy.
            result = LaunchParamsWrapper.RESULT_CONTINUE;
        } else {
            result = LaunchParamsWrapper.RESULT_SKIP;
        }
        Trace.endSection();
        return result;
    }

    private boolean needsSafeRegionBounds(ActivityRecordWrapper activity) {
        if (activity != null && activity.getComponentName() != null
                && requiresDisplayCompat(activity.getComponentName(), activity.getUserId())) {
            if (DBG) {
                Slogf.d(TAG, "Activity:%s needs to be within a safe region",
                        activity.getComponentName());
            }
            return true;
        }
        return false;
    }

    @GuardedBy("mLock")
    private int getUserForDisplayLocked(int displayId) {
        int userForDisplay = mDisplayToProfileUserMapping.get(displayId,
                UserManagerHelper.USER_NULL);
        if (userForDisplay != UserManagerHelper.USER_NULL) {
            return userForDisplay;
        }
        return mBuiltin.getUserAssignedToDisplay(displayId);
    }

    @GuardedBy("mLock")
    @Nullable
    private TaskDisplayAreaWrapper getAlternativeDisplayAreaForPassengerLocked(int userId,
            @Nullable ActivityRecordWrapper activityRecord, @Nullable RequestWrapper request) {
        if (DBG) Slogf.d(TAG, "getAlternativeDisplayAreaForPassengerLocked:%d", userId);
        List<TaskDisplayAreaWrapper> fallbacks = mBuiltin.getFallbackDisplayAreasForActivity(
                activityRecord, request);
        for (int i = 0, size = fallbacks.size(); i < size; ++i) {
            TaskDisplayAreaWrapper fallbackTda = fallbacks.get(i);
            int userForDisplay = getUserForDisplayLocked(fallbackTda.getDisplay().getDisplayId());
            if (userForDisplay == userId) {
                return fallbackTda;
            }
        }
        return fallbackDisplayAreaForUserLocked(userId);
    }

    /**
     * Return a {@link TaskDisplayAreaWrapper} that can be used if a source display area is
     * not found. First check the default display for the user. If it is absent select
     * the first passenger display if present.  If both are absent return {@code null}
     *
     * @param userId ID of the active user
     * @return {@link TaskDisplayAreaWrapper} that is recommended when a display area is
     *     not specified
     */
    @GuardedBy("mLock")
    @Nullable
    private TaskDisplayAreaWrapper fallbackDisplayAreaForUserLocked(@UserIdInt int userId) {
        int displayIdForUserProfile = mDefaultDisplayForProfileUser.get(userId,
                Display.INVALID_DISPLAY);
        if (displayIdForUserProfile != Display.INVALID_DISPLAY) {
            int displayId = mDefaultDisplayForProfileUser.get(userId);
            return mBuiltin.getDefaultTaskDisplayAreaOnDisplay(displayId);
        }
        int displayId = mBuiltin.getMainDisplayAssignedToUser(userId);
        if (displayId != Display.INVALID_DISPLAY) {
            return mBuiltin.getDefaultTaskDisplayAreaOnDisplay(displayId);
        }

        if (!mPassengerDisplays.isEmpty()) {
            displayId = mPassengerDisplays.get(0);
            if (DBG) {
                Slogf.d(TAG, "fallbackDisplayAreaForUserLocked: userId=%d, displayId=%d",
                        userId, displayId);
            }
            return mBuiltin.getDefaultTaskDisplayAreaOnDisplay(displayId);
        }
        return null;
    }

    /**
     * See {@link CarActivityManager#setPersistentActivity(android.content.ComponentName,int, int)}
     */
    public int setPersistentActivity(ComponentName activity, int displayId, int featureId) {
        try {
            Trace.beginSection(
                    "CarLaunchParamsModifier-setPersistentActivityOnDisplay: " + displayId);
            if (DBG) {
                Slogf.d(TAG, "setPersistentActivity: activity=%s, displayId=%d, featureId=%d",
                        activity, displayId, featureId);
            }
            if (featureId == DisplayAreaOrganizerHelper.FEATURE_UNDEFINED) {
                synchronized (mLock) {
                    TaskDisplayAreaWrapper removed = mPersistentActivities.remove(activity);
                    if (removed == null) {
                        throw new ServiceSpecificException(
                                CarActivityManager.ERROR_CODE_ACTIVITY_NOT_FOUND,
                                "Failed to remove " + activity.toShortString());
                    }
                    return CarActivityManager.RESULT_SUCCESS;
                }
            }
            TaskDisplayAreaWrapper tda = mBuiltin.findTaskDisplayArea(displayId, featureId);
            if (tda == null) {
                throw new IllegalArgumentException(
                        "Unknown display=" + displayId + " or feature=" + featureId);
            }
            synchronized (mLock) {
                mPersistentActivities.put(activity, tda);
            }
            return CarActivityManager.RESULT_SUCCESS;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Dump {code CarLaunchParamsModifierUpdatableImpl#mPersistentActivities}
     */
    public void dump(IndentingPrintWriter writer) {
        writer.println(TAG);
        writer.increaseIndent();
        writer.println("Persistent Activities:");
        writer.increaseIndent();
        synchronized (mLock) {
            if (mPersistentActivities.size() == 0) {
                writer.println("No activity persisted on a task display area");
            } else {
                for (int i = 0; i < mPersistentActivities.size(); i++) {
                    TaskDisplayAreaWrapper taskDisplayAreaWrapper =
                            mPersistentActivities.valueAt(i);
                    writer.println(
                            "Activity name: " + mPersistentActivities.keyAt(i) + " - Display ID: "
                                    + taskDisplayAreaWrapper.getDisplay().getDisplayId()
                                    + " , Feature ID: " + taskDisplayAreaWrapper.getFeatureId());
                }
            }
            writer.decreaseIndent();
            writer.decreaseIndent();
            writer.println("Root Task Launch Behaviors:");
            writer.increaseIndent();
            if (mRootTaskLaunchBehaviors.isEmpty()) {
                writer.println("No root task launch behavior is specified.");
            } else {
                for (Map.Entry<IBinder, Integer> entry : mRootTaskLaunchBehaviors.entrySet()) {
                    IBinder token = entry.getKey();
                    String name = mTaskStackRepository.getRootTaskName(token);
                    writer.println("Root task id: " + token + " ("
                            + (name != null ? name : "name not found")
                            + "), Behavior: " + launchBehaviorToString(entry.getValue()));
                }
            }
        }
    }

    private static String launchBehaviorToString(int behavior) {
        return switch (behavior) {
            case CarActivityManager.LAUNCH_BEHAVIOR_REPARENT_TO_SOURCE_ROOT_TASK ->
                    "REPARENT_TO_SOURCE_ROOT_TASK";
            case CarActivityManager.LAUNCH_BEHAVIOR_REMAIN_IN_SOURCE_ROOT_TASK ->
                    "REMAIN_IN_SOURCE_ROOT_TASK";
            default -> "DEFAULT";
        };
    }

    /**
     * Sets the launch behavior for a given root task.
     * See {@link android.car.app.CarActivityManager#setLaunchBehaviorForRootTask(IBinder, int)}.
     *
     * @param rootTaskToken The token of the root task.
     * @param behavior The launch behavior to set.
     */
    public void setLaunchBehaviorForRootTask(IBinder rootTaskToken, int behavior) {
        synchronized (mLock) {
            if (behavior == CarActivityManager.LAUNCH_BEHAVIOR_DEFAULT) {
                mRootTaskLaunchBehaviors.remove(rootTaskToken);
            } else {
                mRootTaskLaunchBehaviors.put(rootTaskToken, behavior);
            }
        }
    }
}
