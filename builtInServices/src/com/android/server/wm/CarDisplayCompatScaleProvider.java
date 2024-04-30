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

import static com.android.server.wm.CarDisplayCompatConfig.ANY_PACKAGE;
import static com.android.server.wm.CarDisplayCompatConfig.DEFAULT_SCALE;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_PRODUCT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo.CompatScale;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AtomicFile;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.car.CarActivityInterceptor;
import com.android.server.LocalServices;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Automotive implementation of {@link CompatScaleProvider}
 * This class is responsible for providing different scaling factor for some automotive specific
 * packages.
 *
 * @hide
 */
public final class CarDisplayCompatScaleProvider implements CompatScaleProvider {
    private static final String TAG = CarDisplayCompatScaleProvider.class.getSimpleName();
    private static final String DISPLAYCOMPAT_SETTINGS_SECURE_KEY =
            FEATURE_CAR_DISPLAY_COMPATIBILITY + ":settings:secure";
    private static final String CONFIG_PATH = "etc/display_compat_config.xml";
    private static final float NO_SCALE = -1f;
    @NonNull
    private Context mContext;

    private final ReentrantReadWriteLock mRWLock = new ReentrantReadWriteLock();
    @GuardedBy("mRWLock")
    @NonNull
    private final CarDisplayCompatConfig mConfig = new CarDisplayCompatConfig();

    @NonNull
    private CarActivityInterceptor mActivityInterceptor;

    /**
     * Registers {@link CarDisplayCompatScaleProvider} with {@link ActivityTaskManagerService}
     */
    public void init(@NonNull Context context, CarActivityInterceptor activityInterceptor) {
        mContext = context;
        mActivityInterceptor = activityInterceptor;
        PackageManager packageManager = context.getPackageManager();
        if (!packageManager.hasSystemFeature(FEATURE_CAR_DISPLAY_COMPATIBILITY)) {
            Slogf.i(TAG, "Feature %s not available ", FEATURE_CAR_DISPLAY_COMPATIBILITY);
            return;
        }

        if (!updateConfigForUserFromSettings(UserHandle.USER_CURRENT)) {
            updateCurrentConfigFromDevice();
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
                        updateConfigForUserFromSettings(userId);
                    }
                }
            }, UserHandle.USER_ALL);

        ActivityTaskManagerService atms =
                (ActivityTaskManagerService) ActivityTaskManager.getService();
        atms.registerCompatScaleProvider(COMPAT_SCALE_MODE_PRODUCT, this);
        Slogf.i(TAG, "registered Car service as a CompatScaleProvider.");
    }

    @Nullable
    @Override
    public CompatScale getCompatScale(@NonNull String packageName, int uid) {
        int displayId = mActivityInterceptor.getPackageDisplay(uid);
        return getCompatScaleForPackageAsUser(displayId, packageName,
                UserHandle.getUserHandleForUid(uid), true);
    }

    /** Notifies user switching. */
    public void handleCurrentUserSwitching(int newUserId) {
        updateConfigForUserFromSettings(newUserId);
    }

    /**
     * @return true if package requires launching in automotive compatibility mode
     */
    public boolean requiresDisplayCompat(@NonNull String packageName, @UserIdInt int userId) {
        // TODO: add implementation
        return false;
    }

    private boolean updateCurrentConfigFromDevice() {
        mRWLock.writeLock().lock();
        // read the default config from device if user settings is not available.
        try (InputStream in = getConfigFile().openRead()) {
            mConfig.populate(in);
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    DISPLAYCOMPAT_SETTINGS_SECURE_KEY, mConfig.dump(),
                    UserHandle.USER_CURRENT);
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from device " + getConfigFile(), e);
        } finally {
            mRWLock.writeLock().unlock();
        }
        return false;
    }

    private boolean updateConfigForUserFromSettings(int userId) {
        // Read the config and populate the in memory cache
        String configString = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), DISPLAYCOMPAT_SETTINGS_SECURE_KEY,
                userId);
        if (configString == null) {
            Slogf.e(TAG, "read config failed from Settings.Secure for user %d", userId);
            return false;
        }
        mRWLock.writeLock().lock();
        try (InputStream in =
                new ByteArrayInputStream(configString.getBytes())) {
            mConfig.populate(in);
            return true;
        } catch (XmlPullParserException | IOException | SecurityException e) {
            Slogf.e(TAG, "read config failed from Settings.Secure for user " + userId, e);
        } finally {
            mRWLock.writeLock().unlock();
        }
        return false;
    }

    private int getCurrentOrTargetUserId() {
        return LocalServices.getService(ActivityManagerInternal.class).getCurrentUser().id;
    }

    private CompatScale getCompatScaleForPackageAsUser(int displayId, @NonNull String packageName,
            @NonNull UserHandle user, boolean checkForCompatModeChanges) {
        CompatScale compatScale = getCompatScaleForPackageAsUser(displayId, packageName, user);
        if (!checkForCompatModeChanges) {
            return compatScale;
        }

        // This shouldn't happen outside of CTS, because CompatModeChanges has higher priority and
        // will already return a scale.
        // See {@link com.android.server.wm.CompatModePackage#getCompatScale} for details.
        float compatMode = isCompatModeChangesEnabled(packageName, user);
        if (compatMode != 1.0f) {
            return new CompatScale(NO_SCALE, (1f / compatMode) * compatScale.mDensityScaleFactor);
        }
        return compatScale;
    }

    @Nullable
    private CompatScale getCompatScaleForPackageAsUser(int displayId, @NonNull String packageName,
            @NonNull UserHandle user) {
        mRWLock.readLock().lock();
        try {
            CarDisplayCompatConfig.Key key =
                    new CarDisplayCompatConfig.Key(displayId, packageName, user);
            float scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScale(DEFAULT_SCALE, scaleFactor);
            }
            // Query the scale factor for all packages for a specific user.
            key.packageName = ANY_PACKAGE;
            scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScale(DEFAULT_SCALE, scaleFactor);
            }
            // Query the scale factor for a specific package across all users.
            key.packageName = packageName;
            key.userId = UserHandle.ALL.getIdentifier();
            scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScale(DEFAULT_SCALE, scaleFactor);
            }
            // Query the scale factor for a specific display regardless of
            // user or package name.
            key.packageName = ANY_PACKAGE;
            key.userId = UserHandle.ALL.getIdentifier();
            scaleFactor = mConfig.getScaleFactor(key, NO_SCALE);
            if (scaleFactor != NO_SCALE) {
                return new CompatScale(DEFAULT_SCALE, scaleFactor);
            }
            return null;
        } finally {
            mRWLock.readLock().unlock();
        }
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
