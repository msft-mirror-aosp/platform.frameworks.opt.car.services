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

import android.car.builtin.util.Slogf;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.car.internal.dep.Trace;
import com.android.internal.annotations.GuardedBy;

import java.util.Set;

/**
 * A repository to store and manage task stack information in car service helpoer service.
 *
 * <p>This class is thread-safe.
 */
public final class CarServiceHelperTaskStackRepository {
    private static final String TAG = CarServiceHelperTaskStackRepository.class.getSimpleName();

    private final Object mLock = new Object();
    // K: Root task name, V: Root task token
    @GuardedBy("mLock")
    private final ArrayMap<String, IBinder> mRootTaskNameToTokenMap = new ArrayMap<>();
    // K: Root task token, V: Root task name
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, String> mRootTaskTokenToNameMap = new ArrayMap<>();
    @GuardedBy("mLock")
    private final Set<IBinder> mKnownRootTasks = new ArraySet<>();

    /**
     * Constructs a new instance of the repository.
     */
    public CarServiceHelperTaskStackRepository() {
    }

    /**
     * Updates maps with root task information that got created.
     *
     * @param name          name of the root task.
     * @param token the binder token of the root task which was created.
     */
    public void onRootTaskCreated(String name, IBinder token) {
        try {
            beginTraceSection("TaskStackRepository-onRootTaskCreated: " + token);
            synchronized (mLock) {
                if (token == null) {
                    Slogf.d(TAG, "The root task token is null for created event.");
                    return;
                }
                mRootTaskNameToTokenMap.put(name, token);
                mRootTaskTokenToNameMap.put(token, name);
                updateRootTaskInformationInKnownRootTasks(token);
            }
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Updates maps with root task information that appeared.
     *
     * @param name          name of the root task.
     * @param rootTaskToken the binder token of the root task which appeared.
     */
    public void onRootTaskAppeared(String name, IBinder rootTaskToken) {
        try {
            beginTraceSection("TaskStackRepository-onRootTaskAppeared: " + rootTaskToken);
            synchronized (mLock) {
                if (rootTaskToken == null) {
                    Slogf.d(TAG, "The root task token is null for appeared event.");
                    return;
                }
                mRootTaskNameToTokenMap.put(name, rootTaskToken);
                mRootTaskTokenToNameMap.put(rootTaskToken, name);
                updateRootTaskInformationInKnownRootTasks(rootTaskToken);
            }
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Updates maps with root task information that vanished.
     *
     * @param name name of the root task which vanished.
     */
    public void onRootTaskVanished(String name) {
        try {
            beginTraceSection("TaskStackRepository-onRootTaskVanished: " + name);
            synchronized (mLock) {
                if (name.isEmpty()) {
                    Slogf.d(TAG, "The name of the root task is empty.");
                    return;
                }
                IBinder rootTaskToken = mRootTaskNameToTokenMap.remove(name);
                if (rootTaskToken != null) {
                    mRootTaskTokenToNameMap.remove(rootTaskToken);
                    mKnownRootTasks.remove(rootTaskToken);
                }
            }
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Retrieves the root task token associated with the specified container name.
     *
     * <p>This method searches for a root task associated with the given {@code containerName}.
     * If a matching root task is found, its token is returned.
     *
     * <p>If no root task is found with the provided {@code containerName}, this method returns
     * {@code null}.
     */
    public IBinder getLaunchRootTaskToken(String containerName) {
        synchronized (mLock) {
            return mRootTaskNameToTokenMap.get(containerName);
        }
    }

    /**
     * Retrieves the root task name associated with the specified token.
     *
     * <p>If no root task is found with the provided {@code token}, this method returns
     * {@code null}.
     */
    public String getRootTaskName(IBinder token) {
        synchronized (mLock) {
            return mRootTaskTokenToNameMap.get(token);
        }
    }

    @GuardedBy("mLock")
    private void updateRootTaskInformationInKnownRootTasks(IBinder rootTaskToken) {
        if (!mKnownRootTasks.contains(rootTaskToken)) {
            // Seeing the token for the first time, set the listener
            linkToDeath(rootTaskToken);
            mKnownRootTasks.add(rootTaskToken);
        }
    }

    private void linkToDeath(IBinder rootTaskToken) {
        try {
            rootTaskToken.linkToDeath(() -> removeRootTask(rootTaskToken), /* flags= */ 0);
        } catch (RemoteException e) {
            // Should not happen.
            Slogf.e(TAG, "Failed to linkToDeath", e);
            removeRootTask(rootTaskToken);
        }
    }

    private void removeRootTask(IBinder rootTaskToken) {
        synchronized (mLock) {
            mKnownRootTasks.remove(rootTaskToken);
            String name = mRootTaskTokenToNameMap.remove(rootTaskToken);
            if (name != null) {
                mRootTaskNameToTokenMap.remove(name);
            }
        }
    }

    private void beginTraceSection(String sectionName) {
        // Traces can only have max 127 characters
        Trace.beginSection(sectionName.substring(0, Math.min(sectionName.length(), 127)));
    }
}
