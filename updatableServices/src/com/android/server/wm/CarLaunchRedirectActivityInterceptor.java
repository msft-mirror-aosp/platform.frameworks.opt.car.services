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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.Display;

import com.android.car.internal.dep.Trace;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * This class handles launching the application on a private display or a root task.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class CarLaunchRedirectActivityInterceptor implements
        CarActivityInterceptorUpdatable {
    // TODO(b/402624224): Enforce checks for multi user policy
    public static final String TAG =
            CarLaunchRedirectActivityInterceptor.class.getSimpleName();
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final int INVALID_DISPLAY = -1;
    private static final ActivityOptionsWrapper EMPTY_LAUNCH_OPTIONS_WRAPPER =
            ActivityOptionsWrapper.create(ActivityOptions.makeBasic());
    private static final String NAMESPACE_KEY = "com.android.car.app.launch_redirect";

    @VisibleForTesting
    static final String PERMISSION_ACCESS_PRIVATE_DISPLAY_ID =
            "android.car.permission.ACCESS_PRIVATE_DISPLAY_ID";
    /**
     * This key is defined by the applications that want to launch on a root task or a private
     * display.
     */
    @VisibleForTesting
    static final String LAUNCH_REDIRECT_ON_CONTAINER =
            NAMESPACE_KEY + ".launch_redirect_on_container";
    @VisibleForTesting
    static final String LAUNCH_ACTIVITY = NAMESPACE_KEY + ".launch_activity";
    @VisibleForTesting
    static final String LAUNCH_ACTIVITY_OPTIONS = NAMESPACE_KEY + ".launch_activity_options";
    @VisibleForTesting
    static final String LAUNCH_ACTIVITY_DISPLAY_ID = NAMESPACE_KEY + ".launch_activity_display_id";

    private final Object mLock = new Object();
    // K: Root task name, V: Root task token
    @GuardedBy("mLock")
    private final ArrayMap<String, IBinder> mRootTaskNameToRootTaskMap = new ArrayMap<>();
    @GuardedBy("mLock")
    private final Set<IBinder> mKnownRootTasks = new ArraySet<>();
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
     * Contains the names of the packages which are allowlisted to have launches redirected.
     */
    @Nullable
    private final Set<String> mAllowlist;

    public CarLaunchRedirectActivityInterceptor(@NonNull Context context) {
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
            Slogf.e(TAG,
                    "Couldn't update allowlist or read LaunchOnPrivateDisplay router activity.");
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
                LAUNCH_REDIRECT_ON_CONTAINER)) {
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
        String containerName = launchIntent.getExtras().getString(LAUNCH_REDIRECT_ON_CONTAINER);
        IBinder rootTaskToken = getLaunchRootTaskToken(containerName);
        if (rootTaskToken != null) {
            if (DBG) {
                Slogf.d(TAG, "Launch activity %s on root task %s", launchIntent.getComponent(),
                        containerName);
            }
            launchOptions.setLaunchRootTask(rootTaskToken);
            return ActivityInterceptResultWrapper.create(launchIntent, launchOptions.getOptions());
        }
        // Fall through to launch activity on a private display since root task not found

        // Check access private display id permission for a launch redirect on a private display
        if (!ensureAccessPrivateDisplayIdPermission(info)) {
            return null;
        }
        // Launch the router activity
        Intent intent = new Intent();
        intent.setComponent(mRouterActivity);

        int launchDisplayId = getLogicalDisplayId(containerName);
        if (DBG) {
            Slogf.d(TAG, "Launch activity %s on %d", launchIntent.getComponent(), launchDisplayId);
        }
        if (launchDisplayId == INVALID_DISPLAY) {
            return null;
        }

        launchIntent.removeExtra(LAUNCH_REDIRECT_ON_CONTAINER);
        intent.putExtra(LAUNCH_ACTIVITY, launchIntent);
        if (launchOptions.getOptions() != null) {
            intent.putExtra(LAUNCH_ACTIVITY_OPTIONS, launchOptions.getOptions().toBundle());
        }
        intent.putExtra(LAUNCH_ACTIVITY_DISPLAY_ID, launchDisplayId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityInterceptResultWrapper.create(intent, launchOptions.getOptions());
    }

    /**
     * Retrieves the root task token associated with the specified container name.
     *
     * <p>This method searches for a root task associated with the given {@code containerName}.
     * If a matching root task is found, its token is returned.
     *
     * <p>If no root task is found with the provided {@code containerName}, this method returns
     * {@code null}. A {@code null} return value indicates that no root task exists with the
     * specified container name.
     */
    private IBinder getLaunchRootTaskToken(String containerName) {
        synchronized (mLock) {
            int keyIndex = mRootTaskNameToRootTaskMap.indexOfKey(containerName);
            if (keyIndex >= 0) {
                return mRootTaskNameToRootTaskMap.valueAt(keyIndex);
            }
            return null;
        }
    }

    private boolean ensureAccessPrivateDisplayIdPermission(ActivityInterceptorInfoWrapper info) {
        int callingPid = info.getCallingPid();
        int callingUid = info.getCallingUid();
        boolean hasPermission = (mContext.checkPermission(PERMISSION_ACCESS_PRIVATE_DISPLAY_ID,
                callingPid, callingUid) == PERMISSION_GRANTED);
        if (!hasPermission) {
            Slogf.e(TAG, "Calling package (%s) doesn't have required permissions %s",
                    info.getCallingPackage(), PERMISSION_ACCESS_PRIVATE_DISPLAY_ID);
            return false;
        }
        return true;
    }

    /**
     * Updates {@code mRootTaskNameToRootTaskMap} with root task information that appeared.
     *
     * @param name          name of the root task.
     * @param rootTaskToken the binder token of the root task which appeared.
     */
    public void onRootTaskAppeared(String name, IBinder rootTaskToken) {
        try {
            beginTraceSection(
                    "CarLaunchRedirectActivityInterceptor-onRootTaskAppeared: " + rootTaskToken);
            synchronized (mLock) {
                if (rootTaskToken == null) {
                    Slogf.d(TAG, "The root task token is null.");
                    return;
                }
                mRootTaskNameToRootTaskMap.put(name, rootTaskToken);
                updateRootTaskInformationInKnownRootTasks(rootTaskToken);
            }
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Updates {@code mRootTaskNameToRootTaskMap} with root task information that vanished.
     *
     * @param name name of the root task which vanished.
     */
    public void onRootTaskVanished(String name) {
        try {
            beginTraceSection("CarLaunchRedirectActivityInterceptor-onRootTaskVanished: " + name);
            synchronized (mLock) {
                if (name.isEmpty()) {
                    Slogf.d(TAG, "The name of the root task is empty.");
                    return;
                }
                IBinder rootTaskToken = mRootTaskNameToRootTaskMap.get(name);
                mRootTaskNameToRootTaskMap.remove(name);
                mKnownRootTasks.remove(rootTaskToken);
            }
        } finally {
            Trace.endSection();
        }
    }

    @GuardedBy("mLock")
    private void updateRootTaskInformationInKnownRootTasks(IBinder rootTaskToken) {
        if (!mKnownRootTasks.contains(rootTaskToken)) {
            // Seeing the token for the first time, set the listener
            removeRootTaskTokenOnDeath(rootTaskToken);
            mKnownRootTasks.add(rootTaskToken);
        }
    }

    private void removeRootTaskTokenOnDeath(IBinder rootTaskToken) {
        try {
            rootTaskToken.linkToDeath(() -> removeRootTaskToken(rootTaskToken), /* flags= */ 0);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void removeRootTaskToken(IBinder rootTaskToken) {
        synchronized (mLock) {
            mKnownRootTasks.remove(rootTaskToken);
        }
    }

    private void beginTraceSection(String sectionName) {
        // Traces can only have max 127 characters
        Trace.beginSection(sectionName.substring(0, Math.min(sectionName.length(), 127)));
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
