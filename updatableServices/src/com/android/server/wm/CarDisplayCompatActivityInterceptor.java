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

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.car.feature.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

/**
 * This class handles launching the display compat host app.
 *
 * @hide
 */
public final class CarDisplayCompatActivityInterceptor implements CarActivityInterceptorUpdatable {

    public static final String TAG = CarDisplayCompatActivityInterceptor.class.getSimpleName();

    private static final String DISPLAYCOMPAT_SYSTEM_FEATURE = "android.car.displaycompatibility";
    private ComponentName mHostActivity;

    public CarDisplayCompatActivityInterceptor(@NonNull Context context,
            @NonNull CarDisplayCompatScaleProviderUpdatable carDisplayCompatProvider) {
        if (!Flags.displayCompatibility()) {
            Slogf.i(TAG, Flags.FLAG_DISPLAY_COMPATIBILITY + " is not enabled");
            return;
        }
        PackageManager packageManager = context.getPackageManager();
        if (packageManager != null
                && !packageManager.hasSystemFeature(DISPLAYCOMPAT_SYSTEM_FEATURE)) {
            Slogf.i(TAG, DISPLAYCOMPAT_SYSTEM_FEATURE + " is not available");
            return;
        }
        Resources r = context.getResources();
        if (r == null) {
            // This happens during tests where mock context is used.
            Slogf.e(TAG, "Couldn't read DisplayCompat host activity.");
            return;
        }
        int id = r.getIdentifier("config_defaultDisplayCompatHostActivity", "string", "android");
        if (id != 0) {
            mHostActivity = ComponentName.unflattenFromString(r.getString(id));
            if (mHostActivity == null) {
                Slogf.e(TAG, "Couldn't read DisplayCompat host activity.");
            }
        }
    }

    @Override
    public ActivityInterceptResultWrapper onInterceptActivityLaunch(
            ActivityInterceptorInfoWrapper info) {
        if (mHostActivity == null) {
            return null;
        }
        if (info.getIntent() == null) {
            return null;
        }

        // TODO(b/322193839)

        return null;
    }
}
