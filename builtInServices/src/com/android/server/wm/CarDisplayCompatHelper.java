/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.android.server.wm.CompatModePackages.DOWNSCALED;
import static com.android.server.wm.CompatModePackages.DOWNSCALED_INVERSE;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_30;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_35;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_40;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_45;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_50;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_55;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_60;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_65;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_70;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_75;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_80;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_85;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_90;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.app.compat.CompatChanges;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.util.List;

/**
 * An interface that exposes mainly APIs that are not available on client side.
 *
 * @hide
 */
public final class CarDisplayCompatHelper implements CarDisplayCompatHelperInterface {
    private final PackageManager mPackageManager;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final UserManagerInternal mUserManagerInternal;

    public CarDisplayCompatHelper(Context context) {
        mPackageManager = context.getPackageManager();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
    }

    @NonNull
    @Override
    public Pair<Integer, Integer> getCurrentAndTargetUserIds() {
        return mActivityManagerInternal.getCurrentAndTargetUserIds();
    }

    @Override
    public int getMainDisplayAssignedToUser(int userId) {
        return mUserManagerInternal.getMainDisplayAssignedToUser(userId);
    }

    @SuppressLint("MissingPermission")
    @Override
    public PackageInfo getPackageInfoAsUser(@NonNull String packageName,
            @NonNull PackageManager.PackageInfoFlags flags, int userId)
            throws PackageManager.NameNotFoundException {
        return mPackageManager.getPackageInfoAsUser(packageName, flags, userId);
    }

    @Override
    public String getStringForUser(ContentResolver resolver, String name, int userId) {
        return Settings.Secure.getStringForUser(resolver, name, userId);
    }

    @Override
    public boolean putStringForUser(ContentResolver resolver, String name, String value,
            int userId) {
        return Settings.Secure.putStringForUser(resolver, name, value, userId);
    }

    /**
     * Implementation is exact copy of {@code CompatModePackages#getScalingFactor}
     */
    @SuppressLint("MissingPermission")
    @Override
    public float getCompatModeScalingFactor(@NonNull String packageName,
            @NonNull UserHandle userHandle) {
        boolean isDownscaled =
                CompatChanges.isChangeEnabled(DOWNSCALED, packageName, userHandle);
        boolean isDownscaledInverse =
                CompatChanges.isChangeEnabled(DOWNSCALED_INVERSE, packageName, userHandle);

        if (!isDownscaled && !isDownscaledInverse) {
            return 1f;
        }

        if (CompatChanges.isChangeEnabled(DOWNSCALE_90, packageName, userHandle)) {
            return isDownscaledInverse ? 0.9f : 1 / 0.9f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_85, packageName, userHandle)) {
            return isDownscaledInverse ? 0.85f : 1 / 0.85f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_80, packageName, userHandle)) {
            return isDownscaledInverse ? 0.8f : 1 / 0.8f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_75, packageName, userHandle)) {
            return isDownscaledInverse ? 0.75f : 1 / 0.75f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_70, packageName, userHandle)) {
            return isDownscaledInverse ? 0.7f : 1 / 0.7f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_65, packageName, userHandle)) {
            return isDownscaledInverse ? 0.65f : 1 / 0.65f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_60, packageName, userHandle)) {
            return isDownscaledInverse ? 0.6f : 1 / 0.6f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_55, packageName, userHandle)) {
            return isDownscaledInverse ? 0.55f : 1 / 0.55f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_50, packageName, userHandle)) {
            return isDownscaledInverse ? 0.5f : 1 / 0.50f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_45, packageName, userHandle)) {
            return isDownscaledInverse ? 0.45f : 1 / 0.45f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_40, packageName, userHandle)) {
            return isDownscaledInverse ? 0.4f : 1 / 0.4f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_35, packageName, userHandle)) {
            return isDownscaledInverse ? 0.35f : 1 / 0.35f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_30, packageName, userHandle)) {
            return isDownscaledInverse ? 0.3f : 1 / 0.3f;
        }
        return 1f;
    }

    @NonNull
    @Override
    public List<ApplicationInfo> getInstalledApplicationsAsUser(
            @NonNull PackageManager.ApplicationInfoFlags flags, int userId) {
        return mPackageManager.getInstalledApplicationsAsUser(flags, userId);
    }
}
