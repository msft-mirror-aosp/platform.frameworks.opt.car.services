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

import static android.car.feature.Flags.FLAG_DISPLAY_COMPATIBILITY;
import static android.content.ContentResolver.NOTIFY_INSERT;
import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.FeatureInfo.FLAG_REQUIRED;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.content.pm.PackageManager.SIGNATURE_MATCH;
import static android.content.pm.PackageManager.SIGNATURE_NO_MATCH;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.CarDisplayCompatConfig.ANY_PACKAGE;
import static com.android.server.wm.CarDisplayCompatConfig.DEFAULT_SCALE;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.NO_SCALE;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.OPT_OUT;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.DATA_SCHEME_PACKAGE;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.DISPLAYCOMPAT_SETTINGS_SECURE_KEY;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.FEATURE_CAR_DISPLAY_COMPATIBILITY;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.META_DATA_DISTRACTION_OPTIMIZED;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.PLATFORM_PACKAGE_NAME;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.USER_NULL;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

@RequiresFlagsEnabled(FLAG_DISPLAY_COMPATIBILITY)
@RunWith(AndroidJUnit4.class)
public class CarDisplayCompatScaleProviderUpdatableTest {

    private static final int CURRENT_USER = 100;
    private static final int ANOTHER_USER = 120;

    @Rule
    public final CheckFlagsRule checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private CarDisplayCompatScaleProviderUpdatableImpl mImpl;
    private CarDisplayCompatConfig mConfig;
    private MockitoSession mMockingSession;

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageInfo mPackageInfo;
    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private CarDisplayCompatScaleProviderInterface mInterface;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private Looper mMainLooper;

