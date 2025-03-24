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

import static android.car.feature.Flags.FLAG_CAR_MEDIA_APPS_SUSPENSION;
import static android.car.media.CarMediaIntents.ACTION_MEDIA_TEMPLATE;
import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;

import static com.android.internal.app.SuspendedAppActivity.EXTRA_DIALOG_INFO;
import static com.android.internal.app.SuspendedAppActivity.EXTRA_SUSPENDED_PACKAGE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserPackage;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@EnableFlags(FLAG_CAR_MEDIA_APPS_SUSPENSION)
@RunWith(AndroidJUnit4.class)
public final class MediaTemplateActivityInterceptorForSuspensionTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int USER_ID = 10;
    private static final String PACKAGE_NAME = "com.test.package";
    private static final String PACKAGE_CLASS_NAME = "Test package";
    private static final String ACTION_MEDIA_TEMPLATE_V2 =
            "androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V2";
    private static final String ACTION_MEDIA_TEMPLATE_V3 =
            "androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V3";

    private static final String ACTION_MEDIA_TEMPLATE_V3ALPHA =
            "androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V3.5-alpha";
    private static final String INVALID_PREFIX_ACTION_MEDIA_TEMPLATE =
            "androidx.car.app.media.action.MEDIA_TEMPLATE_V2";
    private static final ActivityOptions BASIC_ACTIVITY_OPTIONS = ActivityOptions.makeBasic();

    private final ActivityInterceptorInfoWrapper mMockInfo =
            mock(ActivityInterceptorInfoWrapper.class);
    private final UserPackage mMockUserPackage = mock(UserPackage.class);
    private final SuspendDialogInfo mSuspendDialogInfo = new SuspendDialogInfo.Builder().build();

    private PackageManagerInternal mMockPackageManagerInternal;
    private MediaTemplateActivityInterceptorForSuspension mInterceptor;

    @Before
    public void setUp() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);

        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);
        mInterceptor = new MediaTemplateActivityInterceptorForSuspension();

        when(mMockInfo.getUserId()).thenReturn(USER_ID);
        when(mMockPackageManagerInternal.getSuspendingPackage(anyString(), anyInt()))
                .thenReturn(mMockUserPackage);
        when(mMockPackageManagerInternal
                .getSuspendedDialogInfo(anyString(), eq(mMockUserPackage), anyInt())
        ).thenReturn(mSuspendDialogInfo);
    }

    @Test
    public void mediaInterceptor_whenIntentActionIsEmpty_doesNotIntercept() {
        Intent intent = new Intent();
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }


    @Test
    public void mediaInterceptor_whenMediaComponentExtraIsEmpty_doesNotIntercept() {
        Intent intent = new Intent(ACTION_MEDIA_TEMPLATE);
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void mediaInterceptor_whenIntentActionNotMediaTemplate_doesNotIntercept() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void mediaInterceptor_onMediaTemplateIntentAndPackageNotSuspended_doesNotIntercept() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(ACTION_MEDIA_TEMPLATE);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockPackageManagerInternal.isPackageSuspended(PACKAGE_NAME, USER_ID))
                .thenReturn(false);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    @Test
    public void mediaInterceptor_onMediaTemplateIntentAndMediaPackageSuspended_interceptsLaunch() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(ACTION_MEDIA_TEMPLATE);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockPackageManagerInternal.isPackageSuspended(PACKAGE_NAME, USER_ID))
                .thenReturn(true);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityIntercept(result);
    }

    @Test
    public void mediaInterceptor_onMediaTemplateV2IntentAndPackageSuspended_intercepts() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(ACTION_MEDIA_TEMPLATE_V2);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockPackageManagerInternal.isPackageSuspended(PACKAGE_NAME, USER_ID))
                .thenReturn(true);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityIntercept(result);
    }

    @Test
    public void mediaInterceptor_onMediaTemplateV3IntentAndPackageSuspended_intercepts() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(ACTION_MEDIA_TEMPLATE_V3);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockPackageManagerInternal.isPackageSuspended(PACKAGE_NAME, USER_ID))
                .thenReturn(true);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityIntercept(result);
    }

    @Test
    public void mediaInterceptor_onMediaTemplateV3AlphaIntentAndPackageSuspended_intercepts() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(ACTION_MEDIA_TEMPLATE_V3ALPHA);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockPackageManagerInternal.isPackageSuspended(PACKAGE_NAME, USER_ID))
                .thenReturn(true);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertActivityIntercept(result);
    }

    @Test
    public void mediaInterceptor_onInvalidMediaTemplateIntent_doesNotIntercept() {
        ComponentName componentName = new ComponentName(PACKAGE_NAME, PACKAGE_CLASS_NAME);
        Intent intent = new Intent(INVALID_PREFIX_ACTION_MEDIA_TEMPLATE);
        intent.putExtra(EXTRA_MEDIA_COMPONENT, componentName.flattenToShortString());
        when(mMockInfo.getIntent()).thenReturn(intent);
        when(mMockPackageManagerInternal.isPackageSuspended(PACKAGE_NAME, USER_ID))
                .thenReturn(true);

        ActivityInterceptResultWrapper result = mInterceptor.onInterceptActivityLaunch(mMockInfo);

        assertThat(result).isNull();
    }

    private void assertActivityIntercept(ActivityInterceptResultWrapper result) {
        assertThat(result).isNotNull();

        Intent intent = result.getInterceptResult().getIntent();
        String suspendedPackageName = intent.getStringExtra(EXTRA_SUSPENDED_PACKAGE);
        SuspendDialogInfo suspendedDialog = intent.getParcelableExtra(
                EXTRA_DIALOG_INFO,
                SuspendDialogInfo.class
        );

        assertThat(suspendedPackageName).isEqualTo(PACKAGE_NAME);
        assertThat(suspendedDialog).isEqualTo(mSuspendDialogInfo);
        // Assert ActivityOptions
        ActivityOptions activityOptions = result.getInterceptResult().getActivityOptions();
        assertThat(activityOptions.getPackageName())
                .isEqualTo(BASIC_ACTIVITY_OPTIONS.getPackageName());
        assertThat(activityOptions.getLaunchActivityType())
                .isEqualTo(BASIC_ACTIVITY_OPTIONS.getLaunchActivityType());
        assertThat(activityOptions.getLaunchDisplayId())
                .isEqualTo(BASIC_ACTIVITY_OPTIONS.getLaunchDisplayId());
        assertThat(activityOptions.getCallerDisplayId())
                .isEqualTo(BASIC_ACTIVITY_OPTIONS.getCallerDisplayId());
    }
}
