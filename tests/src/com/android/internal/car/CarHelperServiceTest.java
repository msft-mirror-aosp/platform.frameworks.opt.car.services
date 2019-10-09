/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.util.UserIcons;
import com.android.server.SystemService;
import com.android.server.wm.CarLaunchParamsModifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

/**
 * This class contains unit tests for the {@link CarServiceHelperService}.
 *
 * The following mocks are used:
 * <ol>
 *   <li> {@link Context} provides system services and resources.
 *   <li> {@link CarUserManagerHelper} provides user info and actions.
 * <ol/>
 */
@RunWith(AndroidJUnit4.class)
public class CarHelperServiceTest {
    private static final String DEFAULT_NAME = "Driver";
    private static final int ADMIN_USER_ID = 10;
    private CarServiceHelperService mCarServiceHelperService;
    StaticMockitoSession mStaticMockitoSession;

    @Mock private Context mMockContext;
    @Mock private Context mApplicationContext;
    @Mock private CarUserManagerHelper mCarUserManagerHelper;
    @Mock private UserManager mUserManager;
    @Mock private CarLaunchParamsModifier mCarLaunchParamsModifier;
    private IActivityManager mActivityManager;

    /**
     * Initialize objects and setup testing environment.
     */
    @Before
    public void setUpMocks() {
        mStaticMockitoSession = mockitoSession()
                .initMocks(this)
                .mockStatic(UserIcons.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        doReturn(mApplicationContext).when(mMockContext).getApplicationContext();

        UserInfo adminUser = new UserInfo(ADMIN_USER_ID, DEFAULT_NAME, UserInfo.FLAG_ADMIN);
        doReturn(adminUser).when(mUserManager).createUser(DEFAULT_NAME, UserInfo.FLAG_ADMIN);

        doReturn(null).when(
                () -> UserIcons.getDefaultUserIcon(any(Resources.class), anyInt(), anyBoolean()));

        mActivityManager = ActivityManager.getService();
        spyOn(mActivityManager);
        mCarServiceHelperService =
                new CarServiceHelperService(
                        mMockContext,
                        mCarUserManagerHelper,
                        mUserManager,
                        mActivityManager,
                        mCarLaunchParamsModifier,
                        DEFAULT_NAME);
    }

    @After
    public void tearDown() {
        mStaticMockitoSession.finishMocking();
    }

    /**
     * Test that the {@link CarServiceHelperService} starts up a secondary admin user
     * upon first run.
     */
    @Test
    public void testStartsSecondaryAdminUserOnFirstRun() throws Exception {
        doReturn(new ArrayList<>()).when(mUserManager).getUsers(anyBoolean());
        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mUserManager).createUser(anyString(), eq(UserInfo.FLAG_ADMIN));
        verify(mActivityManager).startUserInForegroundWithListener(ADMIN_USER_ID, null);
    }

    /**
     * Test that the {@link CarServiceHelperService} updates last active user to the first
     * admin user on first run.
     */
    @Test
    public void testUpdateLastActiveUserOnFirstRun() {
        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mCarUserManagerHelper).setLastActiveUser(ADMIN_USER_ID);
    }

    /**
     * Test that the {@link CarServiceHelperService} starts up the last active user on reboot.
     */
    @Test
    public void testStartsLastActiveUserOnReboot() throws Exception {
        List<UserInfo> users = new ArrayList<>();

        int adminUserId = ADMIN_USER_ID;
        UserInfo admin =
            new UserInfo(adminUserId, DEFAULT_NAME, UserInfo.FLAG_ADMIN);

        int secUserId = ADMIN_USER_ID + 1;
        UserInfo secUser =
            new UserInfo(secUserId, DEFAULT_NAME, UserInfo.FLAG_ADMIN);

        users.add(admin);
        users.add(secUser);

        doReturn(users).when(mUserManager).getUsers(anyBoolean());
        doReturn(secUserId).when(mCarUserManagerHelper).getInitialUser();

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mActivityManager).startUserInForegroundWithListener(secUserId, null);
    }
}
