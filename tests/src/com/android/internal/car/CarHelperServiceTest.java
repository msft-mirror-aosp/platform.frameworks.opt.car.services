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

import static android.car.test.util.UserTestingHelper.UserInfoBuilder;
import static android.car.test.util.UserTestingHelper.getDefaultUserType;
import static android.car.test.util.UserTestingHelper.newGuestUser;
import static android.car.test.util.UserTestingHelper.newSecondaryUser;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import android.annotation.UserIdInt;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.SyncAnswer;
import android.car.userlib.CarUserManagerHelper;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.CarProperties;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.car.ExternalConstants.CarUserManagerConstants;
import com.android.internal.car.ExternalConstants.ICarConstants;
import com.android.internal.os.IResultReceiver;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.CarLaunchParamsModifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

/**
 * This class contains unit tests for the {@link CarServiceHelperService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarHelperServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarHelperServiceTest.class.getSimpleName();

    private static final int PRE_CREATED_USER_ID = 24;
    private static final int PRE_CREATED_GUEST_ID = 25;
    private static final int USER_MANAGER_TIMEOUT_MS = 100;

    private static final int HAL_TIMEOUT_MS = 500;

    private static final int ADDITIONAL_TIME_MS = 200;

    private CarServiceHelperService mHelperSpy;
    private CarServiceHelperService mHelper;
    private FakeICarSystemServerClient mCarService;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mPackageManager;
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

    @Captor
    private ArgumentCaptor<Parcel> mBinderCallData;

    private Exception mBinderCallException;

    /**
     * Initialize objects and setup testing environment.
     */
    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session
                .spyStatic(CarProperties.class)
                .spyStatic(UserManager.class);
    }

    @Before
    public void setUpMocks() {
        mHelper = new CarServiceHelperService(
                mMockContext,
                mUserManagerHelper,
                mUserManager,
                mCarLaunchParamsModifier,
                mCarWatchdogDaemonHelper,
                /* halEnabled= */ true,
                HAL_TIMEOUT_MS);
        mHelperSpy = spy(mHelper);
        mCarService = new FakeICarSystemServerClient();
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
    }

    @Test
    public void testCarServiceLaunched() throws Exception {
        mockRegisterReceiver();
        mockBindService();
        mockLoadLibrary();

        mHelperSpy.onStart();

        verifyBindService();
    }

    @Test
    public void testHandleCarServiceCrash() throws Exception {
        mockHandleCarServiceCrash();
        mockCarServiceException();

        mHelperSpy.handleCarServiceConnection(mICarBinder);

        verify(mHelperSpy).handleCarServiceCrash();
    }

    @Test
    public void testInitBootUser_notifiesICar() throws Exception {
        bindMockICar();

        mHelper.initBootUser();

        assertNoICarCallExceptions();
        verifyICarStartInitialUserCalled();
    }

    @Test
    public void testOnUserStarting_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();
        mHelper.onUserStarting(newTargetUser(userId));

        assertNoICarCallExceptions();

        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserStarting_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserStarting(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserSwitching_notifiesICar() throws Exception {
        bindMockICar();

        int currentUserId = 10;
        int targetUserId = 11;
        long before = System.currentTimeMillis();

        mHelper.onUserSwitching(newTargetUser(currentUserId),
                newTargetUser(targetUserId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING, before,
                currentUserId, targetUserId);
    }

    @Test
    public void testOnUserSwitching_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserSwitching(newTargetUser(10), newTargetUser(11, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserUnlocking_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();

        mHelper.onUserUnlocking(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserUnlocking_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserUnlocking(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserUnlocked_notifiesICar_systemUserFirst() throws Exception {
        bindMockICar();

        int systemUserId = UserHandle.USER_SYSTEM;
        long before = System.currentTimeMillis();
        long minDuration = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
        int firstUserId = 10;

        setHalResponseTime();
        mHelper.onUserUnlocked(newTargetUser(systemUserId));
        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, before,
                UserHandle.USER_NULL, systemUserId); // system user

        mHelper.onUserUnlocked(newTargetUser(firstUserId));
        assertNoICarCallExceptions();
        verifyICarFirstUserUnlockedCalled(firstUserId, before, minDuration);    // first user
    }

    @Test
    public void testOnUserUnlocked_notifiesICar_firstUserReportedJustOnce() throws Exception {
        bindMockICar();

        int firstUserId = 10;
        long before = System.currentTimeMillis();
        long minDuration = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
        int secondUserId = 11;

        setHalResponseTime();
        mHelper.onUserUnlocked(newTargetUser(firstUserId));
        mHelper.onUserUnlocked(newTargetUser(secondUserId));

        assertNoICarCallExceptions();

        verifyICarFirstUserUnlockedCalled(firstUserId, before, minDuration);    // first user
        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, before,
                UserHandle.USER_NULL, secondUserId); // second user
    }

    @Test
    public void testOnUserStopping_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();

        mHelper.onUserStopping(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserStopping_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserStopping(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserStopped_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();

        mHelper.onUserStopped(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(
                CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserStopped_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserStopped(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testPreCreatedUsersLessThanRequested() throws Exception {
        // Set existing user
        expectNoPreCreatedUser();
        // Set number of requested user
        setNumberRequestedUsersProperty(1);
        setNumberRequestedGuestsProperty(0);
        mockRunAsync();
        SyncAnswer<UserInfo> syncUserInfo = mockPreCreateUser(/* isGuest= */ false);

        mHelperSpy.managePreCreatedUsers();
        syncUserInfo.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserCreated(/* isGuest= */ false);
    }

    @Test
    public void testPreCreatedGuestsLessThanRequested() throws Exception {
        // Set existing user
        expectNoPreCreatedUser();
        // Set number of requested user
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(1);
        mockRunAsync();
        SyncAnswer<UserInfo> syncUserInfo = mockPreCreateUser(/* isGuest= */ true);

        mHelperSpy.managePreCreatedUsers();
        syncUserInfo.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserCreated(/* isGuest= */ true);
    }

    @Test
    public void testRemovePreCreatedUser() throws Exception {
        UserInfo user = expectPreCreatedUser(/* isGuest= */ false,
                /* isInitialized= */ true);
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(0);
        mockRunAsync();

        SyncAnswer<Boolean> syncRemoveStatus = mockRemoveUser(PRE_CREATED_USER_ID);

        mHelperSpy.managePreCreatedUsers();
        syncRemoveStatus.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserRemoved(user);
    }

    @Test
    public void testRemovePreCreatedGuest() throws Exception {
        UserInfo user = expectPreCreatedUser(/* isGuest= */ true,
                /* isInitialized= */ true);
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(0);
        mockRunAsync();
        SyncAnswer<Boolean>  syncRemoveStatus = mockRemoveUser(PRE_CREATED_GUEST_ID);

        mHelperSpy.managePreCreatedUsers();
        syncRemoveStatus.await(USER_MANAGER_TIMEOUT_MS);

        verifyUserRemoved(user);
    }

    @Test
    public void testRemoveInvalidPreCreatedUser() throws Exception {
        UserInfo user = expectPreCreatedUser(/* isGuest= */ false,
                /* isInitialized= */ false);
        setNumberRequestedUsersProperty(0);
        setNumberRequestedGuestsProperty(0);
        mockRunAsync();
        SyncAnswer<Boolean>  syncRemoveStatus = mockRemoveUser(PRE_CREATED_USER_ID);

        mHelperSpy.managePreCreatedUsers();
        syncRemoveStatus.await(ADDITIONAL_TIME_MS);

        verifyUserRemoved(user);
    }

    @Test
    public void testManagePreCreatedUsersDoNothing() throws Exception {
        expectPreCreatedUser(/* isGuest= */ false, /* isInitialized= */ true);
        setNumberRequestedUsersProperty(1);
        setNumberRequestedGuestsProperty(0);
        mockPreCreateUser(/* isGuest= */ false);
        mockRemoveUser(PRE_CREATED_USER_ID);

        mHelperSpy.managePreCreatedUsers();

        verifyPostPreCreatedUserSkipped();
    }

    @Test
    public void testManagePreCreatedUsersOnBootCompleted() throws Exception {
        mockRunAsync();

        mHelperSpy.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        verifyManagePreCreatedUsers();
    }

    @Test
    public void testPreCreateUserExceptionLogged() throws Exception {
        mockPreCreateUserException();
        TimingsTraceAndSlog trace = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        mHelperSpy.preCreateUsers(trace, false);

        verifyPostPreCreatedUserException();
        assertThat(trace.getUnfinishedTracesForDebug()).isEmpty();
    }

    private void setHalResponseTime() {
        mHelper.setInitialHalResponseTime();
        sleepForHalResponseTimePurposes();
        mHelper.setFinalHalResponseTime();
    }

    private void verifyHalResponseTime() {
        assertThat(mHelper.getHalResponseTime()).isGreaterThan(0);
    }

    private TargetUser newTargetUser(int userId) {
        return newTargetUser(userId, /* preCreated= */ false);
    }

    private TargetUser newTargetUser(int userId, boolean preCreated) {
        TargetUser targetUser = mock(TargetUser.class);
        when(targetUser.getUserIdentifier()).thenReturn(userId);
        UserInfo userInfo = new UserInfo();
        userInfo.id = userId;
        userInfo.preCreated = preCreated;
        when(targetUser.getUserInfo()).thenReturn(userInfo);
        return targetUser;
    }

    private void bindMockICar() throws Exception {
        // Must set the binder expectation, otherwise checks for other transactions would fail
        expectSetSystemServerConnections();
        mHelper.handleCarServiceConnection(mICarBinder);
    }

    private void verifyBindService () throws Exception {
        verify(mMockContext).bindServiceAsUser(
                argThat(intent -> intent.getAction().equals(ICarConstants.CAR_SERVICE_INTERFACE)),
                any(), eq(Context.BIND_AUTO_CREATE), any(), eq(UserHandle.SYSTEM));
    }

    private void mockRegisterReceiver() {
        when(mMockContext.registerReceiverForAllUsers(any(), any(), any(), any()))
                .thenReturn(new Intent());
    }

    private void mockBindService() {
        when(mMockContext.bindServiceAsUser(any(), any(),
                eq(Context.BIND_AUTO_CREATE), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(true);
    }

    private void mockLoadLibrary() {
        doNothing().when(mHelperSpy).loadNativeLibrary();
    }

    private void mockCarServiceException() throws Exception {
        when(mICarBinder.transact(anyInt(), notNull(), isNull(), eq(Binder.FLAG_ONEWAY)))
                .thenThrow(new RemoteException("mock car service Crash"));
    }

    private void mockHandleCarServiceCrash() throws Exception {
        doNothing().when(mHelperSpy).handleCarServiceCrash();
    }

    private void expectSetSystemServerConnections() throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION;
        when(mICarBinder.transact(eq(txn), notNull(), isNull(), eq(Binder.FLAG_ONEWAY)))
                .thenAnswer((invocation) -> {
                    Log.d(TAG, "Answering txn " + txn);
                    Parcel data = (Parcel) invocation.getArguments()[1];
                    data.setDataPosition(0);
                    data.enforceInterface(ICarConstants.CAR_SERVICE_INTERFACE);
                    data.readStrongBinder(); // helper
                    IBinder result = data.readStrongBinder();
                    IResultReceiver resultReceiver = IResultReceiver.Stub.asInterface(result);
                    Bundle bundle = new Bundle();
                    IBinder binder = mCarService.asBinder();
                    bundle.putBinder(ICarConstants.ICAR_SYSTEM_SERVER_CLIENT, binder);
                    resultReceiver.send(1, bundle);
                    return true;
                });
    }

    enum InitialUserInfoAction {
        DEFAULT,
        DEFAULT_WITH_LOCALE,
        DO_NOT_REPLY,
        DELAYED_REPLY,
        NON_OK_RESULT_CODE,
        NULL_BUNDLE,
        SWITCH_OK,
        SWITCH_OK_WITH_LOCALE,
        SWITCH_MISSING_USER_ID
    }

    private void expectNoPreCreatedUser() throws Exception {
        when(mUserManager.getUsers(/* excludePartial= */ true,
                /* excludeDying= */ true, /* excludePreCreated= */ false))
                .thenReturn(new ArrayList<UserInfo> ());
    }

    private UserInfo expectPreCreatedUser(boolean isGuest, boolean isInitialized)
            throws Exception {
        int userId = isGuest ? PRE_CREATED_GUEST_ID : PRE_CREATED_USER_ID;
        UserInfo user = new UserInfoBuilder(userId)
                .setGuest(isGuest)
                .setPreCreated(true)
                .setInitialized(isInitialized)
                .build();

        when(mUserManager.getUsers(/* excludePartial= */ true,
                /* excludeDying= */ true, /* excludePreCreated= */ false))
                .thenReturn(Arrays.asList(user));
        return user;
    }

    private void sleepForHalResponseTimePurposes() {
        sleep("so HAL response time is not 0", 1);
    }

    private void sleep(String reason, long napTimeMs) {
        Log.d(TAG, "Sleeping for " + napTimeMs + "ms: " + reason);
        SystemClock.sleep(napTimeMs);
        Log.d(TAG, "Woke up");
    }

    private void verifyICarStartInitialUserCalled() {
        assertThat(mCarService.startInitialCalled).isTrue();
    }

    private void verifyICarOnUserLifecycleEventCalled(int eventType, long minTimestamp,
            @UserIdInt int fromId, @UserIdInt int toId) throws Exception {
        assertThat(mCarService.isOnUserLifecycleEventCalled).isTrue();
        assertThat(mCarService.eventTypeForLifeCycleEvent).isEqualTo(eventType);
        assertThat(mCarService.timeStampForLifeCyleEvent).isGreaterThan(minTimestamp);
        long now = System.currentTimeMillis();
        assertThat(mCarService.timeStampForLifeCyleEvent).isLessThan(now);
        assertThat(mCarService.fromUserForLifeCycelEvent).isEqualTo(fromId);
        assertThat(mCarService.toUserForLifeCyleEvent).isEqualTo(toId);
    }

    private void verifyICarOnUserLifecycleEventNeverCalled() throws Exception {
        assertThat(mCarService.isOnUserLifecycleEventCalled).isFalse();
    }

    private void verifyICarFirstUserUnlockedCalled(int userId, long minTimestamp, long minDuration)
            throws Exception {
        assertThat(mCarService.isOnFirstUserUnlockedCalled).isTrue();
        assertThat(mCarService.userIdForFirstUserUnlocked).isEqualTo(userId);
        assertThat(mCarService.timeStampForFirstUserUnlocked).isGreaterThan(minTimestamp);
        long now = System.currentTimeMillis();
        assertThat(mCarService.timeStampForFirstUserUnlocked).isLessThan(now);
        assertThat(mCarService.halResponseTimeForFirstUserUnlocked).isGreaterThan(1);
        assertThat(mCarService.durationForFirstUserUnlocked).isGreaterThan(minDuration);
    }

    private void setNumberRequestedUsersProperty(int numberUser) {
        doReturn(Optional.of(numberUser)).when(() -> CarProperties.number_pre_created_users());
    }

    private void setNumberRequestedGuestsProperty(int numberGuest) {
        doReturn(Optional.of(numberGuest)).when(() -> CarProperties.number_pre_created_guests());
    }

    private void mockRunAsync() {
        doAnswer(answerVoid(Runnable::run)).when(mHelperSpy).runAsync(any(Runnable.class));
    }

    private SyncAnswer<UserInfo> mockPreCreateUser(boolean isGuest) {
        UserInfo newUser = isGuest ? newGuestUser(PRE_CREATED_GUEST_ID) :
                newSecondaryUser(PRE_CREATED_USER_ID);
        SyncAnswer<UserInfo> syncUserInfo = SyncAnswer.forReturn(newUser);
        when(mUserManager.preCreateUser(getDefaultUserType(isGuest)))
                .thenAnswer(syncUserInfo);

        return syncUserInfo;
    }

    private SyncAnswer<Boolean> mockRemoveUser(@UserIdInt int userId) {
        SyncAnswer<Boolean> syncRemoveStatus = SyncAnswer.forReturn(true);
        when(mUserManager.removeUser(userId)).thenAnswer(syncRemoveStatus);

        return syncRemoveStatus;
    }

    private SyncAnswer<UserInfo> mockPreCreateUserException() {
        SyncAnswer<UserInfo> syncException = SyncAnswer.forException(new Exception());
        when(mUserManager.preCreateUser(anyString()))
                .thenAnswer(syncException);
        return syncException;
    }

    private void verifyUserCreated(boolean isGuest) throws Exception {
        String userType =
                isGuest ? UserManager.USER_TYPE_FULL_GUEST : UserManager.USER_TYPE_FULL_SECONDARY;
        verify(mUserManager).preCreateUser(eq(userType));
    }

    private void verifyUserRemoved(UserInfo user) throws Exception {
        verify(mUserManager).removeUser(user.id);
    }

    private void verifyPostPreCreatedUserSkipped() throws Exception {
        verify(mHelperSpy, never()).runAsync(any());
    }

    private void verifyPostPreCreatedUserException() throws Exception {
        verify(mHelperSpy).logPrecreationFailure(anyString(), any());
    }

    private void verifyManagePreCreatedUsers() throws Exception {
        verify(mHelperSpy).managePreCreatedUsers();
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

    // TODO(b/162241237): Use mock instead of fake if possible.
    private final class FakeICarSystemServerClient extends ICarSystemServerClient.Stub {

        public boolean isOnFirstUserUnlockedCalled;
        public int userIdForFirstUserUnlocked;
        public long timeStampForFirstUserUnlocked;
        public long durationForFirstUserUnlocked;
        public int halResponseTimeForFirstUserUnlocked;

        public boolean isOnUserLifecycleEventCalled;
        public int eventTypeForLifeCycleEvent;
        public long timeStampForLifeCyleEvent;
        public int fromUserForLifeCycelEvent;
        public int toUserForLifeCyleEvent;

        public boolean startInitialCalled;

        @Override
        public void onFirstUserUnlocked(int userId, long timestampMs, long duration,
                int halResponseTime)
                throws RemoteException {
            isOnFirstUserUnlockedCalled = true;
            userIdForFirstUserUnlocked = userId;
            timeStampForFirstUserUnlocked = timestampMs;
            durationForFirstUserUnlocked = duration;
            halResponseTimeForFirstUserUnlocked = halResponseTime;
        }

        @Override
        public void onUserLifecycleEvent(int eventType, long timestamp, @UserIdInt int fromId,
                @UserIdInt int toId) throws RemoteException {
            isOnUserLifecycleEventCalled = true;
            eventTypeForLifeCycleEvent = eventType;
            timeStampForLifeCyleEvent = timestamp;
            fromUserForLifeCycelEvent = fromId;
            toUserForLifeCyleEvent = toId;
        }

        @Override
        public void initBootUser() throws RemoteException {
            startInitialCalled = true;
        }
    }
}
