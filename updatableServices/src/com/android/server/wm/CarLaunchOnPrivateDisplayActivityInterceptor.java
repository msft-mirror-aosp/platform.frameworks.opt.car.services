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

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityOptions;
import android.car.builtin.util.Slogf;
import android.car.builtin.view.DisplayHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * This class handles launching the application on a private display.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class CarLaunchOnPrivateDisplayActivityInterceptor implements
        CarActivityInterceptorUpdatable {
    public static final String TAG =
            CarLaunchOnPrivateDisplayActivityInterceptor.class.getSimpleName();
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final int INVALID_DISPLAY = -1;
    private static final ActivityOptionsWrapper EMPTY_LAUNCH_OPTIONS_WRAPPER =
            ActivityOptionsWrapper.create(ActivityOptions.makeBasic());
    private static final String NAMESPACE_KEY = "com.android.car.app.private_display";

    @VisibleForTesting
    static final String PERMISSION_ACCESS_PRIVATE_DISPLAY_ID =
            "android.car.permission.ACCESS_PRIVATE_DISPLAY_ID";
    /**
     * This key is defined by the applications that want to launch on the private display.
     */
    @VisibleForTesting
    static final String LAUNCH_ON_PRIVATE_DISPLAY = NAMESPACE_KEY + ".launch_on_private_display";
    @VisibleForTesting
    static final String LAUNCH_ACTIVITY = NAMESPACE_KEY + ".launch_activity";
    @VisibleForTesting
    static final String LAUNCH_ACTIVITY_OPTIONS = NAMESPACE_KEY + ".launch_activity_options";
    @VisibleForTesting
    static final String LAUNCH_ACTIVITY_DISPLAY_ID = NAMESPACE_KEY + ".launch_activity_display_id";

    @NonNull
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    /**
     * Use the router activity to launch the intended activity on the desired display. Since this
     * activity would reside in the SystemUI process, it will have the required permission to launch
     * an activity on a private display.
     */
    @Nullable
    private final ComponentName mRouterActivity;
    /**
     * Contains the names of the packages which are allowlisted to open on the private display.
     */
    @Nullable
    private final Set<String> mAllowlist;

    public CarLaunchOnPrivateDisplayActivityInterceptor(@NonNull Context context) {
        mContext = context;
        mDisplayManager = context.getSystemService(DisplayManager.class);
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) {
            mRouterActivity = null;
            mAllowlist = null;
            // This happens during tests where mock context is used.
            return;
        }
        Resources r = context.getResources();
        if (r == null) {
            mRouterActivity = null;
            mAllowlist = null;
            // This happens during tests where mock context is used.
            Slogf.e(TAG, "Couldn't read LaunchOnPrivateDisplay router activity.");
            return;
        }
        mAllowlist = readAllowlistFromConfig(r);
        mRouterActivity = readRouterActivityFromConfig(r, packageManager);
    }

    @Nullable
    @Override
    public ActivityInterceptResultWrapper onInterceptActivityLaunch(
            ActivityInterceptorInfoWrapper info) {
        if (mRouterActivity == null) {
            return null;
        }
        Intent launchIntent = info.getIntent();
        if (launchIntent == null || launchIntent.getComponent() == null) {
            return null;
        }
        if (launchIntent.getExtras() == null || !launchIntent.getExtras().containsKey(
                LAUNCH_ON_PRIVATE_DISPLAY)) {
            return null;
        }
        int callingPid = info.getCallingPid();
        int callingUid = info.getCallingUid();
        boolean hasPermission = (mContext.checkPermission(PERMISSION_ACCESS_PRIVATE_DISPLAY_ID,
                callingPid, callingUid) == PERMISSION_GRANTED);
        if (!hasPermission) {
            Slogf.e(TAG, "Calling package (%s) doesn't have required permissions %s",
                    info.getCallingPackage(), PERMISSION_ACCESS_PRIVATE_DISPLAY_ID);
            return null;
        }
        if (!isAllowlistedApplication(launchIntent.getComponent().getPackageName())) {
            if (DBG) {
                // TODO(b/343734299): Implement a dumpsys for maintaining the 5-10 most recent
                //  such launches.
                Slogf.e(TAG, "Activity is not allowlisted");
            }
            return null;
        } else if (DBG) {
            Slogf.d(TAG, "Activity %s allowlisted", launchIntent.getComponent());
        }
        ActivityOptionsWrapper launchOptions = info.getCheckedOptions();
        if (launchOptions == null) {
            launchOptions = EMPTY_LAUNCH_OPTIONS_WRAPPER;
        }
        // Launch the router activity
        Intent intent = new Intent();
        intent.setComponent(mRouterActivity);

        String uniqueDisplayName = launchIntent.getExtras().getString(LAUNCH_ON_PRIVATE_DISPLAY);
        int launchDisplayId = getLogicalDisplayId(uniqueDisplayName);
        if (DBG) {
            Slogf.d(TAG, "Launch activity %s on %d", launchIntent.getComponent(), launchDisplayId);
        }
        if (launchDisplayId == INVALID_DISPLAY) {
            return null;
        }

        launchIntent.removeExtra(LAUNCH_ON_PRIVATE_DISPLAY);
        intent.putExtra(LAUNCH_ACTIVITY, launchIntent);
        if (launchOptions.getOptions() != null) {
            intent.putExtra(LAUNCH_ACTIVITY_OPTIONS, launchOptions.getOptions().toBundle());
        }
        intent.putExtra(LAUNCH_ACTIVITY_DISPLAY_ID, launchDisplayId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityInterceptResultWrapper.create(intent, launchOptions.getOptions());
    }

    private boolean isAllowlistedApplication(String packageName) {
        if (mAllowlist == null) {
            return false;
        }
        return mAllowlist.contains(packageName);
    }

    private Set<String> readAllowlistFromConfig(Resources r) {
        int id = r.getIdentifier("config_defaultAllowlistLaunchOnPrivateDisplayPackages", "array",
                "android");
        if (id != 0) {
            String[] allowlistedPackagesFromConfig = r.getStringArray(id);
            Set<String> allowlistedPackages = new HashSet<>();
            for (int i = 0; i < allowlistedPackagesFromConfig.length; i++) {
                allowlistedPackages.add(allowlistedPackagesFromConfig[i]);
            }
            return allowlistedPackages;
        } else if (DBG) {
            Slogf.d(TAG, "Allowlist config not present.");
        }
        return null;
    }

    private ComponentName readRouterActivityFromConfig(Resources r, PackageManager packageManager) {
        int id = r.getIdentifier("config_defaultLaunchOnPrivateDisplayRouterActivity", "string",
                "android");
        if (id != 0) {
            ComponentName routerActivity = ComponentName.unflattenFromString(r.getString(id));
            if (routerActivity == null) {
                Slogf.e(TAG, "Couldn't read LaunchOnPrivateDisplay router activity.");
                return null;
            }
            Intent intent = new Intent();
            intent.setComponent(routerActivity);
            ResolveInfo ri = packageManager.resolveActivity(intent,
                    PackageManager.ResolveInfoFlags.of(MATCH_SYSTEM_ONLY));
            if (ri == null) {
                Slogf.e(TAG, "Couldn't resolve LaunchOnPrivateDisplay router activity. %s",
                        routerActivity);
                return null;
            }
            if (DBG) {
                Slogf.d(TAG, "router Activity is %s", routerActivity);
            }
            return routerActivity;
        }
        return null;
    }

    private int getLogicalDisplayId(String uniqueDisplayName) {
        if (mDisplayManager == null) {
            Slogf.e(TAG, "DisplayManager is null");
            return INVALID_DISPLAY;
        }
        for (Display display : mDisplayManager.getDisplays()) {
            String displayName = DisplayHelper.getUniqueId(display);
            if (Objects.equals(displayName, uniqueDisplayName)) {
                return display.getDisplayId();
            }
        }
        return INVALID_DISPLAY;
    }
}
