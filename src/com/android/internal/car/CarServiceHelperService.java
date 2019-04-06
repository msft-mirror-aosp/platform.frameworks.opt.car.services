/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimingsTraceLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * System service side companion service for CarService.
 * Starts car service and provide necessary API for CarService. Only for car product.
 */
public class CarServiceHelperService extends SystemService {
    // Place holder for user name of the first user created.
    private static final String TAG = "CarServiceHelper";
    private static final boolean DBG = true;
    private static final String CAR_SERVICE_INTERFACE = "android.car.ICar";
    // These numbers should match with binder call order of
    // packages/services/Car/car-lib/src/android/car/ICar.aidl
    private static final int ICAR_CALL_SET_CAR_SERVICE_HELPER = 0;
    private static final int ICAR_CALL_SET_USER_UNLOCK_STATUS = 1;
    private static final int ICAR_CALL_SET_SWITCH_USER = 2;

    @GuardedBy("mLock")
    private int mLastSwitchedUser = UserHandle.USER_NULL;

    private final ICarServiceHelperImpl mHelper = new ICarServiceHelperImpl();
    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IBinder mCarService;
    @GuardedBy("mLock")
    private final HashMap<Integer, Boolean> mUserUnlockedStatus = new HashMap<>();
    private final CarUserManagerHelper mCarUserManagerHelper;
    private final ServiceConnection mCarServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Slog.i(TAG, "**CarService connected**");

