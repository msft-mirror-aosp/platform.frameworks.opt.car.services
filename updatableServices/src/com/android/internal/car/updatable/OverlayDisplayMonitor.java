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

package com.android.internal.car.updatable;

import android.car.builtin.util.Slogf;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.SparseIntArray;

import com.android.internal.car.CarServiceHelperInterface;

/**
 * OverlayDisplay is used to test the multiple display environment in CTS. And in MUMD, every
 * public display should be assigned to a user, or it throws an exception. This class monitors the
 * change of Display and assign it to the driver if the newly added display is a OverlayDisplay.
 *
 * TODO: b/340249048 - Consider how to assign OverlayDisplay to passengers.
 */
public final class OverlayDisplayMonitor {
    private static final String TAG = OverlayDisplayMonitor.class.getSimpleName();
    /** Comes from {@link android.os.UserHandle#USER_NULL}. */
    private static final int USER_NULL = -10000;

    private final DisplayManager mDisplayManager;
    private final Handler mHandler;
    private final CarServiceHelperInterface mHelper;
    // Key: displayId, Value: userId
    private final SparseIntArray mOverlayDisplays = new SparseIntArray();
    private int mCurrentUserId;

    public OverlayDisplayMonitor(DisplayManager displayManager, Handler handler,
            CarServiceHelperInterface helper) {
        mDisplayManager = displayManager;
        mHandler = handler;
        mHelper = helper;
    }

    /** Initializes the class */
    public void init() {
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
    }

    /** Notifies to the class that the current user is switching */
    public void handleCurrentUserSwitching(int userTo) {
        mCurrentUserId = userTo;
    }

    DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            if (mHelper.isOverlayDisplay(displayId)) {
                if (!mHelper.assignUserToExtraDisplay(mCurrentUserId, displayId)) {
                    Slogf.e(TAG, "Failed to assign OverlayDisplay=%d to User=%d",
                            displayId, mCurrentUserId);
                    return;
                }
                mOverlayDisplays.put(displayId, mCurrentUserId);
                Slogf.i(TAG, "Assigned OverlayDisplay=%d to User=%d", displayId, mCurrentUserId);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            int userId = mOverlayDisplays.get(displayId, USER_NULL);
            if (userId != USER_NULL) {
                mOverlayDisplays.delete(displayId);
                boolean success = mHelper.unassignUserFromExtraDisplay(userId, displayId);
                Slogf.i(TAG, "Unassign OverlayDisplay=%d from User=%d: %b",
                        displayId, userId, success);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            // No-op.
        }
    };
}
