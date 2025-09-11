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

import android.annotation.UserIdInt;
import android.app.compat.PackageOverride;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Base64;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Xml;

import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.utils.Slogf;
import com.android.service.nano.StringListParamProto;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.GuardedBy;

/**
 * Utility for applying AppCompat (PlatformCompat) overrides to packages
 * from the Car system service layer.
 *
 * <p>Supports runtime updates via DeviceConfig properties.</p>
 *
 * Example usage:
 * <pre>
 *   adb shell device_config put car allowlisted_packages_for_no_dcl_enforcement \
 *       "com.alpha:12345:com.beta:67890"
 *   adb shell device_config put car allow_dcl_bypass true
 * </pre>
 *
 * @hide
 */
public final class CarCompatOverrideUtils {

    private static final String TAG = "CarCompatOverrideUtils";

    private static final String DEVICECONFIG_NAMESPACE = "car";
    private static final String KEY_ALLOWLIST =
            "allowlisted_packages_for_no_dcl_enforcement";
    private static final String KEY_ALLOW_DCL_BYPASS = "allow_dcl_bypass";
    private static final String KEY_CAR_ALLOW_DCL_BYPASS = "car_allow_dcl_bypass";

    private static final long ENFORCE_READ_ONLY_JAVA_DCL = 218865702L;

    private static final String APPLIED_OVERRIDES_XML_FILE =
            "/data/misc/appcompat/compat_framework_overrides.xml";

    private final Context mContext;
    private final IPlatformCompat mPlatformCompat;

    private final Object mAllowlistLock = new Object();

    @GuardedBy("mAllowlistLock")
    private Map<String, Long> mAllowlistedPackages = new ArrayMap<>();

    @GuardedBy("mAllowlistLock")
    private final Set<String> mAppliedOverrides = new ArraySet<>();

    public CarCompatOverrideUtils(Context context) {
        mContext = context;
        mPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

        initAppliedOverridesFromXml();
        registerDeviceConfigListener();
    }

