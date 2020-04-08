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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.nullable;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.car.userlib.CarUserManagerHelper;
import android.car.userlib.InitialUserSetter;
import android.car.userlib.UserHalHelper;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.Slog;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.car.ExternalConstants.CarUserManagerConstants;
import com.android.internal.car.ExternalConstants.CarUserServiceConstants;
import com.android.internal.car.ExternalConstants.ICarConstants;
import com.android.internal.car.ExternalConstants.UserHalServiceConstants;
import com.android.internal.os.IResultReceiver;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

/**
 * This class contains unit tests for the {@link CarServiceHelperService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarHelperServiceTest {

    private static final String TAG = CarHelperServiceTest.class.getSimpleName();

    private static final String HAL_USER_NAME = "HAL 9000";
    private static final int HAL_USER_ID = 42;
    private static final int HAL_USER_FLAGS = 108;

    private static final int HAL_TIMEOUT_MS = 500;

    private static final int ADDITIONAL_TIME_MS = 200;

    private static final int HAL_NOT_REPLYING_TIMEOUT_MS = HAL_TIMEOUT_MS + ADDITIONAL_TIME_MS;

    private static final long POST_HAL_NOT_REPLYING_TIMEOUT_MS = HAL_NOT_REPLYING_TIMEOUT_MS
            + ADDITIONAL_TIME_MS;

    private CarServiceHelperService mHelper;

    private StaticMockitoSession mStaticMockitoSession;

    @Mock
    private Context mMockContext;
    @Mock
    private Context mApplicationContext;
    @Mock
    private CarUserManagerHelper mUserManagerHelper;
    @Mock
    private UserManager mUserManager;
    @Mock
    private CarLaunchParamsModifier mCarLaunchParamsModifier;
    @Mock
    private CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    @Mock
    private IBinder mICarBinder;
    @Mock
    private InitialUserSetter mInitialUserSetter;

    @Captor
    private ArgumentCaptor<Parcel> mBinderCallData;

    private Exception mBinderCallException;

    /**
     * Initialize objects and setup testing environment.
     */
    @Before
    public void setUpMocks() {
        mStaticMockitoSession = mockitoSession()
                .initMocks(this)
                .spyStatic(Slog.class)
                .mockStatic(UserIcons.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        interceptWtfCalls();

        mHelper = new CarServiceHelperService(
                mMockContext,
                mUserManagerHelper,
                mInitialUserSetter,
                mUserManager,
                mCarLaunchParamsModifier,
                mCarWatchdogDaemonHelper,
                /* halEnabled= */ true,
                HAL_TIMEOUT_MS);
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
    public void testInitialInfo_noHal() throws Exception {
        CarServiceHelperService halLessHelper = new CarServiceHelperService(
                mMockContext,
                mUserManagerHelper,
                mInitialUserSetter,
                mUserManager,
                mCarLaunchParamsModifier,
                mCarWatchdogDaemonHelper,
                /* halEnabled= */ false,
                HAL_TIMEOUT_MS);

        halLessHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halReturnedDefault() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.DEFAULT);

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halServiceNeverReturned() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.DO_NOT_REPLY);

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        sleep("before asserting DEFAULT behavior", POST_HAL_NOT_REPLYING_TIMEOUT_MS);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halServiceReturnedTooLate() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.DELAYED_REPLY);

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        sleep("before asserting DEFAULT behavior", POST_HAL_NOT_REPLYING_TIMEOUT_MS);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        sleep("to make sure not called again", POST_HAL_NOT_REPLYING_TIMEOUT_MS);

        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halReturnedNonOkResultCode() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.NON_OK_RESULT_CODE);

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halReturnedOkWithNoBundle() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.NULL_BUNDLE);

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halReturnedSwitch_ok() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.SWITCH_OK);
        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyUserSwitchedByHal();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halReturnedSwitch_switchMissingUserId() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo(InitialUserInfoAction.SWITCH_MISSING_USER_ID);

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyUserNotSwitchedByHal();
        verifyDefaultBootBehavior();
        verifyWtfNeverLogged();
    }

    @Test
    public void testInitialInfo_halReturnedCreateOk() throws Exception {
        bindMockICar();

        expectICarGetInitialUserInfo((r) -> sendCreateDefaultHalUserAction(r));

        mHelper.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        assertNoICarCallExceptions();
        verifyICarGetInitialUserInfoCalled();

        verifyUserCreatedByHal();
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserStartingNotifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING,
                userId);

        mHelper.onUserStarting(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled();
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserSwitchingNotifiesICar() throws Exception {
        bindMockICar();

        int currentUserId = 10;
        int targetUserId = 11;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING,
                currentUserId, targetUserId);
        expectICarOnSwitchUser(targetUserId);

        mHelper.onUserSwitching(newTargetUser(currentUserId),
                newTargetUser(targetUserId));

        assertNoICarCallExceptions();
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserUnlockingNotifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING,
                userId);
        expectICarSetUserLockStatus(userId, true);

        mHelper.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mHelper.onUserUnlocking(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserUnlockedNotifiesICar_systemUserFirst() throws Exception {
        bindMockICar();

        int systemUserId = UserHandle.USER_SYSTEM;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED,
                systemUserId);

        int firstUserId = 10;
        expectICarFirstUserUnlocked(firstUserId);

        mHelper.onUserUnlocked(newTargetUser(systemUserId));
        mHelper.onUserUnlocked(newTargetUser(firstUserId));

        assertNoICarCallExceptions();

        verifyICarOnUserLifecycleEventCalled(); // system user
        verifyICarFirstUserUnlockedCalled();    // first user
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserUnlockedNotifiesICar_firstUserReportedJustOnce() throws Exception {
        bindMockICar();

        int firstUserId = 10;
        expectICarFirstUserUnlocked(firstUserId);

        int secondUserId = 11;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED,
                secondUserId);

        mHelper.onUserUnlocked(newTargetUser(firstUserId));
        mHelper.onUserUnlocked(newTargetUser(secondUserId));

        assertNoICarCallExceptions();

        verifyICarFirstUserUnlockedCalled();    // first user
        verifyICarOnUserLifecycleEventCalled(); // second user
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserStoppingNotifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING,
                userId);
        expectICarSetUserLockStatus(userId, false);

        mHelper.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mHelper.onUserStopping(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyWtfNeverLogged();
    }

    @Test
    public void testOnUserStoppedNotifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        expectICarOnUserLifecycleEvent(CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED,
                userId);
        expectICarSetUserLockStatus(userId, false);

        mHelper.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mHelper.onUserStopped(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyWtfNeverLogged();
    }

    @Test
    public void testSendSetInitialUserInfoNotifiesICar() throws Exception {
        bindMockICar();

        UserInfo user = new UserInfo(42, "Dude", UserInfo.FLAG_ADMIN);
        expectICarSetInitialUserInfo(user);

        mHelper.setInitialUser(user);

        verifyICarSetInitialUserCalled();
        assertNoICarCallExceptions();
        verifyWtfNeverLogged();
    }

    /**
     * Used in cases where the result of calling HAL for the initial info should be the same as
     * not using HAL.
     */
    private void verifyDefaultBootBehavior() throws Exception {
        verify(mInitialUserSetter).executeDefaultBehavior();
    }

    private TargetUser newTargetUser(int userId) {
        TargetUser targetUser = mock(TargetUser.class);
        when(targetUser.getUserIdentifier()).thenReturn(userId);
        return targetUser;
    }

    private void bindMockICar() throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION
                + ICarConstants.ICAR_CALL_SET_CAR_SERVICE_HELPER;
        // Must set the binder expectation, otherwise checks for other transactions would fail
        when(mICarBinder.transact(eq(txn), notNull(), isNull(), eq(Binder.FLAG_ONEWAY)))
                .thenReturn(true);
        mHelper.handleCarServiceConnection(mICarBinder);
    }

    private void verifyUserCreatedByHal() throws Exception {
        verify(mInitialUserSetter).createUser(HAL_USER_NAME, HAL_USER_FLAGS);
    }

    private void verifyUserSwitchedByHal() {
        verify(mInitialUserSetter).switchUser(HAL_USER_ID);
    }

    private void verifyUserNotSwitchedByHal() {
        verify(mInitialUserSetter, never()).switchUser(anyInt());
    }

    // TODO: create a custom matcher / verifier for binder calls

    private void expectICarOnUserLifecycleEvent(int eventType, int expectedUserId)
            throws Exception {
        expectICarOnUserLifecycleEvent(eventType, UserHandle.USER_NULL, expectedUserId);
    }

    private void expectICarOnUserLifecycleEvent(int expectedEventType, int expectedFromUserId,
            int expectedToUserId) throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION + ICarConstants.ICAR_CALL_ON_USER_LIFECYCLE;
        long before = System.currentTimeMillis();

        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        long after = System.currentTimeMillis();
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
                        int actualEventType = data.readInt();
                        long actualTimestamp = data.readLong();
                        int actualFromUserId = data.readInt();
                        int actualToUserId = data.readInt();
                        Log.d(TAG, "Unmarshalled data: eventType=" + actualEventType
                                + ", timestamp= " + actualTimestamp
                                + ", fromUserId= " + actualFromUserId
                                + ", toUserId= " + actualToUserId);
                        List<String> errors = new ArrayList<>();
                        assertParcelValueInRange(errors, "timestamp", before, actualTimestamp, after);
                        assertParcelValue(errors, "eventType", expectedEventType, actualEventType);
                        assertParcelValue(errors, "fromUserId", expectedFromUserId,
                                actualFromUserId);
                        assertParcelValue(errors, "toUserId", expectedToUserId, actualToUserId);
                        assertNoParcelErrors(errors);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception answering binder call", e);
                        mBinderCallException = e;
                        return false;
                    }
                });
    }

    private void expectICarFirstUserUnlocked(int expectedUserId) throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION + ICarConstants.ICAR_CALL_FIRST_USER_UNLOCKED;
        long before = System.currentTimeMillis();
        long minDuration = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();

        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        long after = System.currentTimeMillis();
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
                        int actualUserId = data.readInt();
                        long actualTimestamp = data.readLong();
                        long actualDuration = data.readLong();
                        Log.d(TAG, "Unmarshalled data: userId= " + actualUserId
                                + ", timestamp= " + actualTimestamp
                                + ", duration=" + actualDuration);
                        List<String> errors = new ArrayList<>();
                        assertParcelValue(errors, "userId", expectedUserId, actualUserId);
                        assertParcelValueInRange(errors, "timestamp", before, actualTimestamp,
                                after);
                        if (actualDuration < minDuration) {
                            errors.add("Minimum duration should be " + minDuration + " (was "
                                    + actualDuration + ")");
                        }
                        assertNoParcelErrors(errors);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception answering binder call", e);
                        mBinderCallException = e;
                        return false;
                    }
                });

    }

    private void expectICarOnSwitchUser(int expectedUserId) throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION + ICarConstants.ICAR_CALL_ON_SWITCH_USER;

        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
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
        int txn = IBinder.FIRST_CALL_TRANSACTION + ICarConstants.ICAR_CALL_SET_USER_UNLOCK_STATUS;
        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
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

    enum InitialUserInfoAction {
        DEFAULT,
        DO_NOT_REPLY,
        DELAYED_REPLY,
        NON_OK_RESULT_CODE,
        NULL_BUNDLE,
        SWITCH_OK,
        SWITCH_MISSING_USER_ID
    }

    private void expectICarGetInitialUserInfo(InitialUserInfoAction action) throws Exception {
        expectICarGetInitialUserInfo((receiver) ->{
            switch (action) {
                case DEFAULT:
                    sendDefaultAction(receiver);
                    break;
                case DO_NOT_REPLY:
                    Log.d(TAG, "NOT replying to bind call");
                    break;
                case DELAYED_REPLY:
                    sleep("before sending result", HAL_NOT_REPLYING_TIMEOUT_MS);
                    sendDefaultAction(receiver);
                    break;
                case NON_OK_RESULT_CODE:
                    Log.d(TAG, "sending bad result code");
                    receiver.send(-1, null);
                    break;
                case NULL_BUNDLE:
                    Log.d(TAG, "sending OK without bundle");
                    receiver.send(UserHalServiceConstants.STATUS_OK, null);
                    break;
                case SWITCH_OK:
                    sendValidSwitchAction(receiver);
                    break;
                case SWITCH_MISSING_USER_ID:
                    Log.d(TAG, "sending Switch without user Id");
                    sendSwitchAction(receiver, null);
                    break;
               default:
                    throw new IllegalArgumentException("invalid action: " + action);
            }
        });
    }

    private void expectICarGetInitialUserInfo(GetInitialUserInfoAction action) throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION + ICarConstants.ICAR_CALL_GET_INITIAL_USER_INFO;
        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
                        int actualRequestType = data.readInt();
                        int actualTimeoutMs = data.readInt();
                        IResultReceiver receiver = IResultReceiver.Stub
                                .asInterface(data.readStrongBinder());

                        Log.d(TAG, "Unmarshalled data: requestType= " + actualRequestType
                                + ", timeout=" + actualTimeoutMs
                                + ", receiver =" + receiver);
                        action.onReceiver(receiver);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception answering binder call", e);
                        mBinderCallException = e;
                        return false;
                    }
                });
    }

    private void expectICarSetInitialUserInfo(UserInfo user) throws RemoteException {
        int txn = IBinder.FIRST_CALL_TRANSACTION + ICarConstants.ICAR_CALL_SET_INITIAL_USER;
        when(mICarBinder.transact(eq(txn), notNull(), isNull(),
                eq(Binder.FLAG_ONEWAY))).thenAnswer((invocation) -> {
                    try {
                        Log.d(TAG, "Answering txn " + txn);
                        Parcel data = (Parcel) invocation.getArguments()[1];
                        data.setDataPosition(0);
                        data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
                        int actualUserId = data.readInt();
                        Log.d(TAG, "Unmarshalled data: user= " + actualUserId);
                        assertThat(actualUserId).isEqualTo(user.id);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception answering binder call", e);
                        mBinderCallException = e;
                        return false;
                    }
                });
    }

    private interface GetInitialUserInfoAction {
        void onReceiver(IResultReceiver receiver) throws Exception;
    }

    private void sendDefaultAction(IResultReceiver receiver) throws Exception {
        Log.d(TAG, "Sending DEFAULT action to receiver " + receiver);
        Bundle data = new Bundle();
        data.putInt(CarUserServiceConstants.BUNDLE_INITIAL_INFO_ACTION,
                InitialUserInfoResponseAction.DEFAULT);
        receiver.send(UserHalServiceConstants.STATUS_OK, data);
    }

    private void sendValidSwitchAction(IResultReceiver receiver) throws Exception {
        Log.d(TAG, "Sending SWITCH (" + HAL_USER_ID + ") action to receiver " + receiver);
        sendSwitchAction(receiver, HAL_USER_ID);
    }

    private void sendSwitchAction(IResultReceiver receiver, Integer id) throws Exception {
        Bundle data = new Bundle();
        data.putInt(CarUserServiceConstants.BUNDLE_INITIAL_INFO_ACTION,
                InitialUserInfoResponseAction.SWITCH);
        if (id != null) {
            data.putInt(CarUserServiceConstants.BUNDLE_USER_ID, id);
        }
        receiver.send(UserHalServiceConstants.STATUS_OK, data);
    }

    private void sendCreateDefaultHalUserAction(IResultReceiver receiver) throws Exception {
        sendCreateAction(receiver, HAL_USER_NAME, HAL_USER_FLAGS);
    }

    private void sendCreateAction(IResultReceiver receiver, String name, Integer flags)
            throws Exception {
        Bundle data = new Bundle();
        data.putInt(CarUserServiceConstants.BUNDLE_INITIAL_INFO_ACTION,
                InitialUserInfoResponseAction.CREATE);
        if (name != null) {
            data.putString(CarUserServiceConstants.BUNDLE_USER_NAME, name);
        }
        if (flags != null) {
            data.putInt(CarUserServiceConstants.BUNDLE_USER_FLAGS, flags);
        }
        receiver.send(UserHalServiceConstants.STATUS_OK, data);
    }

    private void sleep(String reason, long napTimeMs) {
        Log.d(TAG, "Sleeping for " + napTimeMs + "ms: " + reason);
        SystemClock.sleep(napTimeMs);
        Log.d(TAG, "Woke up (from '"  + reason + "')");
    }

    private void verifyICarOnUserLifecycleEventCalled() throws Exception {
        verifyICarTxnCalled(ICarConstants.ICAR_CALL_ON_USER_LIFECYCLE);
    }

    private void verifyICarFirstUserUnlockedCalled() throws Exception {
        verifyICarTxnCalled(ICarConstants.ICAR_CALL_FIRST_USER_UNLOCKED);
    }

    private void verifyICarGetInitialUserInfoCalled() throws Exception {
        verifyICarTxnCalled(ICarConstants.ICAR_CALL_GET_INITIAL_USER_INFO);
    }

    private void verifyICarSetInitialUserCalled() throws Exception {
        verifyICarTxnCalled(ICarConstants.ICAR_CALL_SET_INITIAL_USER);
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

    private void assertParcelValueInRange(List<String> errors, String field, long before,
            long actual, long after) {
        if (actual < before || actual> after) {
            errors.add(field + " (" + actual+ ") not in range [" + before + ", " + after + "]");
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

    // TODO(b/149099817): members below should be moved to common code

    // Tracks Log.wtf() calls made during code execution / used on verifyWtfNeverLogged()
    private final List<UnsupportedOperationException> mWtfs = new ArrayList<>();

    private void interceptWtfCalls() {
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Slog.wtf(anyString(), anyString()));
        doAnswer((invocation) -> {
            return addWtf(invocation);
        }).when(() -> Slog.wtf(anyString(), anyString(), notNull()));
    }

    private Object addWtf(InvocationOnMock invocation) {
        String message = "Called " + invocation;
        Log.d(TAG, message); // Log always, as some test expect it
        mWtfs.add(new UnsupportedOperationException(message));
        return null;
    }

    // TODO: should be part of @After, but then it would hide the real test failure (if any). We'd
    // need a custom rule (like CTS's SafeCleaner) for it...
    private void verifyWtfNeverLogged() {
        int size = mWtfs.size();

        switch (size) {
            case 0:
                return;
            case 1:
                throw mWtfs.get(0);
            default:
                StringBuilder msg = new StringBuilder("wtf called ").append(size).append(" times")
                        .append(": ").append(mWtfs);
                fail(msg.toString());
        }
    }
}
