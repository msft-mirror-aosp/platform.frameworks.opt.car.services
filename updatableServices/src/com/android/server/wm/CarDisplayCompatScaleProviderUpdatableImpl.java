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

import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.FeatureInfo.FLAG_REQUIRED;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.content.pm.PackageManager.GET_ACTIVITIES;
import static android.content.pm.PackageManager.GET_CONFIGURATIONS;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.SIGNATURE_MATCH;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.wm.CarDisplayCompatConfig.ANY_PACKAGE;
import static com.android.server.wm.CarDisplayCompatConfig.DEFAULT_SCALE;

import static java.lang.Math.abs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.car.builtin.util.Slogf;
import android.car.feature.Flags;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.CompatScaleWrapper;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;

import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.StampedLock;

/**
 * Implementation of {@link CarDisplayCompatScaleProviderUpdatable}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class CarDisplayCompatScaleProviderUpdatableImpl implements
        CarDisplayCompatScaleProviderUpdatable, CarActivityInterceptorUpdatable {
    private static final String TAG =
            CarDisplayCompatScaleProviderUpdatableImpl.class.getSimpleName();
    // {@code PackageManager#FEATURE_CAR_DISPLAY_COMPATIBILITY}
    static final String FEATURE_CAR_DISPLAY_COMPATIBILITY =
            "android.software.car.display_compatibility";
    @VisibleForTesting
    static final String META_DATA_DISTRACTION_OPTIMIZED = "distractionOptimized";
    @VisibleForTesting
    static final String PLATFORM_PACKAGE_NAME = "android";
    private static final String CONFIG_PATH = "etc/display_compat_config.xml";
    // {@code android.os.UserHandle.USER_NULL}
    @VisibleForTesting
    static final int USER_NULL = -10000;
    @VisibleForTesting
    static final float NO_SCALE = 0f;
    @VisibleForTesting
    static final float OPT_OUT = -1 * DEFAULT_SCALE;
    // {@code CarPackageManager#ERROR_CODE_NO_PACKAGE}
    private static final int ERROR_CODE_NO_PACKAGE = -100;
    @VisibleForTesting
    static final String DISPLAYCOMPAT_SETTINGS_SECURE_KEY =
            FEATURE_CAR_DISPLAY_COMPATIBILITY + ":settings:secure";
    @VisibleForTesting
            static final String DATA_SCHEME_PACKAGE = "package";

    @NonNull
    private Context mContext;
    @NonNull
    private final PackageManager mPackageManager;
    @NonNull
    private final CarDisplayCompatScaleProviderInterface mCarCompatScaleProviderInterface;

    // {@link StampedLock} is used for 2 reasons
    // 1) the # of reads is way higher than # of writes.
    // 2) {@code ReentrantReadWriteLock} is not very efficient.
    private final StampedLock mConfigLock = new StampedLock();
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    @NonNull
    private CarDisplayCompatConfig mConfig;
    // Maps package names to a boolean that indicates if a package requires running in display
    // compatibility mode or not.
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private final ArrayMap<String, Boolean> mRequiresDisplayCompat = new ArrayMap<>();

    // TODO(b/345248202): can this be private
    @VisibleForTesting
    @NonNull
    ContentObserver mSettingsContentObserver;

    // TODO(b/345248202): can this be private
    @VisibleForTesting
    BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData().getSchemeSpecificPart();
            if (isDebugLoggable()) {
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
            } catch (PackageManager.NameNotFoundException e) {
                // This shouldn't be the case if the user requesting the package is the same as
                // the user launching the app.
                Slogf.w(TAG, "Package %s for user %d not found", packageName,
                        getCurrentOrTargetUserId());
            } finally {
                mConfigLock.unlockWrite(stamp);
            }
        }
    };

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
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    @NonNull
    private final SparseIntArray mPackageUidToLastLaunchedActivityDisplayIdMap =
            new SparseIntArray();

    public CarDisplayCompatScaleProviderUpdatableImpl(Context context,
            CarDisplayCompatScaleProviderInterface carCompatScaleProviderInterface) {
        this(context, carCompatScaleProviderInterface, new CarDisplayCompatConfig());
    }

    @VisibleForTesting
    CarDisplayCompatScaleProviderUpdatableImpl(Context context,
            CarDisplayCompatScaleProviderInterface carCompatScaleProviderInterface,
            @NonNull CarDisplayCompatConfig config) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mCarCompatScaleProviderInterface = carCompatScaleProviderInterface;
        mConfig = config;

        if (!Flags.displayCompatibility()) {
            Slogf.i(TAG, "Flag %s is not enabled", Flags.FLAG_DISPLAY_COMPATIBILITY);
            return;
        }
        if (mPackageManager != null
                && !mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            Slogf.i(TAG, "Feature %s is not available", FEATURE_CAR_DISPLAY_COMPATIBILITY);
            return;
        }

        initConfig(UserHandle.of(getCurrentOrTargetUserId()));

        // TODO(b/329898692): can we fix the tests so we don't need this?
        if (mContext.getMainLooper() == null) {
            // Looper is null during tests.
            return;
        }

        mSettingsContentObserver = new ContentObserver(new Handler(mContext.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris,
                    int flags, UserHandle user) {
                super.onChange(selfChange, uris, flags, user);
                if (selfChange) {
                    return;
                }
                if (getCurrentOrTargetUserId() == user.getIdentifier()) {
                    long stamp = mConfigLock.writeLock();
                    try {
                        initLocalConfigFromSettingsLocked(user);
                    } finally {
                        mConfigLock.unlockWrite(stamp);
                    }
                }
            }
        };
        Uri keyUri = Settings.Secure.getUriFor(DISPLAYCOMPAT_SETTINGS_SECURE_KEY);
        mContext.getContentResolver().registerContentObserverAsUser(keyUri,
                /*notifyForDescendants*/ true,
                mSettingsContentObserver, UserHandle.ALL);

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
    public CompatScaleWrapper getCompatScale(@NonNull String packageName, @UserIdInt int userId) {
        if (!Flags.displayCompatibility() || !Flags.displayCompatibilityDensity()) {
            return null;
        }
        if (mPackageManager != null
                && !mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            return null;
        }

        long stamp = mConfigLock.tryOptimisticRead();
        int displayId = getPackageDisplayIdAsUserLocked(packageName, userId);
        CompatScaleWrapper compatScale = getCompatScaleForPackageAsUserLocked(displayId,
                packageName, userId);
        if (!mConfigLock.validate(stamp)) {
            stamp = mConfigLock.readLock();
            try {
                displayId = getPackageDisplayIdAsUserLocked(packageName, userId);
                compatScale = getCompatScaleForPackageAsUserLocked(displayId, packageName, userId);
            } finally {
                mConfigLock.unlockRead(stamp);
            }
        }

        float compatModeScalingFactor = mCarCompatScaleProviderInterface
                .getCompatModeScalingFactor(packageName, UserHandle.of(userId));
        if (compatModeScalingFactor == DEFAULT_SCALE) {
            Slogf.i(TAG, "Returning CompatScale %s for package %s", compatScale, packageName);
            return compatScale;
        }
        // This shouldn't happen outside of CTS, because CompatModeChanges has higher
        // priority and will already return a scale.
        // See {@code com.android.server.wm.CompatModePackage#getCompatScale} for details.
        if (compatScale != null) {
            CompatScaleWrapper res = new CompatScaleWrapper(compatModeScalingFactor,
                    compatModeScalingFactor * compatScale.getDensityScaleFactor());
            Slogf.i(TAG, "Returning CompatScale %s for package %s", res, packageName);
            return res;
        }
        Slogf.i(TAG, "Returning CompatScale %s for package %s", compatScale, packageName);
        return compatScale;
    }

    @Nullable
    @Override
    public ActivityInterceptResultWrapper onInterceptActivityLaunch(
            ActivityInterceptorInfoWrapper info) {
        if (info.getIntent() != null && info.getIntent().getComponent() != null
                && info.getCheckedOptions() != null) {
            int displayId = info.getCheckedOptions().getOptions().getLaunchDisplayId();
            if (displayId == INVALID_DISPLAY) {
                displayId = DEFAULT_DISPLAY;
                if (info.getCallingUid() != -1) {
                    long stamp = mConfigLock.tryOptimisticRead();
                    displayId = mPackageUidToLastLaunchedActivityDisplayIdMap
                                .get(info.getCallingUid(), displayId);
                    if (!mConfigLock.validate(stamp)) {
                        mConfigLock.readLock();
                        try {
                            displayId = mPackageUidToLastLaunchedActivityDisplayIdMap
                                    .get(info.getCallingUid(), displayId);
                        } finally {
                            mConfigLock.unlockRead(stamp);
                        }
                    }
                }
            }
            long stamp = mConfigLock.writeLock();
            try {
                mPackageUidToLastLaunchedActivityDisplayIdMap
                        .put(info.getActivityInfo().applicationInfo.uid, displayId);
            } finally {
                mConfigLock.unlockWrite(stamp);
            }
        }
        return null;
    }

    @Override
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
            if (isDebugLoggable()) {
                Slogf.d(TAG, "Package %s is cached %b", packageName, res.booleanValue());
            }
            return res.booleanValue();
        } else {
            stamp = mConfigLock.writeLock();
            try {
                return updateStateOfPackageForUserLocked(packageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                // This shouldn't be the case if the user requesting the package is the same as
                // the user launching the app.
                throw new ServiceSpecificException(ERROR_CODE_NO_PACKAGE, e.getMessage());
            } finally {
                mConfigLock.unlockWrite(stamp);
            }
        }
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(UserHandle newUser) {
        initConfig(newUser);
    }

    /**
     * Dump {@code CarDisplayCompatScaleProviderUpdatableImpl#mConfig}
     */
    public void dump(IndentingPrintWriter writer) {
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

    /** Initialise cache. */
    private void initConfig(UserHandle user) {
        long stamp = mConfigLock.writeLock();
        try {
            if (!initLocalConfigFromSettingsLocked(user)) {
                initLocalConfigAndSettingsFromConfigFileLocked();
                initLocalConfigAndSettingsForAllInstalledPackagesLocked(user.getIdentifier());
            }
        } finally {
            mConfigLock.unlockWrite(stamp);
        }
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private int getPackageDisplayIdAsUserLocked(@NonNull String packageName,
            @UserIdInt int userId) {
        int displayId = mCarCompatScaleProviderInterface
                .getMainDisplayAssignedToUser(userId);
        if (displayId == INVALID_DISPLAY) {
            displayId = DEFAULT_DISPLAY;
        }
        UserHandle user = UserHandle.of(userId);
        try {
            ApplicationInfoFlags appFlags = ApplicationInfoFlags.of(/* flags */ 0);
            ApplicationInfo applicationInfo = mPackageManager
                    .getApplicationInfoAsUser(packageName, appFlags, user);
            if (applicationInfo != null) {
                // TODO(b/331089039): {@link Activity} should start on the display of the
                // calling package if {@code ActivityOptions#launchDisplayId} is set to
                // {@link INVALID_DISPLAY}. Therefore, the display will be set to
                // {@link DEFAULT_DISPLAY} if the calling package's display isn't available in
                // the cache.
                displayId = mPackageUidToLastLaunchedActivityDisplayIdMap
                        .get(applicationInfo.uid, displayId);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // This shouldn't be the case if the user requesting the package is the same as
            // the user launching the app.
            Slogf.w(TAG, "Package %s for user %d not found", packageName, userId);
        }
        return displayId;
    }

    /**
     * Initializes local config and settings for all installed packages for the user.
     */
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private void initLocalConfigAndSettingsForAllInstalledPackagesLocked(@UserIdInt int userId) {
        // TODO(b/329898692): can we fix the tests so we don't need this?
        if (mPackageManager == null) {
            // mPackageManager is null during tests.
            return;
        }
        ApplicationInfoFlags appFlags = ApplicationInfoFlags.of(GET_META_DATA);
        List<ApplicationInfo> allPackagesForUser =
                mCarCompatScaleProviderInterface.getInstalledApplicationsAsUser(appFlags, userId);
        for (int i = 0; i < allPackagesForUser.size(); i++) {
            ApplicationInfo appInfo = allPackagesForUser.get(i);
            try {
                updateStateOfPackageForUserLocked(appInfo.packageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                Slogf.w(TAG, "Package %s for user %d not found", appInfo.packageName, userId);
            }
        }
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean updateStateOfPackageForUserLocked(@NonNull String packageName,
            @UserIdInt int userId) throws PackageManager.NameNotFoundException {
        int displayId = getPackageDisplayIdAsUserLocked(packageName, userId);
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
        mCarCompatScaleProviderInterface.putStringForUser(mContext.getContentResolver(),
                DISPLAYCOMPAT_SETTINGS_SECURE_KEY, mConfig.dump(),
                getCurrentOrTargetUserId());

        return result;
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean requiresDisplayCompatNotCachedLocked(@NonNull String packageName,
            @UserIdInt int userId) throws PackageManager.NameNotFoundException {

        UserHandle userHandle = UserHandle.of(userId);
        ApplicationInfoFlags appFlags = ApplicationInfoFlags.of(GET_META_DATA);
        ApplicationInfo applicationInfo = mPackageManager
                .getApplicationInfoAsUser(packageName, appFlags, userHandle);

        // application has {@code FEATURE_CAR_DISPLAY_COMPATIBILITY} metadata
        if (applicationInfo != null &&  applicationInfo.metaData != null
                && applicationInfo.metaData.containsKey(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            if (isDebugLoggable()) {
                Slogf.d(TAG, "Package %s has %s metadata", packageName,
                        FEATURE_CAR_DISPLAY_COMPATIBILITY);
            }
            return applicationInfo.metaData.getBoolean(FEATURE_CAR_DISPLAY_COMPATIBILITY);
        }

        PackageInfoFlags pkgFlags = PackageInfoFlags
                .of(GET_CONFIGURATIONS | GET_ACTIVITIES);
        PackageInfo pkgInfo = mCarCompatScaleProviderInterface
                .getPackageInfoAsUser(packageName, pkgFlags, userId);

        // Opt out if has {@code FEATURE_AUTOMOTIVE}
        if (pkgInfo != null && pkgInfo.reqFeatures != null) {
            FeatureInfo[] features = pkgInfo.reqFeatures;
            for (FeatureInfo feature: features) {
                if (FEATURE_AUTOMOTIVE.equals(feature.name)) {
                    boolean required = ((feature.flags & FLAG_REQUIRED) != 0);
                    if (isDebugLoggable()) {
                        Slogf.d(TAG, "Package %s has %s %b",
                                packageName, FEATURE_AUTOMOTIVE, required);
                    }
                    return false;
                }
            }
        }

        // Opt out if has no activities
        if (pkgInfo == null || pkgInfo.activities == null) {
            if (isDebugLoggable()) {
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
                if (isDebugLoggable()) {
                    Slogf.d(TAG, "Package %s has %s", packageName,
                            META_DATA_DISTRACTION_OPTIMIZED);
                }
                return false;
            }
        }

        if (applicationInfo != null) {
            // Opt out if it's a privileged package
            if (applicationInfo.isPrivilegedApp()) {
                if (isDebugLoggable()) {
                    Slogf.d(TAG, "Package %s isPrivileged", packageName);
                }
                return false;
            }

            // Opt out if it's a system package
            if ((applicationInfo.flags & FLAG_SYSTEM) != 0) {
                if (isDebugLoggable()) {
                    Slogf.d(TAG, "Package %s has FLAG_SYSTEM", packageName);
                }
                return false;
            }
        }

        // Opt out if package has platform signature
        if (mPackageManager.checkSignatures(PLATFORM_PACKAGE_NAME, packageName)
                == SIGNATURE_MATCH) {
            if (isDebugLoggable()) {
                Slogf.d(TAG, "Package %s is platform signed", packageName);
            }
            return false;
        }

        // Opt in by default
        return true;
    }

    /**
     * @return {@code true} if local config and settings is successfully updated, false otherwise.
     */
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean initLocalConfigAndSettingsFromConfigFileLocked() {
        // read the default config from device if user settings is not available.
        try (InputStream in = openReadConfigFile()) {
            mConfig.populate(in);
            mRequiresDisplayCompat.clear();
            mCarCompatScaleProviderInterface.putStringForUser(mContext.getContentResolver(),
                    DISPLAYCOMPAT_SETTINGS_SECURE_KEY, mConfig.dump(),
                    getCurrentOrTargetUserId());
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from device " + getConfigFile(), e);
        }
        return false;
    }

    /**
     * @return {@code true} if settings exists and is successfully populated into the local config,
     * false otherwise.
     */
    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    private boolean initLocalConfigFromSettingsLocked(@NonNull UserHandle user) {
        // Read the config and populate the in memory cache
        String configString = mCarCompatScaleProviderInterface.getStringForUser(
                mContext.getContentResolver(), DISPLAYCOMPAT_SETTINGS_SECURE_KEY,
                user.getIdentifier());
        if (configString == null) {
            return false;
        }
        try (InputStream in =
                new ByteArrayInputStream(configString.getBytes())) {
            mConfig.populate(in);
            mRequiresDisplayCompat.clear();
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from Settings.Secure", e);
        }
        return false;
    }

    @VisibleForTesting
    int getCurrentOrTargetUserId() {
        Pair<Integer, Integer> currentAndTargetUserIds =
                mCarCompatScaleProviderInterface.getCurrentAndTargetUserIds();

        // TODO(b/329898692): can we fix the tests so we don't need this?
        if (currentAndTargetUserIds == null) {
            // This is only null during tests.
            return USER_NULL;
        }
        int currentUserId = currentAndTargetUserIds.first;
        int targetUserId = currentAndTargetUserIds.second;
        int currentOrTargetUserId = targetUserId != USER_NULL
                ? targetUserId : currentUserId;
        return currentOrTargetUserId;
    }

    // @GuardedBy("mConfigLock")
    // TODO(b/343755550): add back when error-prone supports {@link StampedLock}
    @Nullable
    private CompatScaleWrapper getCompatScaleForPackageAsUserLocked(int displayId,
            @NonNull String packageName, @UserIdInt int userId) {
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(displayId, packageName, userId);
        float scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScaleWrapper(DEFAULT_SCALE, abs(scaleFactor));
        }
        // Query the scale factor for all packages for a specific user.
        key.mPackageName = ANY_PACKAGE;
        scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScaleWrapper(DEFAULT_SCALE, abs(scaleFactor));
        }
        // Query the scale factor for a specific package across all users.
        key.mPackageName = packageName;
        key.mUserId = UserHandle.ALL.getIdentifier();
        scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScaleWrapper(DEFAULT_SCALE, abs(scaleFactor));
        }
        // Query the scale factor for a specific display regardless of
        // user or package name.
        key.mPackageName = ANY_PACKAGE;
        scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
        if (scaleFactor != NO_SCALE) {
            return new CompatScaleWrapper(DEFAULT_SCALE, abs(scaleFactor));
        }
        return null;
    }

    @NonNull
    private static AtomicFile getConfigFile() {
        File configFile = new File(Environment.getProductDirectory(), CONFIG_PATH);
        return new AtomicFile(configFile);
    }

    /** This method is needed to be overwritten to provide a test InputStream for the config */
    @VisibleForTesting
    @NonNull
    InputStream openReadConfigFile() throws FileNotFoundException {
        return getConfigFile().openRead();
    }

    private static boolean isDebugLoggable() {
        return Slogf.isLoggable(TAG, Log.DEBUG);
    }
}
