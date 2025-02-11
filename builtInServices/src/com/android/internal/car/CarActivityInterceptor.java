/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.car;

import static android.content.pm.PackageManager.FEATURE_CAR_DISPLAY_COMPATIBILITY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.TaskInfo;
import android.car.builtin.util.Slogf;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityInterceptResultWrapper;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityInterceptorInfoWrapper;
import com.android.server.wm.CarActivityInterceptorInterface;
import com.android.server.wm.CarActivityInterceptorUpdatable;


/**
 * See {@link ActivityInterceptorCallback}.
 *
 * @hide
 */
public final class CarActivityInterceptor implements ActivityInterceptorCallback {
    private static final String TAG  = CarActivityInterceptor.class.getSimpleName();
    private CarActivityInterceptorUpdatable mCarActivityInterceptorUpdatable;

    private final PackageManager mPackageManager;

    private final Object mLock = new Object();
    /**
     * Maps package names to the id of the display that the package is set up to launch on.
     *
     * TODO(b/331089039): This is needed in order to get the correct scaling factor from the config
     * file. For example, a package might need a different scaling on display 0 vs display 2.
     *
     * Note that this value is cached based on when the process is created for the first activity
     * of the package. Therefore, if subsequent activities of the package launch on different
     * displays their configuration will be based on the new display's configuration.
     *
     * Also, the package scaling will be based on the {@link DEFAULT_DISPLAY}'s configuration
     * if the process of a package is created because of a broadcast receiver or a content provider.
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseIntArray mPackageUidToLastLaunchedActivityDisplayIdMap =
            new SparseIntArray();

    public CarActivityInterceptor(@NonNull Context context) {
        mCarActivityInterceptorUpdatable = null;
        mPackageManager = context.getPackageManager();
    }

    /**
     * Sets the given {@link CarActivityInterceptorUpdatable} which this internal class will
     * communicate with.
     */
    public void setUpdatable(CarActivityInterceptorUpdatable carActivityInterceptorUpdatable) {
        mCarActivityInterceptorUpdatable = carActivityInterceptorUpdatable;
    }

    @Nullable
    @Override
    public ActivityInterceptResult onInterceptActivityLaunch(ActivityInterceptorInfo info) {
        if (mCarActivityInterceptorUpdatable == null) {
            Slogf.w(TAG, "mCarActivityInterceptorUpdatable not set");
            return null;
        }

        if (mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            if (info.getIntent() != null
                && info.getIntent().getComponent() != null
                && info.getCheckedOptions() != null) {

                    synchronized (mLock) {
                        int displayId = info.getCheckedOptions().getLaunchDisplayId();
                        if (displayId == INVALID_DISPLAY) {
                            displayId = DEFAULT_DISPLAY;
                            // TODO(b/331089039): {@link Activity} should start on the display of the
                            // calling package if {@code ActivityOptions#launchDisplayId} is set to
                            // {@link INVALID_DISPLAY}. Therefore, the display will be set to
                            // {@link DEFAULT_DISPLAY} if the calling package's display isn't available in
                            // the cache.
                            int callingUid = info.getCallingUid() != -1 ? info.getCallingUid() : info.getRealCallingUid();
                            if (callingUid != -1) {
                                displayId = mPackageUidToLastLaunchedActivityDisplayIdMap
                                        .get(callingUid, displayId);
                            }
                        }

                        mPackageUidToLastLaunchedActivityDisplayIdMap
                                .put(info.getActivityInfo().applicationInfo.uid, displayId);
                    }
            }

        }

        ActivityInterceptResultWrapper interceptResultWrapper = mCarActivityInterceptorUpdatable
                .onInterceptActivityLaunch(ActivityInterceptorInfoWrapper.create(info));
        if (interceptResultWrapper == null) {
            return null;
        }
        return interceptResultWrapper.getInterceptResult();
    }

    @Override
    public void onActivityLaunched(TaskInfo taskInfo, ActivityInfo activityInfo,
            ActivityInterceptorInfo info) {
        // do nothing
    }

    CarActivityInterceptorInterface getBuiltinInterface() {
        return new CarActivityInterceptorInterface() {
            @Override
            public int getUserAssignedToDisplay(int displayId) {
                UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
                int userId = umi.getUserAssignedToDisplay(displayId);
                return userId;
            }

            @Override
            public int getMainDisplayAssignedToUser(int userId) {
                UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
                int displayId = umi.getMainDisplayAssignedToUser(userId);
                return displayId;
            }
        };
    }

    /**
    * Returns display id of for the given uid.
    */
    public int getPackageDisplay(int uid) {
        synchronized (mLock) {
            return mPackageUidToLastLaunchedActivityDisplayIdMap.get(uid, DEFAULT_DISPLAY);
        }
    }
}
