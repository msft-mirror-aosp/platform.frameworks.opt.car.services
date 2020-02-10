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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.nullable;

import static org.mockito.Mockito.times;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.util.UserIcons;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.wm.CarLaunchParamsModifier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

/**
 * This class contains unit tests for the {@link CarServiceHelperService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarHelperServiceTest {

    private static final String TAG = CarHelperServiceTest.class.getSimpleName();
    private static final String DEFAULT_NAME = "Driver";
    private static final int ADMIN_USER_ID = 10;

    private CarServiceHelperService mCarServiceHelperService;
    StaticMockitoSession mStaticMockitoSession;

    @Mock
    private Context mMockContext;
    @Mock
    private Context mApplicationContext;
    @Mock
    private CarUserManagerHelper mCarUserManagerHelper;
    @Mock
    private UserManager mUserManager;
    @Mock
    private CarLaunchParamsModifier mCarLaunchParamsModifier;
    @Mock
    private IBinder mICarBinder;

    @Captor
    private ArgumentCaptor<Parcel> mBinderCallData;

    private IActivityManager mActivityManager;
    private Exception mBinderCallException;

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
        mCarServiceHelperService = new CarServiceHelperService(
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
     * Test that the {@link CarServiceHelperService} starts up a secondary admin user upon first
     * run.
     */
    @Test
    public void testStartsSecondaryAdminUserOnFirstRun() throws Exception {
        doReturn(new ArrayList<>()).when(mUserManager).getUsers(anyBoolean());
        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mUserManager).createUser(anyString(), eq(UserInfo.FLAG_ADMIN));
        verify(mActivityManager).startUserInForegroundWithListener(ADMIN_USER_ID, null);
    }

    /**
     * Test that the {@link CarServiceHelperService} updates last active user to the first admin
     * user on first run.
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
        UserInfo admin = new UserInfo(adminUserId, DEFAULT_NAME, UserInfo.FLAG_ADMIN);

        int secUserId = ADMIN_USER_ID + 1;
        UserInfo secUser = new UserInfo(secUserId, DEFAULT_NAME, UserInfo.FLAG_ADMIN);

        users.add(admin);
        users.add(secUser);

        doReturn(users).when(mUserManager).getUsers(anyBoolean());
        doReturn(secUserId).when(mCarUserManagerHelper).getInitialUser();

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mActivityManager).startUserInForegroundWithListener(secUserId, null);
    }

    @Test
    public void testOnSwitchUserNotifiesICar() throws Exception {
        bindMockICar();

        int currentUserId = 10;
        int targetUserId = 11;
        expectICarOnSwitchUser(targetUserId);

        mCarServiceHelperService.onSwitchUser(newTargetUser(currentUserId),
                newTargetUser(targetUserId));

        assertNoICarCallExceptions();
        verifyICarOnSwitchUserCalled();
    }

    @Test
    public void testOnUnlockUserNotifiesICar() throws Exception {
        int userId = 10;
        expectICarSetUserLockStatus(userId, true);
        bindMockICar();

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mCarServiceHelperService.onUnlockUser(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarSetUserLockStatusCalled();
    }

    @Test
    public void testOnStopUserNotifiesICar() throws Exception {
        int userId = 10;
        expectICarSetUserLockStatus(userId, false);
        bindMockICar();

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mCarServiceHelperService.onStopUser(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarSetUserLockStatusCalled();
    }

    @Test
    public void testOnCleanupUserNotifiesICar() throws Exception {
        int userId = 10;
        expectICarSetUserLockStatus(userId, false);
        bindMockICar();

        mCarServiceHelperService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mCarServiceHelperService.onCleanupUser(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarSetUserLockStatusCalled();
    }

    private TargetUser newTargetUser(int userId) {
        TargetUser targetUser = mock(TargetUser.class);
        when(targetUser.getUserIdentifier()).thenReturn(userId);
        return targetUser;
    }

    private void bindMockICar() throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION
                + CarServiceHelperService.ICAR_CALL_SET_CAR_SERVICE_HELPER;
        // Must set the binder expectation, otherwise checks for other transactions would fail
        when(mICarBinder.transact(eq(txn), notNull(), isNull(), eq(Binder.FLAG_ONEWAY)))
                .thenReturn(true);
        mCarServiceHelperService.handleCarServiceConnection(mICarBinder);
    }

    // TODO: create a custom matcher / verifier for binder calls

    private void expectICarOnSwitchUser(int expectedUserId) throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION + CarServiceHelperService.ICAR_CALL_ON_SWITCH_USER;

        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(CarServiceHelperService.CAR_SERVICE_INTERFACE);
                        int actualUserId = data.readInt();
                        Log.d(TAG, "Unmarshalled data: userId= " + actualUserId);
                        List<String> errors = new ArrayList<>();
                        assertParcelValue(errors, "userId", expectedUserId, actualUserId);
                        assertNoParcelErrors(errors);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception answering binder call", e);
                        mBinderCallException = e;
                        return false;
                    }
                });
    }

    private void expectICarSetUserLockStatus(int expectedUserId, boolean expectedUnlocked)
            throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION
                + CarServiceHelperService.ICAR_CALL_SET_USER_UNLOCK_STATUS;
        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(CarServiceHelperService.CAR_SERVICE_INTERFACE);
                        int actualUserId = data.readInt();
                        int actualUnlocked = data.readInt();
                        Log.d(TAG, "Unmarshalled data: userId= " + actualUserId
                                + ", unlocked=" + actualUnlocked);
                        List<String> errors = new ArrayList<>();
                        assertParcelValue(errors, "userId", expectedUserId, actualUserId);
                        assertParcelValue(errors, "unlocked",
                                expectedUnlocked ? 1 : 0, actualUnlocked);
                        assertNoParcelErrors(errors);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception answering binder call", e);
                        mBinderCallException = e;
                        return false;
                    }
                });
    }

    private void verifyICarOnSwitchUserCalled() throws Exception {
        verifyICarTxnCalled(CarServiceHelperService.ICAR_CALL_ON_SWITCH_USER);
    }

    private void verifyICarSetUserLockStatusCalled() throws Exception {
        verifyICarTxnCalled(CarServiceHelperService.ICAR_CALL_SET_USER_UNLOCK_STATUS);
    }

    private void verifyICarTxnCalled(int txnId) throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION + txnId;
        verify(mICarBinder, times(1)).transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY));
    }

    private void assertParcelValue(List<String> errors, String field, int expected,
            int actual) {
        if (expected != actual) {
            errors.add(String.format("%s mismatch: expected=%d, actual=%d",
                    field, expected, actual));
        }
    }

    private void assertNoParcelErrors(List<String> errors) {
        int size = errors.size();
        if (size == 0) return;

        StringBuilder msg = new StringBuilder().append(size).append(" errors on parcel: ");
        for (String error : errors) {
            msg.append("\n\t").append(error);
        }
        msg.append('\n');
        throw new IllegalArgumentException(msg.toString());
    }

    /**
     * Asserts that no exception was thrown when answering to a mocked {@code ICar} binder call.
     * <p>
     * This method should be called before asserting the expected results of a test, so it makes
     * clear why the test failed when the call was not made as expected.
     */
    private void assertNoICarCallExceptions() throws Exception {
        if (mBinderCallException != null)
            throw mBinderCallException;

    }
}
