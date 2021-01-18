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

import static android.app.admin.DevicePolicyManager.OPERATION_CLEAR_APPLICATION_USER_DATA;
import static android.app.admin.DevicePolicyManager.OPERATION_CREATE_AND_MANAGE_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_INSTALL_CA_CERT;
import static android.app.admin.DevicePolicyManager.OPERATION_INSTALL_KEY_PAIR;
import static android.app.admin.DevicePolicyManager.OPERATION_INSTALL_SYSTEM_UPDATE;
import static android.app.admin.DevicePolicyManager.OPERATION_LOCK_NOW;
import static android.app.admin.DevicePolicyManager.OPERATION_LOGOUT_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_REBOOT;
import static android.app.admin.DevicePolicyManager.OPERATION_REMOVE_ACTIVE_ADMIN;
import static android.app.admin.DevicePolicyManager.OPERATION_REMOVE_KEY_PAIR;
import static android.app.admin.DevicePolicyManager.OPERATION_REMOVE_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_REQUEST_BUGREPORT;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_ALWAYS_ON_VPN_PACKAGE;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_APPLICATION_HIDDEN;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_APPLICATION_RESTRICTIONS;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_CAMERA_DISABLED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_FACTORY_RESET_PROTECTION_POLICY;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_GLOBAL_PRIVATE_DNS;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_KEEP_UNINSTALLED_PACKAGES;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_KEYGUARD_DISABLED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_LOCK_TASK_FEATURES;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_LOCK_TASK_PACKAGES;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_LOGOUT_ENABLED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_MASTER_VOLUME_MUTED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_OVERRIDE_APNS_ENABLED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_PACKAGES_SUSPENDED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_PERMISSION_GRANT_STATE;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_PERMISSION_POLICY;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_RESTRICTIONS_PROVIDER;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_STATUS_BAR_DISABLED;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_SYSTEM_SETTING;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_SYSTEM_UPDATE_POLICY;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_TRUST_AGENT_CONFIGURATION;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_USER_CONTROL_DISABLED_PACKAGES;
import static android.app.admin.DevicePolicyManager.OPERATION_SET_USER_RESTRICTION;
import static android.app.admin.DevicePolicyManager.OPERATION_START_USER_IN_BACKGROUND;
import static android.app.admin.DevicePolicyManager.OPERATION_STOP_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_SWITCH_USER;
import static android.app.admin.DevicePolicyManager.OPERATION_UNINSTALL_CA_CERT;
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
                {OPERATION_CLEAR_APPLICATION_USER_DATA, false},
                {OPERATION_LOGOUT_USER, false},
                {OPERATION_REBOOT, false},
                {OPERATION_REQUEST_BUGREPORT, false},
                {OPERATION_SET_APPLICATION_HIDDEN, false},
                {OPERATION_SET_APPLICATION_RESTRICTIONS, false},
                {OPERATION_SET_KEYGUARD_DISABLED, false},
                {OPERATION_SET_PACKAGES_SUSPENDED, false},
                {OPERATION_SET_STATUS_BAR_DISABLED, false},
                {OPERATION_SET_SYSTEM_SETTING, false},
                {OPERATION_SWITCH_USER, false},

                // safe operations
                {OPERATION_WIPE_DATA, true}, // Safe because it will be delayed
                {OPERATION_CREATE_AND_MANAGE_USER, true},
                {OPERATION_INSTALL_CA_CERT, true},
                {OPERATION_INSTALL_KEY_PAIR, true},
                {OPERATION_INSTALL_SYSTEM_UPDATE, true},
                {OPERATION_LOCK_NOW, true},
                {OPERATION_REMOVE_ACTIVE_ADMIN, true},
                {OPERATION_REMOVE_KEY_PAIR, true},
                {OPERATION_REMOVE_USER, true},
                {OPERATION_SET_ALWAYS_ON_VPN_PACKAGE, true},
                {OPERATION_SET_CAMERA_DISABLED, true},
                {OPERATION_SET_FACTORY_RESET_PROTECTION_POLICY, true},
                {OPERATION_SET_GLOBAL_PRIVATE_DNS, true},
                {OPERATION_SET_KEEP_UNINSTALLED_PACKAGES, true},
                {OPERATION_SET_LOCK_TASK_FEATURES, true},
                {OPERATION_SET_LOCK_TASK_PACKAGES, true},
                {OPERATION_SET_LOGOUT_ENABLED, true},
                {OPERATION_SET_MASTER_VOLUME_MUTED, true},
                {OPERATION_SET_OVERRIDE_APNS_ENABLED, true},
                {OPERATION_SET_PERMISSION_GRANT_STATE, true},
                {OPERATION_SET_PERMISSION_POLICY, true},
                {OPERATION_SET_RESTRICTIONS_PROVIDER, true},
                {OPERATION_SET_SYSTEM_UPDATE_POLICY, true},
                {OPERATION_SET_TRUST_AGENT_CONFIGURATION, true},
                {OPERATION_SET_USER_CONTROL_DISABLED_PACKAGES, true},
                {OPERATION_SET_USER_RESTRICTION, true},
                {OPERATION_START_USER_IN_BACKGROUND, true},
                {OPERATION_STOP_USER, true},
                {OPERATION_UNINSTALL_CA_CERT, true}
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