    @Before
    public void setUp() throws XmlPullParserException, IOException, SecurityException {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(eq(FEATURE_CAR_DISPLAY_COMPATIBILITY)))
                .thenReturn(true);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getMainLooper()).thenReturn(mMainLooper);
        when(mInterface.getCurrentAndTargetUserIds())
                .thenReturn(Pair.create(CURRENT_USER, USER_NULL));
        when(mInterface.getCompatModeScalingFactor(any(String.class), any(UserHandle.class)))
                .thenReturn(DEFAULT_SCALE);
        mConfig = new CarDisplayCompatConfig();
        String emptyConfig = "<config></config>";
        try (InputStream in = new ByteArrayInputStream(emptyConfig.getBytes());) {
            mConfig.populate(in);
        }
        mImpl = new CarDisplayCompatScaleProviderUpdatableImpl(mContext, mInterface, mConfig);
    }

    @After
    public void tearDown() {
        // If the exception is thrown during the MockingSession setUp, mMockingSession can be null.
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void noApplicationInfo_returnsFalse() throws NameNotFoundException {
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(null);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void hasMedataDataDisplayCompat_requiredFalse_returnsFalse()
            throws NameNotFoundException {
        mApplicationInfo.metaData = new Bundle();
        mApplicationInfo.metaData.putBoolean(FEATURE_CAR_DISPLAY_COMPATIBILITY, false);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void hasMedataData_notDisplayCompat_returnsTrue()
            throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        mApplicationInfo.metaData = new Bundle();
        mApplicationInfo.metaData.putBoolean("key1", false);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();
    }

    @Test
    public void hasMedataDataDisplayCompat_requiredTrue_returnsTrue() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        mApplicationInfo.metaData = new Bundle();
        mApplicationInfo.metaData.putBoolean(FEATURE_CAR_DISPLAY_COMPATIBILITY, true);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();
    }

    @Test
    public void hasFeatureAutomotive_requiredTrue_returnsFalse() throws NameNotFoundException {
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_AUTOMOTIVE;
        features[0].flags = FLAG_REQUIRED;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void hasFeatureAutomotive_requiredFalse_returnsFalse() throws NameNotFoundException {
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_AUTOMOTIVE;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void hasNoActivities_returnsFalse() throws NameNotFoundException {
        mPackageInfo.activities = null;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void hasDistractionOptimizedActivity_returnsFalse() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        activities[0].metaData = new Bundle();
        activities[0].metaData.putBoolean(META_DATA_DISTRACTION_OPTIMIZED, true);
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void isPrivileged_returnsFalse() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mApplicationInfo.isPrivilegedApp()).thenReturn(true);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void isSystem_returnsFalse() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        mApplicationInfo.flags = FLAG_SYSTEM;
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void hasPlatformSignature_returnsFalse() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_MATCH);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }

    @Test
    public void requiresDisplayCompat_packageStateIsCached() throws NameNotFoundException {
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
        // Verify the number of calls to PackageManager#getPackageInfo did not increase.
        verify(mInterface, times(1)).getPackageInfoAsUser(eq("package1"),
                any(PackageInfoFlags.class), any(int.class));
    }

    @Test
    public void matchDisplay_scalesPackage() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE,
                        UserHandle.ALL.getIdentifier());
        mConfig.setScaleFactor(key, 0.5f);

        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
    }

    @Test
    public void matchDisplayPackage_scalesPackage() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "package1",
                        UserHandle.ALL.getIdentifier());
        mConfig.setScaleFactor(key, 0.5f);
        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
        assertThat(mImpl.getCompatScale("package2", CURRENT_USER)).isNull();
    }

    @Test
    public void matchDisplayUser_scalesPackage() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();
        assertThat(mImpl.requiresDisplayCompat("package1", ANOTHER_USER)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE, CURRENT_USER);
        mConfig.setScaleFactor(key, 0.5f);

        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
        assertThat(mImpl.getCompatScale("package1", ANOTHER_USER)).isNull();
    }

    @Test
    public void matchDisplayPackageUser_scalesPackage() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();

        when(mInterface.getPackageInfoAsUser(eq("package2"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package2")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package2"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package2", 11)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "package1", CURRENT_USER);
        mConfig.setScaleFactor(key, 0.5f);

        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
        assertThat(mImpl.getCompatScale("package1", ANOTHER_USER)).isNull();
        assertThat(mImpl.getCompatScale("package2", CURRENT_USER)).isNull();
    }

    @Test
    public void displayUserPackage_hasHigherPriority_thanDisplayUser()
            throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "package1", CURRENT_USER);
        CarDisplayCompatConfig.Key key1 =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE, CURRENT_USER);
        mConfig.setScaleFactor(key, 0.5f);
        mConfig.setScaleFactor(key1, 0.6f);

        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
    }

    @Test
    public void displayUser_hasHigherPriority_thanDisplayPackage() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE, CURRENT_USER);
        CarDisplayCompatConfig.Key key1 =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "package1",
                        UserHandle.ALL.getIdentifier());
        mConfig.setScaleFactor(key, 0.5f);
        mConfig.setScaleFactor(key1, 0.6f);

        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
    }

    @Test
    public void displayPackage_hasHigherPriority_thanDisplay() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        FeatureInfo[] features = new FeatureInfo[1];
        features[0] = new FeatureInfo();
        features[0].name = FEATURE_CAR_DISPLAY_COMPATIBILITY;
        mPackageInfo.reqFeatures = features;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);

        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "package1",
                        UserHandle.ALL.getIdentifier());
        CarDisplayCompatConfig.Key key1 =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE,
                        UserHandle.ALL.getIdentifier());
        mConfig.setScaleFactor(key, 0.5f);
        mConfig.setScaleFactor(key1, 0.6f);

        assertThat(mImpl.getCompatScale("package1", CURRENT_USER).getDensityScaleFactor())
                .isEqualTo(0.5f);
    }

    @Test
    public void updatingSettings_updatesConfigForUser() {
        String userConfigXml = "<config><scale display=\"0\">0.5</scale></config>";
        when(mInterface.getStringForUser(any(ContentResolver.class), any(String.class),
                any(int.class))).thenReturn(userConfigXml);

        Uri keyUri = Settings.Secure.getUriFor(DISPLAYCOMPAT_SETTINGS_SECURE_KEY);
        mImpl.mSettingsContentObserver.onChange(false, Collections.singletonList(keyUri),
                NOTIFY_INSERT, UserHandle.of(mImpl.getCurrentOrTargetUserId()));

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE,
                        UserHandle.ALL.getIdentifier());
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(0.5f);
    }

    @Test
    public void switchingUser_updatesConfigForUser() {
        String user100ConfigXml = "<config><scale display=\"0\">0.5</scale></config>";
        when(mInterface.getStringForUser(any(ContentResolver.class), any(String.class),
                eq(CURRENT_USER))).thenReturn(user100ConfigXml);
        Uri keyUri = Settings.Secure.getUriFor(DISPLAYCOMPAT_SETTINGS_SECURE_KEY);
        mImpl.mSettingsContentObserver.onChange(false, Collections.singletonList(keyUri),
                NOTIFY_INSERT, UserHandle.of(mImpl.getCurrentOrTargetUserId()));
        String user120ConfigXml = "<config><scale display=\"0\">0.7</scale></config>";
        when(mInterface.getStringForUser(any(ContentResolver.class), any(String.class),
                eq(ANOTHER_USER))).thenReturn(user120ConfigXml);
        mImpl.handleCurrentUserSwitching(UserHandle.of(ANOTHER_USER));

        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE,
                        UserHandle.ALL.getIdentifier());
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(0.7f);
    }

    @Test
    public void packageOptOut_withoutScaling() {
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "package1", CURRENT_USER);
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(OPT_OUT);
    }

    @Test
    public void packageAdded_updatesConfig() throws NameNotFoundException {
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();

        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);

        Intent i = new Intent(Intent.ACTION_PACKAGE_ADDED);
        i.setData(Uri.fromParts(DATA_SCHEME_PACKAGE, "package1" , null));
        mImpl.mPackageChangeReceiver.onReceive(mContext, i);
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();
    }

    @Test
    public void packageChanged_updatesConfig() throws NameNotFoundException {
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();

        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);

        Intent i = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        i.setData(Uri.fromParts(DATA_SCHEME_PACKAGE, "package1" , null));
        mImpl.mPackageChangeReceiver.onReceive(mContext, i);
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();
    }

    @Test
    public void packageReplaced_updatesConfig() throws NameNotFoundException {
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();

        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);

        Intent i = new Intent(Intent.ACTION_PACKAGE_REPLACED);
        i.setData(Uri.fromParts(DATA_SCHEME_PACKAGE, "package1" , null));
        mImpl.mPackageChangeReceiver.onReceive(mContext, i);
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isTrue();
    }

    @Test
    public void packageRemoved_updatesConfig() throws NameNotFoundException {
        ActivityInfo[] activities = new ActivityInfo[1];
        activities[0] = new ActivityInfo();
        mPackageInfo.activities = activities;
        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(mPackageInfo);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(mApplicationInfo);
        when(mPackageManager.checkSignatures(eq(PLATFORM_PACKAGE_NAME), eq("package1")))
                .thenReturn(SIGNATURE_NO_MATCH);

        when(mInterface.getPackageInfoAsUser(eq("package1"), any(PackageInfoFlags.class),
                any(int.class))).thenReturn(null);
        when(mPackageManager.getApplicationInfoAsUser(eq("package1"),
                any(ApplicationInfoFlags.class), any(UserHandle.class)))
                        .thenReturn(null);
        Intent i = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        i.setData(Uri.fromParts(DATA_SCHEME_PACKAGE, "package1" , null));
        mImpl.mPackageChangeReceiver.onReceive(mContext, i);
        assertThat(mImpl.requiresDisplayCompat("package1", CURRENT_USER)).isFalse();
    }
}
