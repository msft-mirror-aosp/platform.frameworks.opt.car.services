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

import static android.content.pm.PackageManager.FEATURE_CAR_DISPLAY_COMPATIBILITY;

import static com.android.server.wm.CompatModePackages.DOWNSCALE_90;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_85;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_80;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_75;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_70;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_65;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_60;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_55;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_50;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_45;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_40;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_35;
import static com.android.server.wm.CompatModePackages.DOWNSCALE_30;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.compat.CompatChanges;
import android.car.builtin.util.Slogf;
import android.car.feature.Flags;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.CompatScaleWrapper;
import android.content.res.CompatibilityInfo.CompatScale;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

/**
 * Automotive implementation of {@link CompatScaleProvider}
 * This class is responsible for providing different scaling factor for some automotive specific
 * packages.
 *
 * @hide
 */
public final class CarDisplayCompatScaleProvider implements CompatScaleProvider {
    private static final String TAG = CarDisplayCompatScaleProvider.class.getSimpleName();

    private CarDisplayCompatScaleProviderUpdatable mCarCompatScaleProviderUpdatable;
    private ActivityTaskManagerService mAtms;
    private PackageManager mPackageManager;

    /**
     * Registers {@link CarDisplayCompatScaleProvider} with {@link ActivityTaskManagerService}
     */
    public void init(Context context) {
        if (!Flags.displayCompatibility()) {
            Slogf.i(TAG, Flags.FLAG_DISPLAY_COMPATIBILITY + " is not enabled");
            return;
        }
        mPackageManager = context.getPackageManager();
        if (mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            mAtms = (ActivityTaskManagerService) ActivityTaskManager.getService();
            mAtms.registerCompatScaleProvider(COMPAT_SCALE_MODE_PRODUCT, this);
            Slogf.i(TAG, "registered Car service as a CompatScaleProvider.");
        }
    }

    /**
     * Sets the given {@link CarActivityInterceptorUpdatable} which this internal class will
     * communicate with.
     */
    public void setUpdatable(
            CarDisplayCompatScaleProviderUpdatable carCompatScaleProviderUpdatable) {
        mCarCompatScaleProviderUpdatable = carCompatScaleProviderUpdatable;
    }

    @Nullable
    @Override
    public CompatScale getCompatScale(@NonNull String packageName, int uid) {
        if (mCarCompatScaleProviderUpdatable == null) {
            Slogf.w(TAG, "mCarCompatScaleProviderUpdatable not set");
            return null;
        }
        CompatScaleWrapper wrapper = mCarCompatScaleProviderUpdatable
                .getCompatScale(packageName, UserHandle.getUserId(uid));
        return wrapper == null ? null : new CompatScale(wrapper.getScaleFactor(),
                wrapper.getDensityScaleFactor());
    }

    /**
     * @return an interface that exposes mainly APIs that are not available on client side.
     */
    public CarDisplayCompatScaleProviderInterface getBuiltinInterface() {
        return new CarDisplayCompatScaleProviderInterface() {

            @Override
            public Pair<Integer, Integer> getCurrentAndTargetUserIds() {
                return LocalServices.getService(ActivityManagerInternal.class)
                        .getCurrentAndTargetUserIds();
            }

            @Override
            public int getMainDisplayAssignedToUser(int userId) {
                return LocalServices.getService(UserManagerInternal.class)
                        .getMainDisplayAssignedToUser(userId);
            }

            @Override
            public PackageInfo getPackageInfoAsUser(String packageName,
                    PackageInfoFlags flags, int userId)
                    throws PackageManager.NameNotFoundException {
                return mPackageManager.getPackageInfoAsUser(packageName, flags, userId);
            }

            @Override
            public String getStringForUser(ContentResolver resolver, String name,
                    int userId) {
                return Settings.Secure.getStringForUser(resolver, name, userId);
            }

            @Override
            public boolean putStringForUser(ContentResolver resolver, String name, String value,
                    int userId) {
                return Settings.Secure.putStringForUser(resolver, name, value,
                        userId);
            }

            /**
             * Implementation is exact copy of {@code CompatModePackages#getScalingFactor}
             */
            @Override
            public float getCompatModeScalingFactor(@NonNull String packageName,
                    @NonNull UserHandle userHandle) {
                if (CompatChanges.isChangeEnabled(DOWNSCALE_90, packageName, userHandle)) {
                    return 0.9f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_85, packageName, userHandle)) {
                    return 0.85f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_80, packageName, userHandle)) {
                    return 0.8f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_75, packageName, userHandle)) {
                    return 0.75f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_70, packageName, userHandle)) {
                    return 0.7f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_65, packageName, userHandle)) {
                    return 0.65f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_60, packageName, userHandle)) {
                    return 0.6f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_55, packageName, userHandle)) {
                    return 0.55f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_50, packageName, userHandle)) {
                    return 0.5f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_45, packageName, userHandle)) {
                    return 0.45f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_40, packageName, userHandle)) {
                    return 0.4f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_35, packageName, userHandle)) {
                    return 0.35f;
                }
                if (CompatChanges.isChangeEnabled(DOWNSCALE_30, packageName, userHandle)) {
                    return 0.3f;
                }
                return 1f;
            }
        };
    }
}
