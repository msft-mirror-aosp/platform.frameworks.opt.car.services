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

import static android.app.admin.DevicePolicyManager.OPERATION_REBOOT;
import static android.app.admin.DevicePolicyManager.OPERATION_SWITCH_USER;
import static android.app.admin.DevicePolicyManager.operationToString;

import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager.DevicePolicyOperation;
import android.app.admin.DevicePolicySafetyChecker;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Integrates {@link android.app.admin.DevicePolicyManager} operations with car UX restrictions.
 */
final class CarDevicePolicySafetyChecker implements DevicePolicySafetyChecker {

    private static final String TAG = CarDevicePolicySafetyChecker.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int[] UNSAFE_OPERATIONS = new int[] {
            OPERATION_SWITCH_USER,
            OPERATION_REBOOT
    };

    private final AtomicBoolean mSafe = new AtomicBoolean(true);

    @Override
    public boolean isDevicePolicyOperationSafe(@DevicePolicyOperation int operation) {
        boolean safe = true;
        boolean globalSafe = mSafe.get();
        if (!globalSafe) {
            for (int unsafeOperation : UNSAFE_OPERATIONS) {
                if (unsafeOperation == operation) {
                    safe = false;
                    break;
                }
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "isDevicePolicyOperationSafe(" + operationToString(operation)
                    + "): " + safe + " (mSafe=" + globalSafe + ")");
        }
        return safe;
    }

    // TODO(b/172376923): override getUnsafeStateException to show error message explaining how to
    // wrap it under CarDevicePolicyManager

    void setSafe(boolean safe) {
        Slog.i(TAG, "Setting safe to " + safe);
        mSafe.set(safe);
    }

    void dump(@NonNull IndentingPrintWriter pw) {
        pw.printf("Safe to run device policy operations: %b\n", mSafe.get());
        pw.printf("Unsafe operations: %s\n", Arrays.stream(UNSAFE_OPERATIONS)
                .mapToObj(o -> operationToString(o)).collect(Collectors.toList()));
    }
}
