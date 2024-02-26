/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.content.pm.PackageManager.FEATURE_CAR_DISPLAY_COMPATIBILITY;

import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo.CompatScale;

import com.android.server.utils.Slogf;

/**
 * Automotive implementation of {@link CompatScaleProvider}
 * This class is responsible for providing different scaling factor for some automotive specific
 * packages.
 *
 * @hide
 */
public final class CarDisplayCompatScaleProvider implements CompatScaleProvider {
    private static final String TAG = CarDisplayCompatScaleProvider.class.getSimpleName();

    @NonNull
    private Context mContext;

    /**
     * Registers {@link CarDisplayCompatScaleProvider} with {@link ActivityTaskManagerService}
     */
    public void init(@NonNull Context context) {
        mContext = context;
        PackageManager packageManager = context.getPackageManager();
        if (!packageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            Slogf.i(TAG, "Feature not available " + FEATURE_CAR_DISPLAY_COMPATIBILITY);
            return;
        }

        ActivityTaskManagerService atms =
                (ActivityTaskManagerService) ActivityTaskManager.getService();
        atms.registerCompatScaleProvider(COMPAT_SCALE_MODE_PRODUCT, this);
        Slogf.i(TAG, "registered Car service as a CompatScaleProvider.");
    }

    @Nullable
    @Override
    public CompatScale getCompatScale(@NonNull String packageName, int uid) {
        return null;
    }
}
