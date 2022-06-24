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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.annotation.AddedIn;
import android.graphics.Rect;

/**
 * Wrapper of {@link com.android.server.wm.LaunchParamsController.LaunchParams}.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class LaunchParamsWrapper {
    /** Returned when the modifier does not want to influence the bounds calculation */
    @AddedIn(majorVersion = 33)
    public static int RESULT_SKIP = LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;
    /**
     * Returned when the modifier has changed the bounds and would like its results to be the
     * final bounds applied.
     */
    @AddedIn(majorVersion = 33)
    public static int RESULT_DONE = LaunchParamsController.LaunchParamsModifier.RESULT_DONE;
    /**
     * Returned when the modifier has changed the bounds but is okay with other modifiers
     * influencing the bounds.
     */
    @AddedIn(majorVersion = 33)
    public static int RESULT_CONTINUE = LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;

    private final LaunchParamsController.LaunchParams mLaunchParams;

    private LaunchParamsWrapper(LaunchParamsController.LaunchParams launchParams) {
        mLaunchParams = launchParams;
    }

    /** @hide */
    @AddedIn(majorVersion = 33)
    public static LaunchParamsWrapper create(
            @Nullable LaunchParamsController.LaunchParams launchParams) {
        if (launchParams == null) return null;
        return new LaunchParamsWrapper(launchParams);
    }

    /**
     * Gets the {@link TaskDisplayAreaWrapper} the {@link Task} would prefer to be on.
     */
    @AddedIn(majorVersion = 33)
    public TaskDisplayAreaWrapper getPreferredTaskDisplayArea() {
        return TaskDisplayAreaWrapper.create(mLaunchParams.mPreferredTaskDisplayArea);
    }

    /**
     * Sets the {@link TaskDisplayAreaWrapper} the {@link Task} would prefer to be on.
     */
    @AddedIn(majorVersion = 33)
    public void setPreferredTaskDisplayArea(TaskDisplayAreaWrapper tda) {
        mLaunchParams.mPreferredTaskDisplayArea = tda.getTaskDisplayArea();
    }

    /**
     * Gets the windowing mode to be in.
     */
    @AddedIn(majorVersion = 33)
    public int getWindowingMode() {
        return mLaunchParams.mWindowingMode;
    }

    /**
     * Sets the windowing mode to be in.
     */
    @AddedIn(majorVersion = 33)
    public void setWindowingMode(int windowingMode) {
        mLaunchParams.mWindowingMode = windowingMode;
    }

    /**
     *  Gets the bounds within the parent container.
     */
    @AddedIn(majorVersion = 33)
    public Rect getBounds() {
        return mLaunchParams.mBounds;
    }

    /**
     *  Sets the bounds within the parent container.
     */
    @AddedIn(majorVersion = 33)
    public void setBounds(Rect bounds) {
        mLaunchParams.mBounds.set(bounds);
    }

    @Override
    public String toString() {
        return "LaunchParams{" +
                "mPreferredTaskDisplayArea=" + mLaunchParams.mPreferredTaskDisplayArea +
                ", mWindowingMode=" + mLaunchParams.mWindowingMode +
                ", mBounds=" + mLaunchParams.mBounds.toString() + '}';
    }
}
