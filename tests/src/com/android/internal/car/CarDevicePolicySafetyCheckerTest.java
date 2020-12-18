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

import static android.app.admin.DevicePolicyManager.OPERATION_CREATE_AND_MANAGE_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_LOCK_NOW;
import static android.app.admin.DevicePolicyManager.OPERATION_LOGOUT_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_REBOOT;
import static android.app.admin.DevicePolicyManager.OPERATION_REMOVE_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_USER_RESTRICTION;
import static android.app.admin.DevicePolicyManager.OPERATION_START_USER_IN_BACKGROUND;
import static android.app.admin.DevicePolicyManager.OPERATION_STOP_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_SWITCH_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_WIPE_DATA;
import static android.app.admin.DevicePolicyManager.operationToString;

import static com.google.common.truth.Truth.assertWithMessage;

import android.app.admin.DevicePolicyManager.DevicePolicyOperation;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public final class CarDevicePolicySafetyCheckerTest {

    private final CarDevicePolicySafetyChecker mChecker = new CarDevicePolicySafetyChecker();

    private final @DevicePolicyOperation int mOperation;
    private final boolean mSafe;

    @Parameterized.Parameters
    public static Collection<?> packageManagers() {
        return Arrays.asList(new Object[][] {
                // unsafe operations
                {OPERATION_LOGOUT_USER, false},
                {OPERATION_REBOOT, false},
                {OPERATION_SWITCH_USER, false},
                {OPERATION_WIPE_DATA, false},

                // safe operations
                {OPERATION_CREATE_AND_MANAGE_USER, true},
                {OPERATION_LOCK_NOW, true},
                {OPERATION_REMOVE_USER, true},
                {OPERATION_SET_USER_RESTRICTION, true},
                {OPERATION_START_USER_IN_BACKGROUND, true},
                {OPERATION_STOP_USER, true}
        });
    }

    public CarDevicePolicySafetyCheckerTest(@DevicePolicyOperation int operation, boolean safe) {
        mOperation = operation;
        mSafe = safe;
    }

    @Test
    public void testSafe() throws Exception {
        mChecker.setSafe(true);

        assertWithMessage("%s should be safe when car is safe", operationToString(mOperation))
                .that(mChecker.isDevicePolicyOperationSafe(mOperation)).isTrue();
    }

    @Test
    public void testUnsafe() throws Exception {
        mChecker.setSafe(false);

        if (mSafe) {
            assertWithMessage("%s should be safe EVEN when car isn't",
                    operationToString(mOperation))
                            .that(mChecker.isDevicePolicyOperationSafe(mOperation)).isTrue();
        } else {
            assertWithMessage("%s should NOT be safe even when car isn't",
                    operationToString(mOperation))
                            .that(mChecker.isDevicePolicyOperationSafe(mOperation)).isFalse();
        }
    }
}
