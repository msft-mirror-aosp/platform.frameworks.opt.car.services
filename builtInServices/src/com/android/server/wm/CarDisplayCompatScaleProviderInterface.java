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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;

/**
 * Interface implemented by {@link com.android.server.wm.CarDisplayCompatScaleProvider} and
 * used by {@link CarDisplayCompatScaleProviderUpdatable}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public interface CarDisplayCompatScaleProviderInterface {
    /**
     * @return a pair of the current userId and the target userId.
     * The target userId is the user to switch during switching the driver,
     * or {@link android.os.UserHandle.USER_NULL}.
     *
     * See {@link android.app.ActivityManagerInternal#getCurrentAndTargetUserIds}
     */
    @NonNull Pair<Integer, Integer> getCurrentAndTargetUserIds();

    /**
     * Returns the main display id assigned to the user, or {@code Display.INVALID_DISPLAY} if the
     * user is not assigned to any main display.
     * See {@link com.android.server.pm.UserManagerInternal#getMainDisplayAssignedToUser(int)} for
     * the detail.
     */
    int getMainDisplayAssignedToUser(@UserIdInt int userId);

    /**
     * See {@link PackageManager#getPackageInfoAsUser(String, PackageInfoFlags, int)} for details.
     */
    @Nullable
    PackageInfo getPackageInfoAsUser(@NonNull String packageName,
            @NonNull PackageInfoFlags flags, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException;

    /**
     * See {@link Settings.Secure#getStringForUser(ContentResolver, String, int)}
     */
    String getStringForUser(ContentResolver resolver, String name, @UserIdInt int userId);

    /**
     * See {@link Settings.Secure#setStringForUser(ContentResolver, String, String, int)}
     */
    boolean putStringForUser(ContentResolver resolver, String name, String value,
            @UserIdInt int userId);

    /**
     * Returns the compat mode scale if framework already set a scaling for this package.
     * see {@link CompatChanges#isChangeEnabled}
     */
    float getCompatModeScalingFactor(@NonNull String packageName,
            @NonNull UserHandle user);
}
