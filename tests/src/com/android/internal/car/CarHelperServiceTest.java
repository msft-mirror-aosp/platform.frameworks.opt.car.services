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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.car.user.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.support.test.runner.AndroidJUnit4;
import com.android.server.SystemService;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private CarServiceHelperService mCarServiceHelperService;
    @Mock
    private Context mMockContext;

    @Mock
    private Context mApplicationContext;

    @Mock
    private CarUserManagerHelper mCarUserManagerHelper;

    /**
     * Initialize objects and setup testing environment.
     */
    @Before
    public void setUpMocks() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(mApplicationContext).when(mMockContext).getApplicationContext();

        mCarServiceHelperService = new CarServiceHelperService(mMockContext);
        mCarServiceHelperService.setCarUserManagerHelper(mCarUserManagerHelper);
    }

    /**
     * Test that the {@link CarServiceHelperService} starts up a secondary admin user
     * upon first run.
     */
    @Test
    public void testStartsSecondaryAdminUserOnFirstRun() {
        UserInfo admin = mockAdmin(/* adminId= */ 10);

        doReturn(new ArrayList<>()).when(mCarUserManagerHelper).getAllUsers();
        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mCarUserManagerHelper).createNewAdminUser(CarUserManagerHelper.DEFAULT_FIRST_ADMIN_NAME);
        verify(mCarUserManagerHelper).switchToUser(admin);
    }

    /**
     * Test that the {@link CarServiceHelperService} updates last active user to the first
     * admin user on first run.
     */
    @Test
    public void testUpdateLastActiveUserOnFirstRun() {
        UserInfo admin = mockAdmin(/* adminId= */ 10);

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mCarUserManagerHelper)
            .setLastActiveUser(admin.id, /* skipGlobalSetting= */ false);
    }

    /**
     * Test that the {@link CarServiceHelperService} starts up the last active user on reboot.
     */
    @Test
    public void testStartsLastActiveUserOnReboot() {
        List<UserInfo> users = new ArrayList<>();

        int adminUserId = 10;
        UserInfo admin =
            new UserInfo(adminUserId, CarUserManagerHelper.DEFAULT_FIRST_ADMIN_NAME, UserInfo.FLAG_ADMIN);

        int secUserId = 11;
        UserInfo secUser =
            new UserInfo(secUserId, CarUserManagerHelper.DEFAULT_FIRST_ADMIN_NAME, UserInfo.FLAG_ADMIN);

        users.add(admin);
        users.add(secUser);

        doReturn(users).when(mCarUserManagerHelper).getAllUsers();
        doReturn(secUserId).when(mCarUserManagerHelper).getInitialUser();

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mCarUserManagerHelper).switchToUserId(secUserId);
    }

    private UserInfo mockAdmin(int adminId) {
        UserInfo admin =
                new UserInfo(adminId, CarUserManagerHelper.DEFAULT_FIRST_ADMIN_NAME, UserInfo.FLAG_ADMIN);
        doReturn(admin).when(mCarUserManagerHelper)
                .createNewAdminUser(CarUserManagerHelper.DEFAULT_FIRST_ADMIN_NAME);
        return admin;
    }
}
