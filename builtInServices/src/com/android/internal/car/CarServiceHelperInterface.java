/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.car.builtin.annotation.PlatformVersion;
import android.os.UserHandle;

import com.android.annotation.AddedIn;

import java.io.File;

/**
 * Interface implemented by CarServiceHelperService.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public interface CarServiceHelperInterface {

    /**
     * Sets safety mode
     */
    @AddedIn(PlatformVersion.TIRAMISU_0)
    void setSafetyMode(boolean safe);

    /**
     * Creates user even when disallowed
     */
    @Nullable
    @AddedIn(PlatformVersion.TIRAMISU_0)
    UserHandle createUserEvenWhenDisallowed(@Nullable String name, @NonNull String userType,
            int flags);

    /**
     * Checks if the user is visible.
     */
    @AddedIn(PlatformVersion.UPSIDE_DOWN_CAKE_0)
    int getDisplayAssignedToUser(@UserIdInt int userId);

    /**
     * Dumps service stacks
     */
    @Nullable
    @AddedIn(PlatformVersion.TIRAMISU_0)
    File dumpServiceStacks();

    /** Check {@link android.os.Process#setProcessGroup(int, int)}. */
    @AddedIn(PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void setProcessGroup(int pid, int group);

    /** Check {@link android.os.Process#getProcessGroup(int)}. */
    @AddedIn(PlatformVersion.UPSIDE_DOWN_CAKE_0)
    int getProcessGroup(int pid);
}
