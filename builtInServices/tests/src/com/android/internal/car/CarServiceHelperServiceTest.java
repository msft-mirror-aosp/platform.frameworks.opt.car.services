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

import static com.android.car.internal.common.CommonConstants.INVALID_PID;
import static com.android.car.internal.common.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED;
import static com.android.car.internal.common.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static com.android.car.internal.common.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static com.android.car.internal.common.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING;
import static com.android.car.internal.common.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static com.android.car.internal.common.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING;
import static com.android.internal.util.FrameworkStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_ANR;
import static com.android.internal.util.FrameworkStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE;
import static com.android.internal.util.FrameworkStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__GARAGE_MODE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.SystemService.UserCompletedEventType.newUserCompletedEventTypeForTest;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.automotive.watchdog.internal.ClientsNotRespondingInfo;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.ProcessIdentifier;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ServiceDebugInfo;
import android.os.ServiceManager;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.CarWatchdogKillStatsReported;
import com.android.internal.util.CarWatchdogProcessStats;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.SystemService.TargetUser;
import com.android.server.SystemService.UserCompletedEventType;
import com.android.server.am.StackTracesDumpHelper;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.CarDisplayCompatScaleProvider;
import com.android.server.wm.CarLaunchParamsModifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * This class contains unit tests for the {@link CarServiceHelperService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarServiceHelperServiceTest extends AbstractExtendedMockitoTestCase {
    private static final String SAMPLE_AIDL_VHAL_INTERFACE_NAME =
            "android.hardware.automotive.vehicle.IVehicle/SampleVehicleHalService";
    private static final int MAX_WAIT_TIME_MS = 3000;

    private CarServiceHelperService mHelper;

    @Mock
    private Context mMockContext;
    @Mock
    private Path mMockPath;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private CarLaunchParamsModifier mCarLaunchParamsModifier;
    @Mock
    private CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    @Mock
    private IBinder mICarBinder;
    @Mock
    private CarServiceHelperServiceUpdatable mCarServiceHelperServiceUpdatable;

    @Mock
    private CarDevicePolicySafetyChecker mCarDevicePolicySafetyChecker;

    @Mock
    private UserManagerInternal mUserManagerInternal;

    @Mock
    private ActivityManager mActivityManager;

    @Mock
    private CarActivityInterceptor mActivityInterceptor;
    @Mock
    private CarDisplayCompatScaleProvider mCarDisplayCompatScaleProvider;

    @Captor private ArgumentCaptor<byte[]> mKilledStatsCaptor;
    @Captor private ArgumentCaptor<Integer> mKilledUidCaptor;
    @Captor private ArgumentCaptor<Integer> mUidStateCaptor;
    @Captor private ArgumentCaptor<Integer> mSystemStateCaptor;
    @Captor private ArgumentCaptor<Integer> mKillReasonCaptor;
    @Captor private ArgumentCaptor<ArrayList<Integer>> mDumpJavaPidCaptor;
    @Captor private ArgumentCaptor<Future<ArrayList<Integer>>> mDumpNativePidCaptor;
    @Captor private ArgumentCaptor<ProcessIdentifier> mProcessIdentifierCaptor;

    public CarServiceHelperServiceTest() {
        super(CarServiceHelperService.TAG);
    }

    /**
     * Initialize objects and setup testing environment.
     */
    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session
                .spyStatic(ServiceManager.class)
                .spyStatic(LocalServices.class)
                .spyStatic(Files.class)
                .spyStatic(FrameworkStatsLog.class)
                .spyStatic(StackTracesDumpHelper.class);
    }

    @Before
    public void setTestFixtures() {
        mHelper = new CarServiceHelperService(
                mMockContext,
                mCarLaunchParamsModifier,
                mCarWatchdogDaemonHelper,
                mCarServiceHelperServiceUpdatable,
                mCarDevicePolicySafetyChecker,
                mActivityInterceptor,
                mCarDisplayCompatScaleProvider);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockContext.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);

        doReturn(mUserManagerInternal)
                .when(() -> LocalServices.getService(UserManagerInternal.class));
    }

    @Test
    public void testIsUserSupported_preCreatedUserIsNotSupported() throws Exception {
        expectWithMessage("isUserSupported")
            .that(mHelper.isUserSupported(newTargetUser(10, /* preCreated= */ true)))
            .isFalse();
    }

    @Test
    public void testIsUserSupported_nonPreCreatedUserIsSupported() throws Exception {
        expectWithMessage("isUserSupported").that(mHelper.isUserSupported(newTargetUser(11)))
            .isTrue();
    }

    @Test
    public void testOnUserStarting_notifiesICar() throws Exception {
        int userId = 10;

        mHelper.onUserStarting(newTargetUser(userId));

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_STARTING, userId);
    }

    @Test
    public void testOnUserSwitching_notifiesICar() throws Exception {
        int currentUserId = 10;
        int targetUserId = 11;

        mHelper.onUserSwitching(newTargetUser(currentUserId),
                newTargetUser(targetUserId));

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_SWITCHING,
                currentUserId, targetUserId);
    }

    @Test
    public void testOnUserUnlocking_notifiesICar() throws Exception {
        int userId = 10;

        mHelper.onUserUnlocking(newTargetUser(userId));

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, userId);
    }

    @Test
    public void testOnUserStopping_notifiesICar() throws Exception {
        int userId = 10;

        mHelper.onUserStopping(newTargetUser(userId));

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_STOPPING, userId);
    }

    @Test
    public void testOnUserStopped_notifiesICar() throws Exception {
        int userId = 10;

        mHelper.onUserStopped(newTargetUser(userId));

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_STOPPED, userId);
    }

    @Test
    public void testOnUserCompletedEvent_notifiesPostUnlockedEvent() throws Exception {
        int userId = 10;

        mHelper.onUserCompletedEvent(newTargetUser(userId), newUserCompletedEventTypeForTest(
                UserCompletedEventType.EVENT_TYPE_USER_UNLOCKED));

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_POST_UNLOCKED, userId);
    }

    @Test
    public void testGetMainDisplayAssignedToUser() throws Exception {
        when(mUserManagerInternal.getMainDisplayAssignedToUser(42)).thenReturn(108);

        assertWithMessage("getMainDisplayAssignedToUser(42)")
                .that(mHelper.getMainDisplayAssignedToUser(42)).isEqualTo(108);
    }

    @Test
    public void testGetUserAssignedToDisplay() throws Exception {
        when(mUserManagerInternal.getUserAssignedToDisplay(108)).thenReturn(42);

        assertWithMessage("getUserAssignedToDisplay(108)")
                .that(mHelper.getUserAssignedToDisplay(108)).isEqualTo(42);
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay() throws Exception {
        int userId = 100;
        int displayId = 2;

        mHelper.startUserInBackgroundVisibleOnDisplay(userId, displayId);

        verify(mActivityManager).startUserInBackgroundVisibleOnDisplay(userId, displayId);
    }

    @Test
    public void testFetchAidlVhalPid() throws Exception {
        int vhalPid = 5643;
        ServiceDebugInfo[] debugInfos = {
            newServiceDebugInfo(SAMPLE_AIDL_VHAL_INTERFACE_NAME, vhalPid),
            newServiceDebugInfo("some.service", 1234),
        };
        doReturn(debugInfos).when(() -> ServiceManager.getServiceDebugInfo());

        assertWithMessage("AIDL VHAL pid").that(mHelper.fetchAidlVhalPid()).isEqualTo(vhalPid);
    }

    @Test
    public void testFetchAidlVhalPid_missingAidlVhalService() throws Exception {
        ServiceDebugInfo[] debugInfos = {
            newServiceDebugInfo("random.service", 8535),
            newServiceDebugInfo("some.service", 1234),
        };
        doReturn(debugInfos).when(() -> ServiceManager.getServiceDebugInfo());

        assertWithMessage("AIDL VHAL pid").that(mHelper.fetchAidlVhalPid())
                .isEqualTo(INVALID_PID);
    }

    @Test
    public void testHandleClientsNotRespondingWithAnrMetricsFeatureDisabled() throws Exception {
        int testUid1 = 1001;
        int testUid2 = 1002;
        List<ProcessIdentifier> processIdentifiers = new ArrayList<ProcessIdentifier>();

        ProcessIdentifier processIdentifier1 = new ProcessIdentifier();
        processIdentifier1.processName = "name1";
        processIdentifier1.pid = 1;
        processIdentifier1.uid = testUid1;
        processIdentifier1.startTimeMillis = 1000;
        processIdentifiers.add(processIdentifier1);

        ProcessIdentifier processIdentifier2 = new ProcessIdentifier();
        processIdentifier2.processName = "name2";
        processIdentifier2.pid = 2;
        processIdentifier2.uid = testUid1;
        processIdentifier2.startTimeMillis = 2000;
        processIdentifiers.add(processIdentifier2);

        ProcessIdentifier processIdentifier3 = new ProcessIdentifier();
        processIdentifier3.processName = "name3";
        processIdentifier3.pid = 3;
        processIdentifier3.uid = testUid2;
        processIdentifier3.startTimeMillis = 3000;
        processIdentifiers.add(processIdentifier3);

        ProcessIdentifier processIdentifier4 = new ProcessIdentifier();
        processIdentifier4.processName = "name4";
        processIdentifier4.pid = 4;
        processIdentifier4.uid = testUid2;
        processIdentifier4.startTimeMillis = 4000;
        processIdentifiers.add(processIdentifier4);

        List<Integer> allTestPids = new ArrayList<>();
        for (ProcessIdentifier processIdentifier : processIdentifiers) {
            allTestPids.add(processIdentifier.pid);
        }

        doReturn(null).when(() -> StackTracesDumpHelper.dumpStackTraces(any(), any(), any(), any(),
                any(), any(), any()));
        doReturn(mMockPath).when(() -> Files.readSymbolicLink(any()));
        doReturn("/system/bin/app_process32").when(mMockPath).toString();

        mHelper.handleClientsNotResponding(processIdentifiers);

        verify(() -> StackTracesDumpHelper.dumpStackTraces(mDumpJavaPidCaptor.capture(), eq(null),
                eq(null), mDumpNativePidCaptor.capture(), eq(null), any(), eq(null)),
                timeout(MAX_WAIT_TIME_MS).times(1));
        verify(mCarWatchdogDaemonHelper, timeout(MAX_WAIT_TIME_MS).times(processIdentifiers.size()))
                .tellDumpFinished(any(), mProcessIdentifierCaptor.capture());

        List<ProcessIdentifier> allDumpFinishedProcessIdentifierValues =
                mProcessIdentifierCaptor.getAllValues();
        List<Integer> allDumpPidValues = new ArrayList<>();
        for (ArrayList<Integer> pids : mDumpJavaPidCaptor.getAllValues()) {
            allDumpPidValues.addAll(pids);
        }
        for (Future<ArrayList<Integer>> pids : mDumpNativePidCaptor.getAllValues()) {
            allDumpPidValues.addAll(pids.get());
        }

        assertWithMessage("ANRed processes dumped").that(allDumpPidValues)
                .containsAtLeastElementsIn(allTestPids);
        assertWithMessage("ANRed processes told dump finished")
                .that(allDumpFinishedProcessIdentifierValues)
                .containsExactlyElementsIn(processIdentifiers);
    }

    @Test
    public void testHandleClientsNotResponding() throws Exception {
        int testUid1 = 1001;
        int testUid2 = 1002;
        List<ProcessIdentifier> processIdentifiers = new ArrayList<ProcessIdentifier>();

        ProcessIdentifier processIdentifier1 = new ProcessIdentifier();
        processIdentifier1.processName = "name1";
        processIdentifier1.pid = 1;
        processIdentifier1.uid = testUid1;
        processIdentifier1.startTimeMillis = 1000;
        processIdentifiers.add(processIdentifier1);

        ProcessIdentifier processIdentifier2 = new ProcessIdentifier();
        processIdentifier2.processName = "name2";
        processIdentifier2.pid = 2;
        processIdentifier2.uid = testUid1;
        processIdentifier2.startTimeMillis = 2000;
        processIdentifiers.add(processIdentifier2);

        ProcessIdentifier processIdentifier3 = new ProcessIdentifier();
        processIdentifier3.processName = "name3";
        processIdentifier3.pid = 3;
        processIdentifier3.uid = testUid2;
        processIdentifier3.startTimeMillis = 3000;
        processIdentifiers.add(processIdentifier3);

        ProcessIdentifier processIdentifier4 = new ProcessIdentifier();
        processIdentifier4.processName = "name4";
        processIdentifier4.pid = 4;
        processIdentifier4.uid = testUid2;
        processIdentifier4.startTimeMillis = 4000;
        processIdentifiers.add(processIdentifier4);

        List<Integer> allTestPids = new ArrayList<>();
        for (ProcessIdentifier processIdentifier : processIdentifiers) {
            allTestPids.add(processIdentifier.pid);
        }

        ClientsNotRespondingInfo clientsNotRespondingInfo = new ClientsNotRespondingInfo();
        clientsNotRespondingInfo.processIdentifiers = processIdentifiers;
        clientsNotRespondingInfo.garageMode = GarageMode.GARAGE_MODE_ON;

        doReturn(null).when(() -> StackTracesDumpHelper.dumpStackTraces(any(), any(), any(), any(),
                any(), any(), any()));
        doReturn(mMockPath).when(() -> Files.readSymbolicLink(any()));
        doReturn("/system/bin/app_process32").when(mMockPath).toString();

        mHelper.handleClientsNotResponding(clientsNotRespondingInfo);

        verify(() -> StackTracesDumpHelper.dumpStackTraces(mDumpJavaPidCaptor.capture(), eq(null),
                eq(null), mDumpNativePidCaptor.capture(), eq(null), any(), eq(null)),
                timeout(MAX_WAIT_TIME_MS).times(1));
        verify(mCarWatchdogDaemonHelper, timeout(MAX_WAIT_TIME_MS).times(processIdentifiers.size()))
                .tellDumpFinished(any(), mProcessIdentifierCaptor.capture());

        List<ProcessIdentifier> allDumpFinishedProcessIdentifierValues =
                mProcessIdentifierCaptor.getAllValues();
        List<Integer> allDumpPidValues = new ArrayList<>();
        for (ArrayList<Integer> pids : mDumpJavaPidCaptor.getAllValues()) {
            allDumpPidValues.addAll(pids);
        }
        for (Future<ArrayList<Integer>> pids : mDumpNativePidCaptor.getAllValues()) {
            allDumpPidValues.addAll(pids.get());
        }

        assertWithMessage("ANRed processes dumped").that(allDumpPidValues)
                .containsAtLeastElementsIn(allTestPids);
        assertWithMessage("ANRed processes told dump finished")
                .that(allDumpFinishedProcessIdentifierValues)
                .containsExactlyElementsIn(processIdentifiers);

        captureAndVerifyKillStatsReported(
            new ArrayList<CarWatchdogKillStatsReported>(
                List.of(constructCarWatchdogKillStatsReported(
                            testUid1,
                            CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE,
                            CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__GARAGE_MODE,
                            CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_ANR,
                            CarServiceHelperService.constructCarWatchdogProcessStatsLocked(
                                List.of(processIdentifier1, processIdentifier2))),
                        constructCarWatchdogKillStatsReported(
                            testUid2,
                            CAR_WATCHDOG_KILL_STATS_REPORTED__UID_STATE__UNKNOWN_UID_STATE,
                            CAR_WATCHDOG_KILL_STATS_REPORTED__SYSTEM_STATE__GARAGE_MODE,
                            CAR_WATCHDOG_KILL_STATS_REPORTED__KILL_REASON__KILLED_ON_ANR,
                            CarServiceHelperService.constructCarWatchdogProcessStatsLocked(
                                List.of(processIdentifier3, processIdentifier4))))));
    }

    private void captureAndVerifyKillStatsReported(
            List<CarWatchdogKillStatsReported> expected) throws Exception {
        verify(() -> FrameworkStatsLog.write(eq(FrameworkStatsLog.CAR_WATCHDOG_KILL_STATS_REPORTED),
                mKilledUidCaptor.capture(), mUidStateCaptor.capture(),
                mSystemStateCaptor.capture(), mKillReasonCaptor.capture(),
                mKilledStatsCaptor.capture(), eq(null)),
                timeout(MAX_WAIT_TIME_MS).times(expected.size()));

        List<Integer> allUidValues = mKilledUidCaptor.getAllValues();
        List<Integer> allUidStateValues = mUidStateCaptor.getAllValues();
        List<Integer> allSystemStateValues = mSystemStateCaptor.getAllValues();
        List<Integer> allKillReasonValues = mKillReasonCaptor.getAllValues();
        List<byte[]> allProcessStats = mKilledStatsCaptor.getAllValues();
        List<CarWatchdogKillStatsReported> actual = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            actual.add(constructCarWatchdogKillStatsReported(allUidValues.get(i),
                    allUidStateValues.get(i), allSystemStateValues.get(i),
                    allKillReasonValues.get(i),
                    CarWatchdogProcessStats.parseFrom(
                        allProcessStats.get(i))));
        }
        assertWithMessage("ANR kill stats reported to statsd").that(actual)
            .containsExactlyElementsIn(expected);
    }

    private static CarWatchdogKillStatsReported constructCarWatchdogKillStatsReported(
            int uid, int uidState, int systemState, int killReason,
            CarWatchdogProcessStats processStats) {
        return CarWatchdogKillStatsReported.newBuilder()
                .setUid(uid)
                .setUidState(CarWatchdogKillStatsReported.UidState.forNumber(uidState))
                .setSystemState(CarWatchdogKillStatsReported.SystemState.forNumber(
                    systemState))
                .setKillReason(CarWatchdogKillStatsReported.KillReason.forNumber(
                    killReason))
                .setProcessStats(processStats)
                .build();
    }

    private TargetUser newTargetUser(int userId) {
        return newTargetUser(userId, /* preCreated= */ false);
    }

    private TargetUser newTargetUser(int userId, boolean preCreated) {
        TargetUser targetUser = mock(TargetUser.class);
        when(targetUser.getUserIdentifier()).thenReturn(userId);
        when(targetUser.getUserHandle()).thenReturn(UserHandle.of(userId));
        when(targetUser.isPreCreated()).thenReturn(preCreated);
        return targetUser;
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

    private void verifyICarOnUserLifecycleEventCalled(int eventType,
            @UserIdInt int fromId, @UserIdInt int toId) throws Exception {
        verify(mCarServiceHelperServiceUpdatable).sendUserLifecycleEvent(eventType,
                UserHandle.of(fromId), UserHandle.of(toId));
    }

    private void verifyICarOnUserLifecycleEventCalled(int eventType,
            @UserIdInt int userId) throws Exception {
        verify(mCarServiceHelperServiceUpdatable).sendUserLifecycleEvent(eventType,
                null, UserHandle.of(userId));
    }

    private ServiceDebugInfo newServiceDebugInfo(String name, int debugPid) {
        ServiceDebugInfo serviceDebugInfo = new ServiceDebugInfo();
        serviceDebugInfo.name = name;
        serviceDebugInfo.debugPid = debugPid;
        return serviceDebugInfo;
    }
}
