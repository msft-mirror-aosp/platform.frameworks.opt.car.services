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
package android.content.res;

import android.annotation.SystemApi;

/**
 * Wrapper for {@link CompatibilityInfo.CompatScale} class.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class CompatScaleWrapper {
    private final float mScaleFactor;
    private final float mDensityScaleFactor;

    public CompatScaleWrapper(float scaleFactor, float densityScaleFactor) {
        mScaleFactor = scaleFactor;
        mDensityScaleFactor = densityScaleFactor;
    }

    /**
     * @return application scale factor
     */
    public float getScaleFactor() {
        return mScaleFactor;
    }

    /**
     * @return application's density scale factor
     */
    public float getDensityScaleFactor() {
        return mDensityScaleFactor;
    }

    @Override
    public String toString() {
        return "CompatScaleWrapper{ mScaleFactor=" + mScaleFactor + ", mDensityScaleFactor="
                + mDensityScaleFactor + "}";
    }
}
