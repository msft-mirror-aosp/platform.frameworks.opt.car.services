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

/**
 * Helper class to access the package private members of {@link WindowProcessController}
 * @hide
 */
public final class WindowProcessControllerHelper {
    /**
     * @return {@code uid} of the given {@link WindowProcessController}
     */
    public static int getUid(WindowProcessController wpc) {
        return wpc.mUid;
    }

    private WindowProcessControllerHelper() {
        throw new UnsupportedOperationException("contains only static members");
    }
}
