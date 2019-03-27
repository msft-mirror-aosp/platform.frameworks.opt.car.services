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
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.car.ICarServiceHelper;
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
    private static final String CAR_SERVICE_INTERFACE = "android.car.ICar";
    // These numbers should match with binder call order of
    // packages/services/Car/car-lib/src/android/car/ICar.aidl
    private static final int ICAR_CALL_SET_CAR_SERVICE_HELPER = 0;
    private static final int ICAR_CALL_SET_USER_UNLOCK_STATUS = 1;

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
            synchronized (mLock) {
                mCarService = iBinder;
            }
            sendSetCarServiceHelperBinderCall();
            notifyAllUnlockedUsers();
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
        // We are starting up another user before PHASE_BOOT_COMPLETE. Services should
        // still check for User 0's boot complete intent.
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            DevicePolicyManager devicePolicyManager =
                    mContext.getSystemService(DevicePolicyManager.class);
            if (devicePolicyManager != null && devicePolicyManager.getUserProvisioningState()
                    != DevicePolicyManager.STATE_USER_UNMANAGED) {
                return;
            }

            if (SystemProperties.getBoolean("android.car.systemuser.defaultguest", false)) {
                // Ensure we switch to the guest user by default
                // TODO(b/122852856): Localize this string
                mCarUserManagerHelper.startGuestSession("Guest");

                // Ensure there is an admin user on the device
                if (mCarUserManagerHelper.getAllAdminUsers().isEmpty()) {
                    UserInfo admin = mCarUserManagerHelper.createNewAdminUser();
                }
            } else {
                if (mCarUserManagerHelper.getAllUsers().size() == 0) {
                    // On very first boot, create an admin user and switch to that user.
                    UserInfo admin = mCarUserManagerHelper.createNewAdminUser();
                    mCarUserManagerHelper.switchToUserId(admin.id);
                    mCarUserManagerHelper.setLastActiveUser(admin.id);
                } else {
                    mCarUserManagerHelper.switchToUserId(mCarUserManagerHelper.getInitialUser());
                }
            }
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
    }

    @Override
    public void onStopUser(int userHandle) {
        handleUserLockStatusChange(userHandle, false);
    }

    @Override
    public void onCleanupUser(int userHandle) {
        handleUserLockStatusChange(userHandle, false);
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
        //TODO define recovery bahavior
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
