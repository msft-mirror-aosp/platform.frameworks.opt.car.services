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

import static android.content.pm.PackageManager.GET_CONFIGURATIONS;
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
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.CompatScaleWrapper;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Pair;

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
        CarDisplayCompatScaleProviderUpdatable {
    private static final String TAG = "CarDisplayCompatScaleProvider";
    private static final String FEATURE_DISPLAYCOMPAT = "android.car.displaycompatibility";
    static final String DISPLAYCOMPAT_SETTINGS_SECURE_KEY =
            "android.car.displaycompatibility:settings:secure";
    private static final String CONFIG_PATH = "etc/display_compat_config.xml";
    // {@code android.os.UserHandle.USER_NULL}
    @VisibleForTesting
    static final int USER_NULL = -10000;
    @VisibleForTesting
    static final float NO_SCALE = -1f;
    // {@code CarPackageManager#ERROR_CODE_NO_PACKAGE}
    private static final int ERROR_CODE_NO_PACKAGE = -100;

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

    public CarDisplayCompatScaleProviderUpdatableImpl(Context context,
            CarDisplayCompatScaleProviderInterface carCompatScaleProviderInterface) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mCarCompatScaleProviderInterface = carCompatScaleProviderInterface;
        if (!Flags.displayCompatibility()) {
            Slogf.i(TAG, Flags.FLAG_DISPLAY_COMPATIBILITY + " is not enabled");
            return;
        }
        if (mPackageManager != null
                && !mPackageManager.hasSystemFeature(FEATURE_DISPLAYCOMPAT)) {
            Slogf.i(TAG, FEATURE_DISPLAYCOMPAT + " is not available");
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
                && !mPackageManager.hasSystemFeature(FEATURE_DISPLAYCOMPAT)) {
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
    public boolean requiresDisplayCompat(@NonNull String packageName, @UserIdInt int userId) {
        boolean result = false;
        mRWLock.readLock().lock();
        try {
            // TODO(b/300642384): need to listen to add/remove of packages from PackageManager so
            // the list doesn't have stale data.
            Boolean res = mRequiresDisplayCompat.get(packageName);
            if (res != null) {
                return res.booleanValue();
            }
        } finally {
            mRWLock.readLock().unlock();
        }

        try {
            PackageInfoFlags flags = PackageInfoFlags.of(GET_CONFIGURATIONS);
            FeatureInfo[] features = mPackageManager.getPackageInfo(packageName, flags)
                    .reqFeatures;
            if (features != null) {
                for (FeatureInfo feature: features) {
                    // TODO: get the string from PackageManager
                    if (FEATURE_DISPLAYCOMPAT.equals(feature.name)) {
                        result = true;
                        break;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(TAG, "Package " + packageName + " not found", e);
            throw new ServiceSpecificException(
                    ERROR_CODE_NO_PACKAGE,
                    e.getMessage());
        }
        mRWLock.writeLock().lock();
        try {
            mRequiresDisplayCompat.put(packageName, result);
        } finally {
            mRWLock.writeLock().unlock();
        }
        return result;
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
