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
import android.content.ComponentName;
import android.os.UserHandle;

import java.io.File;
import java.util.List;

/**
 * Interface implemented by CarServiceHelperService.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public interface CarServiceHelperInterface {

    // TODO(b/190458000): Investigate if it is possible to move policy to updatable.
    /**
     * Sets display allow list for user
     */
    void setDisplayAllowlistForUser(@NonNull UserHandle user, int[] displayIds);

    // TODO(b/190458000): Investigate if it is possible to move policy to updatable.
    /**
     * Sets passenger displays
     */
    void setPassengerDisplays(int[] displayIdsForPassenger);

    // TODO(b/190458000): Investigate if it is possible to move policy to updatable.
    /**
     * Sets source preferred components
     */
    void setSourcePreferredComponents(boolean enableSourcePreferred,
            @Nullable List<ComponentName> sourcePreferredComponents);

    /**
     * Designates the given {@code activity} to be launched in {@code TaskDisplayArea} of
     * {@code featureId} in the display of {@code displayId}.
     */
    int setPersistentActivity(ComponentName activity, int displayId, int featureId);

    /**
     * Sets safety mode
     */
    void setSafetyMode(boolean safe);

    /**
     * Creates user even when disallowed
     */
    @Nullable
    UserHandle createUserEvenWhenDisallowed(@Nullable String name, @NonNull String userType,
            int flags);

    /**
     * Dumps service stacks
     */
    @Nullable
    File dumpServiceStacks();
}
