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

import static android.car.media.CarMediaIntents.ACTION_MEDIA_TEMPLATE;
import static android.car.media.CarMediaIntents.EXTRA_MEDIA_COMPONENT;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserPackage;

import com.android.internal.app.SuspendedAppActivity;
import com.android.server.LocalServices;

import java.util.regex.Pattern;

/**
 * This class handles interception of suspended templated media apps.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class MediaTemplateActivityInterceptorForSuspension
        implements CarActivityInterceptorUpdatable {
    private static final String MEDIA_TEMPLATE_REGEX =
            "androidx\\.car\\.app\\.mediaextensions\\.action\\.MEDIA_TEMPLATE_V.+";
    private static final Pattern MEDIA_TEMPLATE_ACTION_PATTERN =
            Pattern.compile(MEDIA_TEMPLATE_REGEX);

    private final PackageManagerInternal mPackageManagerInternal;

    public MediaTemplateActivityInterceptorForSuspension() {
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    @Nullable
    @Override
    public ActivityInterceptResultWrapper onInterceptActivityLaunch(
            ActivityInterceptorInfoWrapper info) {
        Intent launchIntent = info.getIntent();
        if (launchIntent == null) {
            return null;
        }
        if (!isActionMediaTemplate(launchIntent.getAction())) {
            return null;
        }
        String packageName = getMediaPackage(launchIntent);
        if (packageName == null) {
            return null;
        }
        int userId = info.getUserId();
        if (!mPackageManagerInternal.isPackageSuspended(packageName, userId)) {
            return null;
        }
        UserPackage suspender = mPackageManagerInternal.getSuspendingPackage(packageName, userId);
        SuspendDialogInfo dialogInfo =
                mPackageManagerInternal.getSuspendedDialogInfo(packageName, suspender, userId);
        Intent intent = SuspendedAppActivity.createSuspendedAppInterceptIntent(
                packageName,
                suspender,
                dialogInfo,
                /* options = */ null,
                /* onUnsuspend = */ null,
                userId
        );
        // SuspendedAppActivity should be launched with the default options, the calling activity
        // options should not affect the default dialog.
        return ActivityInterceptResultWrapper.create(intent, ActivityOptions.makeBasic());
    }

    private static boolean isActionMediaTemplate(@Nullable String action) {
        if (action == null) {
            return false;
        }
        if (ACTION_MEDIA_TEMPLATE.equals(action)) {
            return true;
        }
        return MEDIA_TEMPLATE_ACTION_PATTERN.matcher(action).matches();
    }

    @Nullable
    private static String getMediaPackage(Intent intent) {
        // Media Template Activity have the MBS service defined in the EXTRA_MEDIA_COMPONENT
        String componentNameString = intent.getStringExtra(EXTRA_MEDIA_COMPONENT);
        if (componentNameString == null) {
            return null;
        }
        ComponentName componentName = ComponentName.unflattenFromString(componentNameString);
        if (componentName == null) {
            return null;
        }
        return componentName.getPackageName();
    }
}
