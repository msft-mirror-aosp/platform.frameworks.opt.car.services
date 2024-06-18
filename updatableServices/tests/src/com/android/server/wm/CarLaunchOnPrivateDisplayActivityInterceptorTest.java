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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.wm.CarLaunchOnPrivateDisplayActivityInterceptor.LAUNCH_ACTIVITY;
import static com.android.server.wm.CarLaunchOnPrivateDisplayActivityInterceptor.LAUNCH_ACTIVITY_DISPLAY_ID;
import static com.android.server.wm.CarLaunchOnPrivateDisplayActivityInterceptor.LAUNCH_ON_PRIVATE_DISPLAY;
import static com.android.server.wm.CarLaunchOnPrivateDisplayActivityInterceptor.PERMISSION_ACCESS_PRIVATE_DISPLAY_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.builtin.view.DisplayHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Objects;

/**
 * Unit tests for launching on private displays (physical or virtual).
 */
@RunWith(AndroidJUnit4.class)
public class CarLaunchOnPrivateDisplayActivityInterceptorTest {
    private static final String DISPLAY_ID_NO_LAUNCH_PRIVATE_DISPLAY_KEY = "-999";
    private static final String INVALID_DISPLAY = "-1";
    private static final int DISPLAY_ID_PHYSICAL_PRIVATE = 0;
    private static final int DISPLAY_ID_VIRTUAL_PRIVATE = 2;
    private static final String UNIQUE_DISPLAY_ID_PHYSICAL_PRIVATE = "local: 0";
    private static final String UNIQUE_DISPLAY_ID_VIRTUAL_PRIVATE = "virtual: 0";
    private static final String ALLOWLISTED_ACTIVITY = "com.test.allowlisted/.exampleActivity";
    private static final String DENYLISTED_ACTIVITY = "com.test.notallowlisted/.exampleActivity";

    private final ComponentName mRouterActivity = ComponentName.unflattenFromString(
            "com.test/.LaunchOnPrivateDisplayRouterActivity");
    private final String[] mAllowlistedPackageNames = {"com.test.allowlisted"};

    @Mock
    private Resources mMockResources;
    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private ActivityInterceptorInfoWrapper mMockInfo;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private Display mDisplay1;
    @Mock
    private Display mDisplay2;

    private MockitoSession mMockingSession;
    private CarLaunchOnPrivateDisplayActivityInterceptor mInterceptor;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(DisplayHelper.class)
                .startMocking();

