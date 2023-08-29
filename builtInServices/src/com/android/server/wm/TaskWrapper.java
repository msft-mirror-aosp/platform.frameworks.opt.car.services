/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.IBinder;


/**
 * Wrapper of {@link Task}.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class TaskWrapper {
    private final Task mTask;

    private TaskWrapper(Task task) {
        mTask = task;
    }

    /** @hide */
    public static TaskWrapper create(@Nullable Task task) {
        if (task == null) return null;
        return new TaskWrapper(task);
    }

    /** Creates an instance of {@link TaskWrapper} based on the task's remote {@code token}. */
    public static TaskWrapper createFromToken(@NonNull IBinder token) {
        return new TaskWrapper((Task) WindowContainer.fromBinder(token));
    }

    /**
     * Gets the {@code userId} of this {@link Task} is created for
     */
    public int getUserId() {
        return mTask.mUserId;
    }

    /**
     * Gets the root {@link TaskWrapper} of the this.
     */
    public TaskWrapper getRootTask() {
        return create(mTask.getRootTask());
    }

    /**
     * Gets the {@link TaskDisplayAreaWrapper} this {@link Task} is on.
     */
    public TaskDisplayAreaWrapper getTaskDisplayArea() {
        return TaskDisplayAreaWrapper.create(mTask.getTaskDisplayArea());
    }

    @Override
    public String toString() {
        return mTask.toString();
    }
}
