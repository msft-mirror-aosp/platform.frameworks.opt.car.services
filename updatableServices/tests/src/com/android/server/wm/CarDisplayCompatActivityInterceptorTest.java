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

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.wm.CarDisplayCompatActivityInterceptor.LAUNCHED_FROM_HOST;
import static com.android.server.wm.CarDisplayCompatActivityInterceptor.PERMISSION_DISPLAY_COMPATIBILITY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class CarDisplayCompatActivityInterceptorTest {

    private MockitoSession mMockingSession;

    @Mock
    private Resources mMockResources;
    @Mock
    private Context mMockContext;
    @Mock
    private CarDisplayCompatScaleProviderUpdatable mMockCarDisplayCompatScaleProvider;
    @Mock
    private ActivityInterceptorInfoWrapper mMockInfo;


    private CarDisplayCompatActivityInterceptor mInterceptor;
    private ComponentName mHostActitivy = ComponentName.unflattenFromString(
            "com.displaycompathost/.StartActivity");

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
            .initMocks(this)
            .strictness(Strictness.LENIENT)
            .startMocking();

        when(mMockResources.getIdentifier(
                eq("config_defaultDisplayCompatHostActivity"), eq("string"), eq("android")
        )).thenReturn(1);
        when(mMockResources.getString(eq(1))).thenReturn(mHostActitivy.flattenToString());
        when(mMockContext.getResources()).thenReturn(mMockResources);

        mInterceptor = new CarDisplayCompatActivityInterceptor(mMockContext,
                mMockCarDisplayCompatScaleProvider);
    }

    @After
    public void tearDown() {
        // If the exception is thrown during the MockingSession setUp, mMockingSession can be null.
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void hostActivity_isIgnored() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(mHostActitivy);

        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void nonDisplayCompatActivity_isIgnored() {
        Intent intent = getNoDisplayCompatRequiredActivity();
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void displayCompatActivity_launchedFromHost_isIgnored() {
        Intent intent = getDisplayCompatRequiredActivity();
        String packageName = intent.getComponent().getPackageName();
        intent.putExtra(LAUNCHED_FROM_HOST, true);
        when(mMockInfo.getIntent()).thenReturn(intent);

        when(mMockInfo.getCallingPackage()).thenReturn(packageName);
        when(mMockInfo.getCallingPid()).thenReturn(1);
        when(mMockInfo.getCallingUid()).thenReturn(2);
        when(mMockContext.checkPermission(PERMISSION_DISPLAY_COMPATIBILITY, 1, 2))
                .thenReturn(PERMISSION_GRANTED);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void displayCompatActivity_returnsHost() {
        Intent intent = getDisplayCompatRequiredActivity();
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNotNull();
        assertThat(result.getInterceptResult()).isNotNull();
        assertThat(result.getInterceptResult().getIntent()).isNotNull();
        assertThat(result.getInterceptResult().getIntent().getComponent()).isEqualTo(mHostActitivy);
        Intent launchIntent = (Intent) result.getInterceptResult().getIntent()
                .getExtra(Intent.EXTRA_INTENT);
        assertThat(launchIntent).isNotNull();
    }

    @Test
    public void displayCompatActivity_launchedFromDisplayCompatApp_returnsHost() {
        Intent intent = getDisplayCompatRequiredActivity();
        String packageName = intent.getComponent().getPackageName();
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockCarDisplayCompatScaleProvider
                .requiresDisplayCompat(eq(packageName), any(int.class)))
                .thenReturn(true);

        when(mMockInfo.getCallingPackage()).thenReturn(packageName);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNotNull();
        assertThat(result.getInterceptResult()).isNotNull();
        assertThat(result.getInterceptResult().getIntent()).isNotNull();
        assertThat(result.getInterceptResult().getIntent().getComponent()).isEqualTo(mHostActitivy);
        Intent launchIntent = (Intent) result.getInterceptResult().getIntent()
                .getExtra(Intent.EXTRA_INTENT);
        assertThat(launchIntent).isNotNull();
    }

    @Test
    public void displayCompatActivity_noPermission_returnsHost() {
        Intent intent = getDisplayCompatRequiredActivity();
        String packageName = intent.getComponent().getPackageName();
        intent.putExtra(LAUNCHED_FROM_HOST, true);
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockCarDisplayCompatScaleProvider
                .requiresDisplayCompat(eq(packageName), any(int.class)))
                .thenReturn(true);

        when(mMockInfo.getCallingPackage()).thenReturn(packageName);
        when(mMockInfo.getCallingPid()).thenReturn(1);
        when(mMockInfo.getCallingUid()).thenReturn(2);
        when(mMockContext.checkPermission(PERMISSION_DISPLAY_COMPATIBILITY, 1, 2))
                .thenReturn(PERMISSION_DENIED);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNotNull();
        assertThat(result.getInterceptResult()).isNotNull();
        assertThat(result.getInterceptResult().getIntent()).isNotNull();
        assertThat(result.getInterceptResult().getIntent().getComponent()).isEqualTo(mHostActitivy);
        Intent launchIntent = (Intent) result.getInterceptResult().getIntent()
                .getExtra(Intent.EXTRA_INTENT);
        assertThat(launchIntent).isNotNull();
    }

    @Test
    public void hostActivity_whenNoLaunchDisplayId_launchesOnDefaultDisplay() {
        Intent intent = getDisplayCompatRequiredActivity();
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityOptions mockActivityOptions = mock(ActivityOptions.class);
        when(mockActivityOptions.getLaunchDisplayId()).thenReturn(INVALID_DISPLAY);
        ActivityOptionsWrapper mockActivityOptionsWrapper = mock(ActivityOptionsWrapper.class);
        when(mockActivityOptionsWrapper.getOptions()).thenReturn(mockActivityOptions);
        when(mMockInfo.getCheckedOptions()).thenReturn(mockActivityOptionsWrapper);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result.getInterceptResult().getActivityOptions().getLaunchDisplayId())
                .isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public void hostActivity_withLaunchDisplayId_launchesOnCorrectDisplay() {
        Intent intent = getDisplayCompatRequiredActivity();
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityOptions mockActivityOptions = mock(ActivityOptions.class);
        when(mockActivityOptions.getLaunchDisplayId()).thenReturn(2);
        ActivityOptionsWrapper mockActivityOptionsWrapper = mock(ActivityOptionsWrapper.class);
        when(mockActivityOptionsWrapper.getOptions()).thenReturn(mockActivityOptions);
        when(mMockInfo.getCheckedOptions()).thenReturn(mockActivityOptionsWrapper);

        ActivityInterceptResultWrapper result =
                mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result.getInterceptResult().getActivityOptions().getLaunchDisplayId())
                .isEqualTo(2);
    }

    /**
     * Returns an {@link Intent} associated with an {@link Activity} that does not need to run in
     * display compat mode.
     */
    private Intent getNoDisplayCompatRequiredActivity() {
        ComponentName displayCompatActivity =
                ComponentName.unflattenFromString("com.test/.NoDisplayCompatRequiredActivity");
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(displayCompatActivity);
        when(mMockCarDisplayCompatScaleProvider
                .requiresDisplayCompat(eq(displayCompatActivity.getPackageName()), any(int.class)))
                .thenReturn(false);
        return intent;
    }

    /**
     * Returns an {@link Intent} associated with an {@link Activity} that needs to run in
     * display compat mode.
     */
    private Intent getDisplayCompatRequiredActivity() {
        ComponentName displayCompatActivity =
                ComponentName.unflattenFromString("com.test/.DisplayCompatRequiredActivity");
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(displayCompatActivity);
        when(mMockCarDisplayCompatScaleProvider
                .requiresDisplayCompat(eq(displayCompatActivity.getPackageName()), any(int.class)))
                .thenReturn(true);
        return intent;
    }
}