        when(mMockResources.getIdentifier(eq("config_defaultLaunchOnPrivateDisplayRouterActivity"),
                eq("string"), eq("android"))).thenReturn(1);
        when(mMockResources.getString(eq(1))).thenReturn(mRouterActivity.flattenToString());
        when(mMockResources.getIdentifier(
                eq("config_defaultAllowlistLaunchOnPrivateDisplayPackages"), eq("array"),
                eq("android"))).thenReturn(2);
        when(mMockResources.getStringArray(eq(2))).thenReturn(mAllowlistedPackageNames);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        doReturn(mDisplayManager).when(mMockContext).getSystemService(eq(DisplayManager.class));
        doReturn(mDisplayManager).when(mMockContext).getSystemService(eq(DisplayManager.class));
        doReturn(new Display[]{mDisplay1, mDisplay2}).when(mDisplayManager).getDisplays();
        doReturn(DISPLAY_ID_PHYSICAL_PRIVATE).when(mDisplay1).getDisplayId();
        doReturn(DISPLAY_ID_VIRTUAL_PRIVATE).when(mDisplay2).getDisplayId();
        when(DisplayHelper.getUniqueId(mDisplay1)).thenReturn(UNIQUE_DISPLAY_ID_PHYSICAL_PRIVATE);
        when(DisplayHelper.getUniqueId(mDisplay2)).thenReturn(UNIQUE_DISPLAY_ID_VIRTUAL_PRIVATE);
        when(mMockPackageManager.resolveActivity(any(Intent.class),
                any(PackageManager.ResolveInfoFlags.class))).thenReturn(mock(ResolveInfo.class));
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);

        mInterceptor = new CarLaunchOnPrivateDisplayActivityInterceptor(mMockContext);
    }

    @After
    public void tearDown() {
        // If the exception is thrown during the MockingSession setUp, mMockingSession can be null.
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void launchOnPhysicalPrivateDisplay_noLaunchOnPrivateDisplayKey_returnsNull() {
        mMockInfo = createMockActivityInterceptorInfo(DISPLAY_ID_NO_LAUNCH_PRIVATE_DISPLAY_KEY,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void launchOnPhysicalPrivateDisplay_invalidDisplay_returnsNull() {
        mMockInfo = createMockActivityInterceptorInfo(INVALID_DISPLAY, ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void launchOnPhysicalPrivateDisplay_notAllowlisted_returnsNull() {
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_PHYSICAL_PRIVATE,
                DENYLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void launchOnVirtualPrivateDisplay_notAllowlisted_returnsNull() {
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_VIRTUAL_PRIVATE,
                DENYLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void launchOnPhysicalPrivateDisplay_isAllowlisted_returnsNotNull() {
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_PHYSICAL_PRIVATE,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityInterceptResultNotNull(result, DISPLAY_ID_PHYSICAL_PRIVATE);
    }

    @Test
    public void launchOnVirtualPrivateDisplay_isAllowlisted_returnsNotNull() {
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_VIRTUAL_PRIVATE,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityInterceptResultNotNull(result, DISPLAY_ID_VIRTUAL_PRIVATE);
    }

    @Test
    public void launchOnPhysicalPrivateDisplay_noPermission_returnsNull() {
        applyPermission(PackageManager.PERMISSION_DENIED);
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_PHYSICAL_PRIVATE,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void launchOnVirtualPrivateDisplay_noPermission_returnsNull() {
        applyPermission(PackageManager.PERMISSION_DENIED);
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_VIRTUAL_PRIVATE,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void launchOnPhysicalPrivateDisplay_hasPermission_returnsNotNull() {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_PHYSICAL_PRIVATE,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityInterceptResultNotNull(result, DISPLAY_ID_PHYSICAL_PRIVATE);
    }

    @Test
    public void launchOnVirtualPrivateDisplay_hasPermission_returnsNotNull() {
        applyPermission(PackageManager.PERMISSION_GRANTED);
        mMockInfo = createMockActivityInterceptorInfo(UNIQUE_DISPLAY_ID_VIRTUAL_PRIVATE,
                ALLOWLISTED_ACTIVITY);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityInterceptResultNotNull(result, DISPLAY_ID_VIRTUAL_PRIVATE);
    }

    private void assertActivityInterceptResultNotNull(ActivityInterceptResultWrapper result,
            int displayId) {
        assertThat(result).isNotNull();
        assertThat(result.getInterceptResult()).isNotNull();
        assertThat(result.getInterceptResult().getIntent()).isNotNull();
        assertThat(result.getInterceptResult().getIntent().getComponent()).isEqualTo(
                mRouterActivity);
        Intent launchIntent = (Intent) result.getInterceptResult().getIntent()
                .getExtra(LAUNCH_ACTIVITY);
        assertThat(launchIntent).isNotNull();
        assertThat(result.getInterceptResult().getIntent().getExtras().getInt(
                LAUNCH_ACTIVITY_DISPLAY_ID)).isEqualTo(displayId);
    }

    private Intent getActivityLaunchOnDisplay(String displayId, String allowlistedActivity) {
        ComponentName exampleActivity =
                ComponentName.unflattenFromString(allowlistedActivity);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(exampleActivity);
        if (!Objects.equals(displayId, DISPLAY_ID_NO_LAUNCH_PRIVATE_DISPLAY_KEY)) {
            intent.putExtra(LAUNCH_ON_PRIVATE_DISPLAY, displayId);
        }
        return intent;
    }

    private void applyPermission(int permissionValue) {
        when(mMockInfo.getCallingPid()).thenReturn(1);
        when(mMockInfo.getCallingUid()).thenReturn(2);
        when(mMockContext.checkPermission(PERMISSION_ACCESS_PRIVATE_DISPLAY_ID, 1, 2))
                .thenReturn(permissionValue);
    }

    private ActivityInterceptorInfoWrapper createMockActivityInterceptorInfo(String displayId,
            String allowlistedActivity) {
        Intent intent = getActivityLaunchOnDisplay(displayId, allowlistedActivity);
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockInfo.getCallingPackage()).thenReturn(intent.getPackage());
        return mMockInfo;
    }
}
