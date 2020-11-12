/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.internal.car;

import static android.app.admin.DevicePolicyManager.OPERATION_LOCK_NOW;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public final class CarDevicePolicySafetyCheckerTest {

    private final CarDevicePolicySafetyChecker mChecker = new CarDevicePolicySafetyChecker();

    // TODO(b/172376923): test for all operations / use parameterized test

    @Test
    public void testSafe() throws Exception {
        mChecker.setSafe(true);
        assertThat(mChecker.isDevicePolicyOperationSafe(OPERATION_LOCK_NOW)).isTrue();
    }

    @Test
    public void testUnsafe() throws Exception {
        mChecker.setSafe(false);
        assertThat(mChecker.isDevicePolicyOperationSafe(OPERATION_LOCK_NOW)).isFalse();
    }
}
