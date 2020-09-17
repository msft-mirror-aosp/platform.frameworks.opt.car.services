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

import static android.car.constants.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.pm.UserInfo;
import android.os.RemoteException;

import com.android.car.internal.ICarSystemServerClient;
import com.android.server.SystemService.TargetUser;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class CarServiceProxyTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private CarServiceHelperService mCarServiceHelperService;
    @Mock
    private ICarSystemServerClient mCarService;

    private TargetUser mFromUser = new TargetUser(new UserInfo(101, "fromUser", 0));
    private TargetUser mToUser = new TargetUser(new UserInfo(102, "toUser", 0));

    private CarServiceProxy mCarServiceProxy;

    @Before
    public void setUpMocks() {
        mCarServiceProxy = new CarServiceProxy(mCarServiceHelperService);
    }

    @Test
    public void testInitBootUser_CarServiceNotNull() throws RemoteException {
        connectToCarService();

        callInitBootUser();

        verifyInitBootUserCalled();
    }

    @Test
    public void testInitBootUser_CarServiceNull() throws RemoteException {
        callInitBootUser();

        verifyInitBootUserNeverCalled();
    }

    @Test
    public void testPreCreateUsers_CarServiceNotNull() throws RemoteException {
        connectToCarService();

        callPreCreateUsers();

        verifyPreCreateUsersCalled();
    }

    @Test
    public void testPreCreateUsers_CarServiceNull() throws RemoteException {
        callPreCreateUsers();

        verifyPreCreateUsersNeverCalled();
    }

    @Test
    public void testSendUserLifecycleEvent_CarServiceNotNull() throws RemoteException {
        connectToCarService();

        callSendLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING);

        verifySendLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    @Test
    public void testSendUserLifecycleEvent_CarServiceNull() throws RemoteException {
        callSendLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING);

        verifySendLifecycleEventNeverCalled();
    }

    @Test
    public void testhandleCarServiceConnection() throws RemoteException {
        callInitBootUser();
        callPreCreateUsers();
        callSendLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
        verifyInitBootUserNeverCalled();
        verifyPreCreateUsersNeverCalled();
        verifySendLifecycleEventNeverCalled();

        connectToCarService();

        verifyInitBootUserCalled();
        verifyPreCreateUsersCalled();
        verifySendLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    private void connectToCarService() {
        mCarServiceProxy.handleCarServiceConnection(mCarService);
    }

    private void callInitBootUser() {
        mCarServiceProxy.initBootUser();
    }

    private void callPreCreateUsers() {
        mCarServiceProxy.preCreateUsers();
    }

    private void callSendLifecycleEvent(int eventType) {
        mCarServiceProxy.sendUserLifecycleEvent(eventType, mFromUser, mToUser);
    }

    private void verifyInitBootUserCalled() throws RemoteException {
        verify(mCarService).initBootUser();
    }

    private void verifyInitBootUserNeverCalled() throws RemoteException {
        verify(mCarService, never()).initBootUser();
    }

    private void verifyPreCreateUsersCalled() throws RemoteException {
        verify(mCarService).preCreateUsers();
    }

    private void verifyPreCreateUsersNeverCalled() throws RemoteException {
        verify(mCarService, never()).preCreateUsers();
    }

    private void verifySendLifecycleEventCalled(int eventType) throws RemoteException {
        verify(mCarService).onUserLifecycleEvent(eventType,
                mFromUser.getUserIdentifier(), mToUser.getUserIdentifier());
    }

    private void verifySendLifecycleEventNeverCalled() throws RemoteException {
        verify(mCarService, never()).onUserLifecycleEvent(anyInt(), anyInt(), anyInt());
    }
}