            int lastSwitchedUser;
            synchronized (mLock) {
                mCarService = iBinder;
                lastSwitchedUser = mLastSwitchedUser;
            }
            sendSetCarServiceHelperBinderCall();
            notifyAllUnlockedUsers();
            if (lastSwitchedUser != UserHandle.USER_NULL) {
                sendSwitchUserBindercall(lastSwitchedUser);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            handleCarServiceCrash();
        }
    };

    public CarServiceHelperService(Context context) {
        this(context, new CarUserManagerHelper(context));
    }

    @VisibleForTesting
    CarServiceHelperService(Context context, CarUserManagerHelper carUserManagerHelper) {
        super(context);
        mContext = context;
        mCarUserManagerHelper = carUserManagerHelper;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            // TODO(b/126199560) Consider doing this earlier in onStart().
            // Other than onStart, PHASE_THIRD_PARTY_APPS_CAN_START is the earliest timing.
            setupAndStartUsers();
        }
    }

    @Override
    public void onStart() {
        Intent intent = new Intent();
        intent.setPackage("com.android.car");
        intent.setAction(CAR_SERVICE_INTERFACE);
        if (!getContext().bindServiceAsUser(intent, mCarServiceConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM)) {
            Slog.wtf(TAG, "cannot start car service");
        }
        System.loadLibrary("car-framework-service-jni");
    }


    @Override
    public void onUnlockUser(int userHandle) {
        handleUserLockStatusChange(userHandle, true);
        if (DBG) {
            Slog.d(TAG, "User" + userHandle + " unlocked");
        }
    }

    @Override
    public void onStopUser(int userHandle) {
        handleUserLockStatusChange(userHandle, false);
    }

    @Override
    public void onCleanupUser(int userHandle) {
        handleUserLockStatusChange(userHandle, false);
    }

    @Override
    public void onSwitchUser(int userHandle) {
        synchronized (mLock) {
            mLastSwitchedUser = userHandle;
            if (mCarService == null) {
                return;  // The event will be delivered upon CarService connection.
            }
        }
        sendSwitchUserBindercall(userHandle);
    }

    private void handleUserLockStatusChange(int userHandle, boolean unlocked) {
        boolean shouldNotify = false;
        synchronized (mLock) {
            Boolean oldStatus = mUserUnlockedStatus.get(userHandle);
            if (oldStatus == null || oldStatus != unlocked) {
                mUserUnlockedStatus.put(userHandle, unlocked);
                if (mCarService != null) {
                    shouldNotify = true;
                }
            }
        }
        if (shouldNotify) {
            sendSetUserLockStatusBinderCall(userHandle, unlocked);
        }
    }

    private void setupAndStartUsers() {
        DevicePolicyManager devicePolicyManager =
                mContext.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager != null && devicePolicyManager.getUserProvisioningState()
                != DevicePolicyManager.STATE_USER_UNMANAGED) {
            Slog.i(TAG, "DevicePolicyManager active, skip user unlock/switch");
            return;
        }
        // Offloading the whole unlock into separate thread did not help due to single locks
        // used in AMS / PMS ended up stopping the world with lots of lock contention.
        // To run these in background, there should be some improvements there.
        int targetUserId = UserHandle.USER_SYSTEM;
        if (SystemProperties.getBoolean("android.car.systemuser.defaultguest", false)) {
            Slog.i(TAG, "Start guest user mode");
            // Ensure there is an admin user on the device
            if (mCarUserManagerHelper.getAllAdminUsers().isEmpty()) {
                Slog.i(TAG, "Create new admin user");
                UserInfo admin = mCarUserManagerHelper.createNewAdminUser();
            }
            // Ensure we switch to the guest user by default
            // TODO(b/122852856): Localize this string
            UserInfo guest = mCarUserManagerHelper.createNewOrFindExistingGuest("Guest");
            if (guest == null) {
                Slog.e(TAG, "cannot create or find guest user");
                return;
            }
            targetUserId = guest.id;
        } else {
            if (mCarUserManagerHelper.getAllUsers().size() == 0) {
                Slog.i(TAG, "Create new admin user and switch");
                // On very first boot, create an admin user and switch to that user.
                UserInfo admin = mCarUserManagerHelper.createNewAdminUser();
                if (admin == null) {
                    Slog.e(TAG, "cannot create admin user");
                    return;
                }
                targetUserId = admin.id;
            } else {
                Slog.i(TAG, "Switch to default user");
                targetUserId = mCarUserManagerHelper.getInitialUser();
            }
        }
        // If system user is the only user to unlock, handle it when system completes the boot.
        if (targetUserId == UserHandle.USER_SYSTEM) {
            return;
        }
        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            Slog.wtf(TAG, "cannot get ActivityManagerService");
            return;
        }
        TimingsTraceLog traceLog = new TimingsTraceLog("SystemServerTiming",
                Trace.TRACE_TAG_ACTIVITY_MANAGER);
        traceLog.traceBegin("User0Unlock");
        try {
            // This is for force changing state into RUNNING_LOCKED. Otherwise unlock does not
            // update the state and user 0 unlock happens twice.
            if (!am.startUserInBackground(UserHandle.USER_SYSTEM)) {
                // cannot start user
                Slog.w(TAG, "cannot start system user");
            } else if (!am.unlockUser(UserHandle.USER_SYSTEM, null, null, null)) {
                // unlocking system user failed. But still continue for other setup.
                Slog.w(TAG, "cannot unlock system user");
            } else {
                // user 0 started and unlocked
                handleUserLockStatusChange(UserHandle.USER_SYSTEM, true);
            }
        } catch (RemoteException e) {
            // should not happen for local call.
            Slog.wtf("RemoteException from AMS", e);
        }
        traceLog.traceEnd();
        // Do not unlock here to allow other stuffs done. Unlock will happen
        // when system completes the boot.
        // TODO(b/124460424) Unlock earlier?
        try {
            if (!am.startUserInForegroundWithListener(targetUserId, null)) {
                Slog.e(TAG, "cannot start foreground user:" + targetUserId);
            } else {
                mCarUserManagerHelper.setLastActiveUser(targetUserId);
            }
        } catch (RemoteException e) {
            // should not happen for local call.
            Slog.wtf("RemoteException from AMS", e);
        }
    }


    private void notifyAllUnlockedUsers() {
        // only care about unlocked users
        LinkedList<Integer> users = new LinkedList<>();
        synchronized (mLock) {
            for (Map.Entry<Integer, Boolean> entry : mUserUnlockedStatus.entrySet()) {
                if (entry.getValue()) {
                    users.add(entry.getKey());
                }
            }
        }
        for (Integer i : users) {
            sendSetUserLockStatusBinderCall(i, true);
        }
    }

    private void sendSetCarServiceHelperBinderCall() {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeStrongBinder(mHelper.asBinder());
        // void setCarServiceHelper(in IBinder helper)
        sendBinderCallToCarService(data, ICAR_CALL_SET_CAR_SERVICE_HELPER);
    }

    private void sendSetUserLockStatusBinderCall(int userHandle, boolean unlocked) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeInt(userHandle);
        data.writeInt(unlocked ? 1 : 0);
        // void setUserLockStatus(in int userHandle, in int unlocked)
        sendBinderCallToCarService(data, ICAR_CALL_SET_USER_UNLOCK_STATUS);
    }

    private void sendSwitchUserBindercall(int userHandle) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeInt(userHandle);
        // void onSwitchUser(in int userHandle)
        sendBinderCallToCarService(data, ICAR_CALL_SET_SWITCH_USER);
    }

    private void sendBinderCallToCarService(Parcel data, int callNumber) {
        // Cannot depend on ICar which is defined in CarService, so handle binder call directly
        // instead.
        IBinder carService;
        synchronized (mLock) {
            carService = mCarService;
        }
        try {
            carService.transact(IBinder.FIRST_CALL_TRANSACTION + callNumber,
                    data, null, Binder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        } finally {
            data.recycle();
        }
    }

    private void handleCarServiceCrash() {
        //TODO define recovery behavior
    }

    private static native int nativeForceSuspend(int timeoutMs);

    private class ICarServiceHelperImpl extends ICarServiceHelper.Stub {
        /**
         * Force device to suspend
         */
        @Override // Binder call
        public int forceSuspend(int timeoutMs) {
            int retVal;
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
            final long ident = Binder.clearCallingIdentity();
            try {
                retVal = nativeForceSuspend(timeoutMs);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return retVal;
        }
    }
}
