/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.UserHandle;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class CarLaunchParamsModifierTest {

    private MockitoSession mMockingSession;

    private CarLaunchParamsModifier mModifier;

    @Mock
    private Context mContext;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private ActivityTaskManagerService mActivityTaskManagerService;
    @Mock
    private ActivityStackSupervisor mActivityStackSupervisor;
    @Mock
    private LaunchParamsController mLaunchParamsController;

    @Mock
    private Display mDisplay0ForDriver;
    @Mock
    private Display mDisplay1Private;
    @Mock
    private Display mDisplay10ForPassenger;
    @Mock
    private Display mDisplay11ForPassenger;

    // All mocks from here before CarLaunchParamsModifier are arguments for
    // LaunchParamsModifier.onCalculate() call.
    @Mock
    private TaskRecord mTaskRecord;
    @Mock
    private ActivityInfo.WindowLayout mWindowLayout;
    @Mock
    private ActivityRecord mActivityRecordActivity;
    @Mock
    private ActivityRecord mActivityRecordSource;
    @Mock
    private ActivityOptions mActivityOptions;
    @Mock
    private LaunchParamsController.LaunchParams mCurrentParams;
    @Mock
    private LaunchParamsController.LaunchParams mOutParams;

    private void mockDisplay(Display display, int displayId, int flags, int type) {
        when(mDisplayManager.getDisplay(displayId)).thenReturn(display);
        when(display.getDisplayId()).thenReturn(displayId);
        when(display.getFlags()).thenReturn(flags);
        when(display.getType()).thenReturn(type);
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(ActivityTaskManager.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        when(mContext.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);
        doReturn(mActivityTaskManagerService).when(() -> ActivityTaskManager.getService());
        mActivityTaskManagerService.mStackSupervisor = mActivityStackSupervisor;
        when(mActivityStackSupervisor.getLaunchParamsController()).thenReturn(
                mLaunchParamsController);
        mockDisplay(mDisplay0ForDriver,0, 0, 0);
        mockDisplay(mDisplay10ForPassenger,10, 0, 0);
        mockDisplay(mDisplay11ForPassenger,11, 0, 0);
        mockDisplay(mDisplay1Private,1, Display.FLAG_PRIVATE, 0);

        mModifier = new CarLaunchParamsModifier(mContext);
        mModifier.init();
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    private void assertDisplayIsAllowed(int userId, Display display) {
        mTaskRecord.userId = userId;
        mCurrentParams.mPreferredDisplayId = display.getDisplayId();
        assertThat(mModifier.onCalculate(mTaskRecord, mWindowLayout, mActivityRecordActivity,
                mActivityRecordSource, mActivityOptions, 0, mCurrentParams, mOutParams)).
                isEqualTo(LaunchParamsController.LaunchParamsModifier.RESULT_SKIP);
    }

    private void assertDisplayIsReassigned(int userId, Display displayRequested,
            Display displayAssigned) {
        assertThat(displayRequested.getDisplayId()).isNotEqualTo(displayAssigned.getDisplayId());
        mTaskRecord.userId = userId;
        mCurrentParams.mPreferredDisplayId = displayRequested.getDisplayId();
        assertThat(mModifier.onCalculate(mTaskRecord, mWindowLayout, mActivityRecordActivity,
                mActivityRecordSource, mActivityOptions, 0, mCurrentParams, mOutParams)).
                isEqualTo(LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE);
        assertThat(mOutParams.mPreferredDisplayId).isEqualTo(displayAssigned.getDisplayId());
    }

    @Test
    public void testNoPolicySet() {
        final int randomUserId = 1000;
        // policy not set set, so do not apply any enforcement.
        assertDisplayIsAllowed(randomUserId, mDisplay0ForDriver);
        assertDisplayIsAllowed(randomUserId, mDisplay10ForPassenger);

        assertDisplayIsAllowed(UserHandle.USER_SYSTEM, mDisplay0ForDriver);
        assertDisplayIsAllowed(UserHandle.USER_SYSTEM, mDisplay10ForPassenger);

        assertDisplayIsAllowed(ActivityManager.getCurrentUser(), mDisplay0ForDriver);
        assertDisplayIsAllowed(ActivityManager.getCurrentUser(), mDisplay10ForPassenger);
    }

    private void assertAllDisplaysAllowedForUser(int userId) {
        assertDisplayIsAllowed(userId, mDisplay0ForDriver);
        assertDisplayIsAllowed(userId, mDisplay10ForPassenger);
        assertDisplayIsAllowed(userId, mDisplay11ForPassenger);
    }

    @Test
    public void testAllowAllForDriverDuringBoot() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay10ForPassenger.getDisplayId()});

        // USER_SYSTEM should be allowed until switch to other user.
        assertAllDisplaysAllowedForUser(UserHandle.USER_SYSTEM);
    }

    @Test
    public void testAllowAllForDriverAfterUserSwitching() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay10ForPassenger.getDisplayId()});

        final int driver1 = 10;
        mModifier.handleCurrentUserSwitching(driver1);
        assertAllDisplaysAllowedForUser(driver1);

        final int driver2 = 10;
        mModifier.handleCurrentUserSwitching(driver2);
        assertAllDisplaysAllowedForUser(driver2);
    }

    @Test
    public void testPassengerAllowed() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay11ForPassenger.getDisplayId()});

        final int passengerUserId = 100;
        mModifier.setDisplayWhitelistForUser(passengerUserId,
                new int[]{mDisplay10ForPassenger.getDisplayId()});

        assertDisplayIsAllowed(passengerUserId, mDisplay10ForPassenger);
    }

    @Test
    public void testPassengerNotAllowed() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay11ForPassenger.getDisplayId()});

        final int passengerUserId = 100;
        mModifier.setDisplayWhitelistForUser(
                passengerUserId, new int[]{mDisplay10ForPassenger.getDisplayId()});

        assertDisplayIsReassigned(passengerUserId, mDisplay0ForDriver, mDisplay10ForPassenger);
        assertDisplayIsReassigned(passengerUserId, mDisplay11ForPassenger, mDisplay10ForPassenger);
    }

    @Test
    public void testPassengerDisplayRemoved() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay11ForPassenger.getDisplayId()});

        final int passengerUserId = 100;
        mModifier.setDisplayWhitelistForUser(passengerUserId,
                new int[]{mDisplay10ForPassenger.getDisplayId(),
                        mDisplay11ForPassenger.getDisplayId()});

        assertDisplayIsAllowed(passengerUserId, mDisplay10ForPassenger);
        assertDisplayIsAllowed(passengerUserId, mDisplay11ForPassenger);

        mModifier.mDisplayListener.onDisplayRemoved(mDisplay11ForPassenger.getDisplayId());

        assertDisplayIsAllowed(passengerUserId, mDisplay10ForPassenger);
        assertDisplayIsReassigned(passengerUserId, mDisplay11ForPassenger, mDisplay10ForPassenger);
    }

    @Test
    public void testIgnorePrivateDisplay() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay11ForPassenger.getDisplayId()});

        final int passengerUserId = 100;
        mModifier.setDisplayWhitelistForUser(passengerUserId,
                new int[]{mDisplay10ForPassenger.getDisplayId(),
                        mDisplay10ForPassenger.getDisplayId()});

        assertDisplayIsAllowed(passengerUserId, mDisplay1Private);
    }

    @Test
    public void testDriverPassengerSwap() {
        mModifier.setPassengerDisplays(new int[]{mDisplay10ForPassenger.getDisplayId(),
                mDisplay11ForPassenger.getDisplayId()});

        final int wasDriver = 10;
        final int wasPassenger = 11;
        mModifier.handleCurrentUserSwitching(wasDriver);
        mModifier.setDisplayWhitelistForUser(wasPassenger,
                new int[]{mDisplay10ForPassenger.getDisplayId(),
                        mDisplay11ForPassenger.getDisplayId()});

        assertDisplayIsAllowed(wasDriver, mDisplay0ForDriver);
        assertDisplayIsAllowed(wasDriver, mDisplay10ForPassenger);
        assertDisplayIsAllowed(wasDriver, mDisplay11ForPassenger);
        assertDisplayIsReassigned(wasPassenger, mDisplay0ForDriver, mDisplay10ForPassenger);
        assertDisplayIsAllowed(wasPassenger, mDisplay10ForPassenger);
        assertDisplayIsAllowed(wasPassenger, mDisplay11ForPassenger);

        final int driver = wasPassenger;
        final int passenger = wasDriver;
        mModifier.handleCurrentUserSwitching(driver);
        mModifier.setDisplayWhitelistForUser(passenger,
                new int[]{mDisplay10ForPassenger.getDisplayId(),
                        mDisplay11ForPassenger.getDisplayId()});

        assertDisplayIsAllowed(driver, mDisplay0ForDriver);
        assertDisplayIsAllowed(driver, mDisplay10ForPassenger);
        assertDisplayIsAllowed(driver, mDisplay11ForPassenger);
        assertDisplayIsReassigned(passenger, mDisplay0ForDriver, mDisplay10ForPassenger);
        assertDisplayIsAllowed(passenger, mDisplay10ForPassenger);
        assertDisplayIsAllowed(passenger, mDisplay11ForPassenger);
    }
}
