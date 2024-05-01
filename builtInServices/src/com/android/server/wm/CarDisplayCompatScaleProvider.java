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

import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.FeatureInfo.FLAG_REQUIRED;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.content.pm.PackageManager.FEATURE_CAR_DISPLAY_COMPATIBILITY;
import static android.content.pm.PackageManager.GET_ACTIVITIES;
import static android.content.pm.PackageManager.GET_CONFIGURATIONS;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.SIGNATURE_MATCH;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.CarDisplayCompatConfig.ANY_PACKAGE;
import static com.android.server.wm.CarDisplayCompatConfig.DEFAULT_SCALE;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT;

import static java.lang.Math.abs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.compat.CompatChanges;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo.CompatScale;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.car.CarActivityInterceptor;
import com.android.server.LocalServices;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.StampedLock;

/**
 * Automotive implementation of {@link CompatScaleProvider}
 * This class is responsible for providing different scaling factor for some automotive specific
 * packages.
 *
 * @hide
 */
public final class CarDisplayCompatScaleProvider implements CompatScaleProvider {
    private static final String TAG = CarDisplayCompatScaleProvider.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String META_DATA_DISTRACTION_OPTIMIZED = "distractionOptimized";
    private static final String PLATFORM_PACKAGE_NAME = "android";
    private static final String DISPLAYCOMPAT_SETTINGS_SECURE_KEY =
            FEATURE_CAR_DISPLAY_COMPATIBILITY + ":settings:secure";
    private static final String CONFIG_PATH = "etc/display_compat_config.xml";
    private static final float NO_SCALE = 0f;
    private static final float OPT_OUT = -1 * DEFAULT_SCALE;
    // {@code CarPackageManager#ERROR_CODE_NO_PACKAGE}
    private static final int ERROR_CODE_NO_PACKAGE = -100;
    private static final String DATA_SCHEME_PACKAGE = "package";

    @NonNull
    private Context mContext;
    @NonNull
    private PackageManager mPackageManager;

    // {@link StampedLock} is used for 2 reasons
    // 1) the # of reads is way higher than # of writes.
    // 2) {@code ReentrantReadWriteLock} is not very efficient.
    private final StampedLock mConfigLock = new StampedLock();
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    @NonNull
    private final CarDisplayCompatConfig mConfig = new CarDisplayCompatConfig();
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private final ArrayMap<String, Boolean> mRequiresDisplayCompat = new ArrayMap<>();

    @NonNull
    private CarActivityInterceptor mActivityInterceptor;

