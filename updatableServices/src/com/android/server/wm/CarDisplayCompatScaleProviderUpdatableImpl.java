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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.car.builtin.util.Slogf;
import android.car.feature.Flags;
import android.content.Context;
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
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
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
    static final float NO_SCALE = -1f;
    // {@code CarPackageManager#ERROR_CODE_NO_PACKAGE}
    private static final int ERROR_CODE_NO_PACKAGE = -100;
    static final String DISPLAYCOMPAT_SETTINGS_SECURE_KEY =
            FEATURE_CAR_DISPLAY_COMPATIBILITY + ":settings:secure";

    @NonNull
    private Context mContext;
    private final PackageManager mPackageManager;
    private final CarDisplayCompatScaleProviderInterface mCarCompatScaleProviderInterface;

    private final ReentrantReadWriteLock mRWLock = new ReentrantReadWriteLock();
    @GuardedBy("mRWLock")
    private final ArrayMap<String, Boolean> mRequiresDisplayCompat = new ArrayMap<>();
    @VisibleForTesting
    final ReentrantReadWriteLock mConfigRWLock = new ReentrantReadWriteLock();
    @VisibleForTesting
    @GuardedBy("mConfigRWLock")
    @NonNull
    final CarDisplayCompatConfig mConfig = new CarDisplayCompatConfig();
    @VisibleForTesting
    ContentObserver mSettingsContentObserver;

    private final ReentrantReadWriteLock mPackageUidToLastLaunchedActivityDisplayIdMapRWLock =
            new ReentrantReadWriteLock();
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
    @GuardedBy("mPackageUidToLastLaunchedActivityDisplayIdMapRWLock")
    @NonNull
    private final SparseIntArray mPackageUidToLastLaunchedActivityDisplayIdMap =
            new SparseIntArray();

    public CarDisplayCompatScaleProviderUpdatableImpl(Context context,
            CarDisplayCompatScaleProviderInterface carCompatScaleProviderInterface) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mCarCompatScaleProviderInterface = carCompatScaleProviderInterface;
        if (!Flags.displayCompatibility()) {
            Slogf.i(TAG, "Flag %s is not enabled", Flags.FLAG_DISPLAY_COMPATIBILITY);
            return;
        }
        if (mPackageManager != null
                && !mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            Slogf.i(TAG, "Feature %s is not available", FEATURE_CAR_DISPLAY_COMPATIBILITY);
            return;
        }

        if (!updateConfigForUserFromSettings(UserHandle.CURRENT)) {
            updateCurrentConfigFromDevice();
        }

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
                    updateConfigForUserFromSettings(user);
                }
            }
        };
        Uri keyUri = Settings.Secure.getUriFor(DISPLAYCOMPAT_SETTINGS_SECURE_KEY);
        mContext.getContentResolver().registerContentObserverAsUser(keyUri,
                /*notifyForDescendants*/ true,
                mSettingsContentObserver, UserHandle.ALL);
    }

    @Nullable
    @Override
    public CompatScaleWrapper getCompatScale(@NonNull String packageName, @UserIdInt int userId) {
        if (!Flags.displayCompatibility()) {
            return null;
        }
        if (mPackageManager != null
                && !mPackageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            return null;
        }
        try {
            if (requiresDisplayCompat(packageName, userId)) {
                int displayId = mCarCompatScaleProviderInterface
                        .getMainDisplayAssignedToUser(userId);
                if (displayId == INVALID_DISPLAY) {
                    displayId = DEFAULT_DISPLAY;
                }
                UserHandle user = UserHandle.of(userId);
                mPackageUidToLastLaunchedActivityDisplayIdMapRWLock.readLock().lock();
                try {
                    ApplicationInfoFlags appFlags = ApplicationInfoFlags.of(/* flags */ 0);
                    ApplicationInfo applicationInfo = mPackageManager
                            .getApplicationInfoAsUser(packageName, appFlags, user);
                    // TODO(b/331089039): {@link Activity} should start on the display of the
                    // calling package if {@code ActivityOptions#launchDisplayId} is set to
                    // {@link INVALID_DISPLAY}. Therefore, the display will be set to
                    // {@link DEFAULT_DISPLAY} if the calling package's display isn't available in
                    // the cache.
                    displayId = mPackageUidToLastLaunchedActivityDisplayIdMap
                            .get(applicationInfo.uid, displayId);
                } catch (PackageManager.NameNotFoundException e) {
                    // This shouldn't be the case if the user requesting the package is the same as
                    // the user launching the app.
                    Slogf.e(TAG, "Package " + packageName + " not found", e);
                } finally {
                    mPackageUidToLastLaunchedActivityDisplayIdMapRWLock.readLock().unlock();
                }
                CompatScaleWrapper compatScale = getCompatScaleForPackageAsUser(displayId,
                        packageName, user);
                float compatModeScalingFactor = mCarCompatScaleProviderInterface
                        .getCompatModeScalingFactor(packageName, user);
                if (compatModeScalingFactor == DEFAULT_SCALE) {
                    return compatScale;
                }

                // This shouldn't happen outside of CTS, because CompatModeChanges has higher
                // priority and will already return a scale.
                // See {@code com.android.server.wm.CompatModePackage#getCompatScale} for details.
                return new CompatScaleWrapper(DEFAULT_SCALE,
                        (1f / compatModeScalingFactor) * compatScale.getDensityScaleFactor());
            }
        } catch (ServiceSpecificException e) {
            return null;
        }
        return null;
    }

    @Override
    public ActivityInterceptResultWrapper onInterceptActivityLaunch(
            ActivityInterceptorInfoWrapper info) {
        if (info.getIntent() != null && info.getIntent().getComponent() != null
                && info.getCheckedOptions() != null) {
            int displayId = info.getCheckedOptions().getOptions().getLaunchDisplayId();
            if (displayId == INVALID_DISPLAY) {
                displayId = DEFAULT_DISPLAY;
                if (info.getCallingUid() != -1) {
                    mPackageUidToLastLaunchedActivityDisplayIdMapRWLock.readLock().lock();
                    try {
                        displayId = mPackageUidToLastLaunchedActivityDisplayIdMap
                                .get(info.getCallingUid(), displayId);
                    } finally {
                        mPackageUidToLastLaunchedActivityDisplayIdMapRWLock.readLock().unlock();
                    }
                }
            }
            mPackageUidToLastLaunchedActivityDisplayIdMapRWLock.writeLock().lock();
            try {
                mPackageUidToLastLaunchedActivityDisplayIdMap
                        .put(info.getActivityInfo().applicationInfo.uid, displayId);
            } finally {
                mPackageUidToLastLaunchedActivityDisplayIdMapRWLock.writeLock().unlock();
            }
        }
        return null;
    }

    @Override
    public boolean requiresDisplayCompat(@NonNull String packageName, @UserIdInt int userId) {
        mRWLock.readLock().lock();
        try {
            // TODO(b/300642384): need to listen to add/remove of packages from PackageManager so
            // the list doesn't have stale data.
            Boolean res = mRequiresDisplayCompat.get(packageName);
            if (res != null) {
                if (DBG) {
                    Slogf.d(TAG, "Package %s is cached %b", packageName, res.booleanValue());
                }
                return res.booleanValue();
            }
        } finally {
            mRWLock.readLock().unlock();
        }

        mRWLock.writeLock().lock();
        try {
            boolean result = requiresDisplayCompatNotCached(packageName, userId);
            mRequiresDisplayCompat.put(packageName, result);
            return result;
        } catch (PackageManager.NameNotFoundException e) {
            // This shouldn't be the case if the user requesting the package is the same as
            // the user launching the app.
            Slogf.e(TAG, "Package " + packageName + " not found", e);
            throw new ServiceSpecificException(
                    ERROR_CODE_NO_PACKAGE,
                    e.getMessage());
        } finally {
            mRWLock.writeLock().unlock();
        }
    }

    @GuardedBy("mRWLock")
    private boolean requiresDisplayCompatNotCached(@NonNull String packageName,
            @UserIdInt int userId) throws PackageManager.NameNotFoundException {

        UserHandle userHandle = UserHandle.of(userId);
        ApplicationInfoFlags appFlags = ApplicationInfoFlags.of(GET_META_DATA);
        ApplicationInfo applicationInfo = mPackageManager
                .getApplicationInfoAsUser(packageName, appFlags, userHandle);

        // application has {@code FEATURE_CAR_DISPLAY_COMPATIBILITY} metadata
        if (applicationInfo != null &&  applicationInfo.metaData != null
                && applicationInfo.metaData.containsKey(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            if (DBG) {
                Slogf.d(TAG, "Package %s has %s metadata", packageName,
                        FEATURE_CAR_DISPLAY_COMPATIBILITY);
            }
            return applicationInfo.metaData.getBoolean(FEATURE_CAR_DISPLAY_COMPATIBILITY);
        }

        PackageInfoFlags pkgFlags = PackageInfoFlags
                .of(GET_CONFIGURATIONS | GET_ACTIVITIES);
        PackageInfo pkgInfo = mCarCompatScaleProviderInterface
                .getPackageInfoAsUser(packageName, pkgFlags, userId);

        // Opt out if has {@code FEATURE_AUTOMOTIVE} or
        // {@code FEATURE_CAR_DISPLAY_COMPATIBILITY}
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
                if (FEATURE_CAR_DISPLAY_COMPATIBILITY.equals(feature.name)) {
                    if (DBG) {
                        boolean required = ((feature.flags & FLAG_REQUIRED) != 0);
                        Slogf.d(TAG, "Package %s has %s %b",
                                packageName, FEATURE_CAR_DISPLAY_COMPATIBILITY, required);
                    }
                    return true;
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

    /**
     * Dump {@code CarDisplayCompatScaleProviderUpdatableImpl#mRequiresDisplayCompat}
     * and {@code CarDisplayCompatScaleProviderUpdatableImpl#mConfig}
     */
    public void dump(IndentingPrintWriter writer) {
        writer.println(TAG);
        writer.increaseIndent();
        writer.println("DisplayCompat Config:");
        writer.increaseIndent();
        mConfigRWLock.readLock().lock();
        try {
            writer.println(mConfig.dump());
        } finally {
            mConfigRWLock.readLock().unlock();
        }
        writer.decreaseIndent();
        writer.println("List of DisplayCompat packages:");
        writer.increaseIndent();
        mRWLock.readLock().lock();
        try {
            if (mRequiresDisplayCompat.size() == 0) {
                writer.println("No package is enabled.");
            } else {
                for (int i = 0; i < mRequiresDisplayCompat.size(); i++) {
                    if (mRequiresDisplayCompat.valueAt(i)) {
                        writer.println("Package name: " + mRequiresDisplayCompat.keyAt(i));
                    }
                }
            }
        } finally {
            mRWLock.readLock().unlock();
        }
        writer.decreaseIndent();
        writer.decreaseIndent();
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(UserHandle newUser) {
        updateConfigForUserFromSettings(newUser);
    }

    private boolean updateCurrentConfigFromDevice() {
        mConfigRWLock.writeLock().lock();
        // read the default config from device if user settings is not available.
        try (InputStream in = getConfigFile().openRead()) {
            mConfig.populate(in);
            mCarCompatScaleProviderInterface.putStringForUser(mContext.getContentResolver(),
                    DISPLAYCOMPAT_SETTINGS_SECURE_KEY, mConfig.dump(),
                    UserHandle.CURRENT.getIdentifier());
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from device " + getConfigFile(), e);
        } finally {
            mConfigRWLock.writeLock().unlock();
        }
        return false;
    }

    private boolean updateConfigForUserFromSettings(UserHandle user) {
        // Read the config and populate the in memory cache
        String configString = mCarCompatScaleProviderInterface.getStringForUser(
                mContext.getContentResolver(), DISPLAYCOMPAT_SETTINGS_SECURE_KEY,
                user.getIdentifier());
        if (configString == null) {
            return false;
        }
        mConfigRWLock.writeLock().lock();
        try (InputStream in =
                new ByteArrayInputStream(configString.getBytes())) {
            mConfig.populate(in);
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from Settings.Secure", e);
        } finally {
            mConfigRWLock.writeLock().unlock();
        }
        return false;
    }

    @VisibleForTesting
    int getCurrentOrTargetUserId() {
        Pair<Integer, Integer> currentAndTargetUserIds =
                mCarCompatScaleProviderInterface.getCurrentAndTargetUserIds();
        int currentUserId = currentAndTargetUserIds.first;
        int targetUserId = currentAndTargetUserIds.second;
        int currentOrTargetUserId = targetUserId != USER_NULL
                ? targetUserId : currentUserId;
        return currentOrTargetUserId;
    }

    @Nullable
    private CompatScaleWrapper getCompatScaleForPackageAsUser(int displayId,
            @NonNull String packageName, @NonNull UserHandle user) {
        mConfigRWLock.readLock().lock();
        try {
            CarDisplayCompatConfig.Key key =
                    new CarDisplayCompatConfig.Key(displayId, packageName, user);
            float scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScaleWrapper(DEFAULT_SCALE, scaleFactor);
            }
            // Query the scale factor for all packages for a specific user.
            key.packageName = ANY_PACKAGE;
            scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScaleWrapper(DEFAULT_SCALE, scaleFactor);
            }
            // Query the scale factor for a specific package across all users.
            key.packageName = packageName;
            key.userId = UserHandle.ALL.getIdentifier();
            scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScaleWrapper(DEFAULT_SCALE, scaleFactor);
            }
            // Query the scale factor for a specific display regardless of
            // user or package name.
            key.packageName = ANY_PACKAGE;
            key.userId = UserHandle.ALL.getIdentifier();
            scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScaleWrapper(DEFAULT_SCALE, scaleFactor);
            }
            return null;
        } finally {
            mConfigRWLock.readLock().unlock();
        }
    }

    @NonNull
    private static AtomicFile getConfigFile() {
        File configFile = new File(Environment.getProductDirectory(), CONFIG_PATH);
        return new AtomicFile(configFile);
    }
}