    /**
     * Checks whether the DCL override should be applied for a given package and if needed,
     * applies the override.
     *
     * @param packageName target package
     * @param userId user ID
     * @return true if override is applied, false otherwise
     */
    public boolean applyOverrideIfNeeded(String packageName, @UserIdInt int userId) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }

        synchronized (mAllowlistLock) {
            if (mAppliedOverrides.contains(packageName)) {
                Slogf.d(TAG,
                        "DCL override already applied for %s, skipping.",
                        packageName);
                return true;
            }
        }

        boolean allowDclBypass = DeviceConfig.getBoolean(
                DEVICECONFIG_NAMESPACE, KEY_ALLOW_DCL_BYPASS, true);

        if (allowDclBypass) {
            DeviceConfig.setProperty(
                    DEVICECONFIG_NAMESPACE,
                    KEY_CAR_ALLOW_DCL_BYPASS,
                    "true",
                    false /* volatile, reset on reboot */);

            PackageManager pm = mContext.getPackageManager();
            try {
                // flags = 0 → default
                PackageInfo info = pm.getPackageInfoAsUser(packageName, 0 /*default*/, userId);
                long installedVersion = info.getLongVersionCode();

                Long allowedVersion;
                synchronized (mAllowlistLock) {
                    allowedVersion = mAllowlistedPackages.get(packageName);

                    if (allowedVersion == null) {
                        if (mAllowlistedPackages.isEmpty()) {
                            applyAllowlistOverrides(packageName);
                            return true;
                        }
                    }
                }

                if (allowedVersion != null && installedVersion <= allowedVersion) {
                    applyAllowlistOverrides(packageName);
                    return true;
                }

                Slogf.d(TAG,
                        "Package %s not eligible for DCL override. Installed=%d Allowed=%s",
                        packageName, installedVersion, allowedVersion);
            } catch (PackageManager.NameNotFoundException e) {
                Slogf.w(TAG,
                        "Package not found when checking DCL override: %s",
                        packageName, e);
            }
        }

        return false;
    }

    /**
     * Applies the allowlist override for a single package.
     *
     * @param pkg the package name
     */
    public void applyAllowlistOverrides(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return;
        }

        try {
            Map<Long, PackageOverride> map = new ArrayMap<>();
            map.put(ENFORCE_READ_ONLY_JAVA_DCL,
                    new PackageOverride.Builder().setEnabled(false).build());

            CompatibilityOverrideConfig config = new CompatibilityOverrideConfig(map);
            mPlatformCompat.putOverridesOnReleaseBuilds(config, pkg);

            synchronized (mAllowlistLock) {
                mAppliedOverrides.add(pkg);
            }

            Slogf.i(TAG, "Applied DCL override for %s", pkg);
        } catch (RemoteException e) {
            Slogf.w(TAG,
                    "Failed to apply DCL override for %s",
                    pkg, e);
        } catch (SecurityException e) {
            Slogf.w(TAG,
                    "Security exception applying DCL override for %s",
                    pkg, e);
        } catch (Exception e) {
            Slogf.w(TAG,
                    "Unexpected exception applying DCL override for %s",
                    pkg, e);
        }
    }

    /**
     * Clears all previously applied DCL overrides.
     */
    private void clearAllAllowlistOverrides() {
        synchronized (mAllowlistLock) {
            if (mAppliedOverrides.isEmpty()) {
                Slogf.i(TAG, "No applied overrides to clear.");
                return;
            }

            Set<Long> changeIds = new ArraySet<>();
            changeIds.add(ENFORCE_READ_ONLY_JAVA_DCL);

            for (String pkg : mAppliedOverrides) {
                try {
                    CompatibilityOverridesToRemoveConfig removeConfig =
                            new CompatibilityOverridesToRemoveConfig(changeIds);
                    mPlatformCompat.removeOverridesOnReleaseBuilds(
                            removeConfig, pkg);

                    Slogf.i(TAG, "Cleared DCL override for %s", pkg);
                } catch (Exception e) {
                    Slogf.w(TAG,
                            "Failed to clear DCL override for %s",
                            pkg, e);
                }
            }

            mAppliedOverrides.clear();
        }

        Slogf.i(TAG, "All applied overrides cleared.");

        DeviceConfig.setProperty(
                DEVICECONFIG_NAMESPACE,
                KEY_CAR_ALLOW_DCL_BYPASS,
                "false",
                false /* volatile */);
    }

    private void registerDeviceConfigListener() {
        DeviceConfig.addOnPropertiesChangedListener(
                DEVICECONFIG_NAMESPACE,
                mContext.getMainExecutor(),
                properties -> {
                    synchronized (mAllowlistLock) {
                        for (String key : properties.getKeyset()) {
                            if (KEY_ALLOWLIST.equals(key)) {
                                String value = properties.getString(key, null);
                                Slogf.d(TAG,
                                        "Allowlist DeviceConfig change detected: %s",
                                        value);
                                mAllowlistedPackages = parseAllowlist(value);
                            } else if (KEY_ALLOW_DCL_BYPASS.equals(key)) {
                                boolean allowFlag =
                                        properties.getBoolean(key, true);
                                Slogf.d(TAG,
                                        "ALLOW DCL bypass flag changed: %s",
                                        allowFlag);
                                if (!allowFlag) {
                                    clearAllAllowlistOverrides();
                                }
                            }
                        }
                    }
                });
    }

    // Called while holding mAllowlistLock
    private Map<String, Long> parseAllowlist(String base64Value) {
        Map<String, Long> allowlist = new ArrayMap<>();

        if (TextUtils.isEmpty(base64Value)) {
            Slogf.w(TAG,
                    "Allowlist value for key=%s is empty.",
                    KEY_ALLOWLIST);
            return allowlist;
        }

        try {
            byte[] decodedBytes =
                    Base64.decode(base64Value,
                            Base64.NO_PADDING | Base64.NO_WRAP);

            StringListParamProto packageList =
                    StringListParamProto.parseFrom(decodedBytes);

            for (int i = 0; i < packageList.element.length; i++) {
                String entry = packageList.element[i];
                Slogf.i(TAG, "Processing allowlist entry: %s", entry);
                if (TextUtils.isEmpty(entry)) continue;

                String[] parts = entry.split(":");
                if (parts.length == 2) {
                    String packageName = parts[0].trim();
                    try {
                        long versionCode =
                                Long.parseLong(parts[1].trim());
                        allowlist.put(packageName, versionCode);
                        Slogf.i(TAG,
                                "Added to allowlist: %s -> %d",
                                packageName, versionCode);
                    } catch (NumberFormatException e) {
                        Slogf.w(TAG,
                                "Invalid version code for entry: %s",
                                entry);
                    }
                } else {
                    Slogf.w(TAG,
                            "Invalid allowlist entry (package:version expected): %s",
                            entry);
                }
            }

            Slogf.i(TAG, "Final allowlist size: %d", allowlist.size());
        } catch (IllegalArgumentException e) {
            Slogf.e(TAG,
                    "Failed to Base64-decode allowlist value for %s",
                    KEY_ALLOWLIST, e);
        } catch (Exception e) {
            Slogf.e(TAG,
                    "Failed to parse StringListParamProto from Base64",
                    e);
        }

        return allowlist;
    }

    private void initAppliedOverridesFromXml() {
        File xmlFile = new File(APPLIED_OVERRIDES_XML_FILE);
        if (!xmlFile.exists()) {
            Slogf.w(TAG,
                    "Compat overrides XML file does not exist: %s",
                    APPLIED_OVERRIDES_XML_FILE);
            return;
        }

        Set<String> loadedOverrides = new ArraySet<>();
        try (InputStream in =
                     new BufferedInputStream(new FileInputStream(xmlFile))) {

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            boolean insideTargetChange = false;
            boolean insideRaw = false;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        String tag = parser.getName();
                        if ("change-overrides".equals(tag)) {
                            String changeId =
                                    parser.getAttributeValue(null, "changeId");
                            insideTargetChange =
                                    Long.toString(ENFORCE_READ_ONLY_JAVA_DCL)
                                            .equals(changeId);
                        } else if (insideTargetChange
                                && "raw".equals(tag)) {
                            insideRaw = true;
                        } else if (insideTargetChange && insideRaw
                                && "raw-override-value".equals(tag)) {
                            String pkgName =
                                    parser.getAttributeValue(null, "packageName");
                            if (!TextUtils.isEmpty(pkgName)) {
                                loadedOverrides.add(pkgName);
                                Slogf.i(TAG,
                                        "Loaded applied override from XML: %s",
                                        pkgName);
                            }
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        String endTag = parser.getName();
                        if ("raw".equals(endTag)) {
                            insideRaw = false;
                        } else if ("change-overrides".equals(endTag)) {
                            insideTargetChange = false;
                        }
                        break;
                }
                eventType = parser.next();
            }

        } catch (Exception e) {
            Slogf.w(TAG,
                    "Failed to initialize applied overrides from XML",
                    e);
        }

        if (!loadedOverrides.isEmpty()) {
            synchronized (mAllowlistLock) {
                mAppliedOverrides.addAll(loadedOverrides);
            }
        }
    }
}