    @NonNull
    private BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData().getSchemeSpecificPart();
            if (DBG) {
                Slogf.d(TAG, "package intent " + intent);
                Slogf.d(TAG, "package uri " + intent.getData());
            }
            if (packageName == null || packageName.isEmpty()) {
                return;
            }
            long stamp = mConfigLock.writeLock();
            try {
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    mRequiresDisplayCompat.remove(packageName);
                } else {
                    updateStateOfPackageForUserLocked(packageName, getCurrentOrTargetUserId());
                }
            } finally {
                mConfigLock.unlockWrite(stamp);
            }
        }
    };

    public CarDisplayCompatScaleProvider(@NonNull Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    /**
     * Registers {@link CarDisplayCompatScaleProvider} with {@link ActivityTaskManagerService}
     */
    public void init(@NonNull CarActivityInterceptor activityInterceptor) {
        mActivityInterceptor = activityInterceptor;
        if (!mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            Slogf.i(TAG, "Feature %s not available ", FEATURE_CAR_DISPLAY_COMPATIBILITY);
            return;
        }

        long stamp = mConfigLock.writeLock();
        try {
            if (!updateConfigForUserFromSettingsLocked(getCurrentOrTargetUserId())) {
                updateCurrentConfigFromDeviceLocked();
            }
        } finally {
            mConfigLock.unlockWrite(stamp);
        }

        Uri keyUri = Settings.Secure.getUriFor(DISPLAYCOMPAT_SETTINGS_SECURE_KEY);
        mContext.getContentResolver().registerContentObserver(keyUri,
                /*notifyForDescendants*/ true,
                new ContentObserver(mContext.getMainThreadHandler()) {
                @Override
                public void onChange(boolean selfChange, Collection<Uri> uris,
                        int flags, int userId) {
                    super.onChange(selfChange, uris, flags, userId);
                    if (selfChange) {
                        return;
                    }
                    if (getCurrentOrTargetUserId() == userId) {
                        long stamp = mConfigLock.writeLock();
                        try {
                            updateConfigForUserFromSettingsLocked(userId);
                        } finally {
                            mConfigLock.unlockWrite(stamp);
                        }
                    }
                }
            }, UserHandle.USER_ALL);

        ActivityTaskManagerService atms =
                (ActivityTaskManagerService) ActivityTaskManager.getService();
        atms.registerCompatScaleProvider(COMPAT_SCALE_MODE_PRODUCT, this);
        Slogf.i(TAG, "registered Car service as a CompatScaleProvider.");

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme(DATA_SCHEME_PACKAGE);
        mContext.registerReceiver(mPackageChangeReceiver, filter);
    }

    @Nullable
    @Override
    public CompatScale getCompatScale(@NonNull String packageName, int uid) {
        int displayId = mActivityInterceptor.getPackageDisplay(uid);
        UserHandle user = UserHandle.getUserHandleForUid(uid);
        return getCompatScaleForPackageAsUser(displayId, packageName, user, true);
    }

    /**
     * @return true if package requires launching in automotive compatibility mode
     */
    public boolean requiresDisplayCompat(@NonNull String packageName, @UserIdInt int userId) {
        long stamp = mConfigLock.tryOptimisticRead();
        Boolean res = mRequiresDisplayCompat.get(packageName);
        if (!mConfigLock.validate(stamp)) {
            stamp = mConfigLock.readLock();
            try {
                res = mRequiresDisplayCompat.get(packageName);
            } finally {
                mConfigLock.unlockRead(stamp);
            }
        }
        if (res != null) {
            if (DBG) {
                Slogf.d(TAG, "Package %s is cached %b", packageName, res.booleanValue());
            }
            return res.booleanValue();
        } else {
            stamp = mConfigLock.writeLock();
            try {
                return updateStateOfPackageForUserLocked(packageName, userId);
            } finally {
                mConfigLock.unlockWrite(stamp);
            }
        }
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(@UserIdInt int newUserId) {
        long stamp = mConfigLock.writeLock();
        try {
            if (!updateConfigForUserFromSettingsLocked(newUserId)) {
                updateCurrentConfigFromDeviceLocked();
            }
        } finally {
            mConfigLock.unlockWrite(stamp);
        }
    }

    /**
     * Dump {@code CarDisplayCompatScaleProviderUpdatableImpl#mConfig}
     */
    public void dump(@NonNull IndentingPrintWriter writer) {
        writer.println(TAG);
        writer.increaseIndent();
        writer.println("DisplayCompat Config:");
        writer.increaseIndent();
        long stamp = mConfigLock.writeLock();
        try {
            writer.println(mConfig.dump());
        } finally {
            mConfigLock.unlockWrite(stamp);
        }
        writer.decreaseIndent();
        writer.decreaseIndent();
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private void updateStateOfAllPackagesForUserLocked(@UserIdInt int userId) {
        List<ApplicationInfo> allPackagesForUser =
                mPackageManager.getInstalledApplicationsAsUser(GET_META_DATA, userId);
        for (int i = 0; i < allPackagesForUser.size(); i++) {
            ApplicationInfo appInfo = allPackagesForUser.get(i);
            updateStateOfPackageForUserLocked(appInfo.packageName, userId);
        }
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean updateStateOfPackageForUserLocked(@NonNull String packageName,
            @UserIdInt int userId) {
        int displayId = getPackageDisplayIdAsUserLocked(packageName, userId);

        try {
            CarDisplayCompatConfig.Key key =
                    new CarDisplayCompatConfig.Key(displayId, packageName, userId);
            float scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            boolean hasConfig = true;
            if (scaleFactor == NO_SCALE) {
                key.mUserId = UserHandle.ALL.getIdentifier();
                scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
                if (scaleFactor == NO_SCALE) {
                    hasConfig = false;
                }
            }

            boolean result = requiresDisplayCompatNotCachedLocked(packageName, userId);
            if (!hasConfig && !result) {
                // Package is opt-out
                mConfig.setScaleFactor(key, OPT_OUT);
            } else if (!hasConfig && result) {
                // Apply user default scale or display default scale to the package
                key.mPackageName = ANY_PACKAGE;
                key.mUserId = userId;
                scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
                if (scaleFactor == NO_SCALE) {
                    key.mUserId = UserHandle.ALL.getIdentifier();
                    scaleFactor = mConfig.getScaleFactor(key, DEFAULT_SCALE);
                }
                mConfig.setScaleFactor(key, scaleFactor);
            } else if (hasConfig) {
                // Package was opt-out, but now is opt-in or the otherway around
                mConfig.setScaleFactor(key, result ? abs(scaleFactor) : -1 * abs(scaleFactor));
            }

            mRequiresDisplayCompat.put(packageName, result);
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    DISPLAYCOMPAT_SETTINGS_SECURE_KEY, mConfig.dump(),
                    getCurrentOrTargetUserId());

            return result;
        } catch (PackageManager.NameNotFoundException e) {
            // This shouldn't be the case if the user requesting the package is the same as
            // the user launching the app.
            Slogf.e(TAG, "Package " + packageName + " not found", e);
            throw new ServiceSpecificException(
                    ERROR_CODE_NO_PACKAGE,
                    e.getMessage());
        }
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private int getPackageDisplayIdAsUserLocked(@NonNull String packageName,
            @UserIdInt int userId) {
        int displayId = DEFAULT_DISPLAY;
        try {
            if (mPackageManager != null && mActivityInterceptor != null) {
                // This can happen if {@link #init} was not called before user switch.
                int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
                displayId = mActivityInterceptor.getPackageDisplay(uid);
            } else {
                Log.d(TAG, "was init called? " + mPackageManager + " " + mActivityInterceptor);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "package not found " + packageName + " " + userId);
        }
        return displayId;
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean requiresDisplayCompatNotCachedLocked(@NonNull String packageName,
            @UserIdInt int userId) throws PackageManager.NameNotFoundException {

        UserHandle userHandle = UserHandle.of(userId);
        ApplicationInfo applicationInfo = mPackageManager
                .getApplicationInfoAsUser(packageName, GET_META_DATA, userHandle);

        // application has {@code FEATURE_CAR_DISPLAY_COMPATIBILITY} metadata
        if (applicationInfo != null &&  applicationInfo.metaData != null
                && applicationInfo.metaData.containsKey(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            if (DBG) {
                Slogf.d(TAG, "Package %s has %s metadata", packageName,
                        FEATURE_CAR_DISPLAY_COMPATIBILITY);
            }
            return applicationInfo.metaData.getBoolean(FEATURE_CAR_DISPLAY_COMPATIBILITY);
        }

        PackageInfo pkgInfo = mPackageManager
                .getPackageInfoAsUser(packageName, GET_CONFIGURATIONS | GET_ACTIVITIES, userId);

        // Opt out if has {@code FEATURE_AUTOMOTIVE}
        if (pkgInfo != null && pkgInfo.reqFeatures != null) {
            FeatureInfo[] features = pkgInfo.reqFeatures;
            for (FeatureInfo feature: features) {
                if (FEATURE_AUTOMOTIVE.equals(feature.name)) {
                    boolean required = ((feature.flags & FLAG_REQUIRED) != 0);
                    if (DBG) {
                        Slogf.d(TAG, "Package %s has %s %b",
                                packageName, FEATURE_AUTOMOTIVE, required);
                    }
                    return false;
                }
            }
        }

        // Opt out if has no activities
        if (pkgInfo == null || pkgInfo.activities == null) {
            if (DBG) {
                Slogf.d(TAG, "Package %s has no Activity", packageName);
            }
            return false;
        }

        // Opt out if has at least 1 activity that has
        // {@code META_DATA_DISTRACTION_OPTIMIZED} metadata set to true
        // This case should prevent NDO apps to accidentally launch in display compat host.
        for (ActivityInfo ai : pkgInfo.activities) {
            Bundle activityMetaData = ai.metaData;
            if (activityMetaData != null && activityMetaData
                    .getBoolean(META_DATA_DISTRACTION_OPTIMIZED)) {
                if (DBG) {
                    Slogf.d(TAG, "Package %s has %s", packageName,
                            META_DATA_DISTRACTION_OPTIMIZED);
                }
                return false;
            }
        }

        if (applicationInfo != null) {
            // Opt out if it's a privileged package
            if (applicationInfo.isPrivilegedApp()) {
                if (DBG) {
                    Slogf.d(TAG, "Package %s isPrivileged", packageName);
                }
                return false;
            }

            // Opt out if it's a system package
            if ((applicationInfo.flags & FLAG_SYSTEM) != 0) {
                if (DBG) {
                    Slogf.d(TAG, "Package %s has FLAG_SYSTEM", packageName);
                }
                return false;
            }
        }

        // Opt out if package has platform signature
        if (mPackageManager.checkSignatures(PLATFORM_PACKAGE_NAME, packageName)
                == SIGNATURE_MATCH) {
            if (DBG) {
                Slogf.d(TAG, "Package %s is platform signed", packageName);
            }
            return false;
        }

        // Opt in by default
        return true;
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean updateCurrentConfigFromDeviceLocked() {
        // read the default config from device if user settings is not available.
        try (InputStream in = getConfigFile().openRead()) {
            mConfig.populate(in);
            mRequiresDisplayCompat.clear();
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    DISPLAYCOMPAT_SETTINGS_SECURE_KEY, mConfig.dump(),
                    getCurrentOrTargetUserId());
            Slogf.d(TAG, "updated Settings.Secure for user %d",
                    getCurrentOrTargetUserId());
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from device " + getConfigFile(), e);
        }
        return false;
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean updateConfigForUserFromSettingsLocked(@UserIdInt int userId) {
        // Read the config and populate the in memory cache
        String configString = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), DISPLAYCOMPAT_SETTINGS_SECURE_KEY,
                userId);
        if (configString == null) {
            Slogf.e(TAG, "read config failed from Settings.Secure for user %d", userId);
            return false;
        }
        try (InputStream in =
                new ByteArrayInputStream(configString.getBytes())) {
            mConfig.populate(in);
            mRequiresDisplayCompat.clear();
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from Settings.Secure for user " + userId, e);
        }
        return false;
    }

    private int getCurrentOrTargetUserId() {
        return LocalServices.getService(ActivityManagerInternal.class).getCurrentUser().id;
    }

    @Nullable
    private CompatScale getCompatScaleForPackageAsUser(int displayId, @NonNull String packageName,
            @NonNull UserHandle user, boolean checkForCompatModeChanges) {
        int userId = user.getIdentifier();
        long stamp = mConfigLock.tryOptimisticRead();
        CompatScale compatScale = getCompatScaleForPackageAsUserLocked(displayId, packageName,
                userId);
        if (!mConfigLock.validate(stamp)) {
            stamp = mConfigLock.readLock();
            try {
                compatScale = getCompatScaleForPackageAsUserLocked(displayId, packageName, userId);
            } finally {
                mConfigLock.unlockRead(stamp);
            }
        }
        if (!checkForCompatModeChanges) {
            return compatScale;
        }

        // This shouldn't happen outside of CTS, because CompatModeChanges has higher priority and
        // will already return a scale.
        // See {@link com.android.server.wm.CompatModePackage#getCompatScale} for details.
        float compatMode = isCompatModeChangesEnabled(packageName, user);
        if (compatMode != 1.0f) {
            CompatScale x = new CompatScale((1f / compatMode),
                    (1f / compatMode) * compatScale.mDensityScaleFactor);
            return x;
        }
        return compatScale;
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    @Nullable
    private CompatScale getCompatScaleForPackageAsUserLocked(int displayId,
            @NonNull String packageName, @UserIdInt int userId) {
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(displayId, packageName, userId);
        float scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScale(DEFAULT_SCALE, abs(scaleFactor));
        }
        // Query the scale factor for all packages for a specific user.
        key.mPackageName = ANY_PACKAGE;
        scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScale(DEFAULT_SCALE, abs(scaleFactor));
        }
        // Query the scale factor for a specific package across all users.
        key.mPackageName = packageName;
        key.mUserId = UserHandle.ALL.getIdentifier();
        scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScale(DEFAULT_SCALE, abs(scaleFactor));
        }
        // Query the scale factor for a specific display regardless of
        // user or package name.
        key.mPackageName = ANY_PACKAGE;
        scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScale(DEFAULT_SCALE, abs(scaleFactor));
        }
        return null;
    }

    /**
     * Returns the compat mode scale if framework already set a scaling for this package.
     * see {@link CompatChanges#isChangeEnabled}
     */
    private static float isCompatModeChangesEnabled(@NonNull String packageName,
            @NonNull UserHandle user) {
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_30,
                packageName,
                user)) {
            return 0.3f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_35,
                packageName,
                user)) {
            return 0.35f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_40,
                packageName,
                user)) {
            return 0.4f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_45,
                packageName,
                user)) {
            return 0.45f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_50,
                packageName,
                user)) {
            return 0.5f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_55,
                packageName,
                user)) {
            return 0.55f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_60,
                packageName,
                user)) {
            return 0.6f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_65,
                packageName,
                user)) {
            return 0.65f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_70,
                packageName,
                user)) {
            return 0.7f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_75,
                packageName,
                user)) {
            return 0.75f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_80,
                packageName,
                user)) {
            return 0.8f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_85,
                packageName,
                user)) {
            return 0.85f;
        }
        if (CompatChanges.isChangeEnabled(
                CompatModePackages.DOWNSCALE_90,
                packageName,
                user)) {
            return 0.9f;
        }
        return 1.0f;
    }

    @NonNull
    private static AtomicFile getConfigFile() {
        File configFile = new File(Environment.getProductDirectory(), CONFIG_PATH);
        return new AtomicFile(configFile);
    }
}
