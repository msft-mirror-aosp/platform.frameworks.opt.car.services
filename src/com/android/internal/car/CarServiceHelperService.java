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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.CarProperties;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.UserIcons;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.SystemService.TargetUser;
import com.android.server.am.ActivityManagerService;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.CarLaunchParamsModifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * System service side companion service for CarService.
 * Starts car service and provide necessary API for CarService. Only for car product.
 */
public class CarServiceHelperService extends SystemService {
    // Place holder for user name of the first user created.
    private static final String TAG = "CarServiceHelper";
    private static final boolean DBG = true;
    private static final boolean VERBOSE = true;
    @VisibleForTesting static final String CAR_SERVICE_INTERFACE = "android.car.ICar";
    // These numbers should match with binder call order of
    // packages/services/Car/car-lib/src/android/car/ICar.aidl
    @VisibleForTesting static final int ICAR_CALL_SET_CAR_SERVICE_HELPER = 0;
    @VisibleForTesting static final int ICAR_CALL_ON_USER_LIFECYCLE = 1;
    @VisibleForTesting static final int ICAR_CALL_SET_USER_UNLOCK_STATUS = 2;
    @VisibleForTesting static final int ICAR_CALL_ON_SWITCH_USER = 3;

    // These constants should match CarUserManager
    @VisibleForTesting static final int USER_LIFECYCLE_EVENT_TYPE_STARTING = 1;
    @VisibleForTesting static final int USER_LIFECYCLE_EVENT_TYPE_SWITCHING = 2;
    @VisibleForTesting static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKING = 3;
    @VisibleForTesting static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKED = 4;
    @VisibleForTesting static final int USER_LIFECYCLE_EVENT_TYPE_STOPPING = 5;
    @VisibleForTesting static final int USER_LIFECYCLE_EVENT_TYPE_STOPPED = 6;

    // Typically there are ~2-5 ops while system and non-system users are starting.
    private final int NUMBER_PENDING_OPERATIONS = 5;

    private static final String PROP_RESTART_RUNTIME = "ro.car.recovery.restart_runtime.enabled";

    private static final List<String> CAR_HAL_INTERFACES_OF_INTEREST = Arrays.asList(
            "android.hardware.automotive.vehicle@2.0::IVehicle",
            "android.hardware.automotive.audiocontrol@1.0::IAudioControl"
    );

    @GuardedBy("mLock")
    private int mLastSwitchedUser = UserHandle.USER_NULL;

    private final ICarServiceHelperImpl mHelper = new ICarServiceHelperImpl();
    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IBinder mCarService;
    @GuardedBy("mLock")
    private boolean mSystemBootCompleted;

    @GuardedBy("mLock")
    private final HashMap<Integer, Boolean> mUserUnlockedStatus = new HashMap<>();
    private final CarUserManagerHelper mCarUserManagerHelper;
    private final UserManager mUserManager;
    private final String mDefaultUserName;
    private final IActivityManager mActivityManager;
    private final CarLaunchParamsModifier mCarLaunchParamsModifier;

    // TODO(b/146207078): rather than store Runnables, it would be more efficient to store some
    // parcelables representing the operation, then pass them to setCarServiceHelper
    @GuardedBy("mLock")
    private ArrayList<Runnable> mPendingOperations;

    private final ServiceConnection mCarServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            if (DBG) {
                Slog.d(TAG, "onServiceConnected:" + iBinder);
            }
            handleCarServiceConnection(iBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            handleCarServiceCrash();
        }
    };

    public CarServiceHelperService(Context context) {
        this(context,
                new CarUserManagerHelper(context),
                UserManager.get(context),
                ActivityManager.getService(),
                new CarLaunchParamsModifier(context),
                context.getString(com.android.internal.R.string.owner_name));
    }

    @VisibleForTesting
    CarServiceHelperService(
            Context context,
            CarUserManagerHelper carUserManagerHelper,
            UserManager userManager,
            IActivityManager activityManager,
            CarLaunchParamsModifier carLaunchParamsModifier,
            String defaultUserName) {
        super(context);
        mContext = context;
        mCarUserManagerHelper = carUserManagerHelper;
        mUserManager = userManager;
        mActivityManager = activityManager;
        mCarLaunchParamsModifier = carLaunchParamsModifier;
        mDefaultUserName = defaultUserName;
    }

    @Override
    public void onBootPhase(int phase) {
        if (DBG) {
            Slog.d(TAG, "onBootPhase:" + phase);
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            t.traceBegin("onBootPhase.3pApps");
            mCarLaunchParamsModifier.init();
            checkForCarServiceConnection(t);
            setupAndStartUsers(t);
            checkForCarServiceConnection(t);
            t.traceEnd();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            t.traceBegin("onBootPhase.completed");
            managePreCreatedUsers();
            boolean shouldNotify = false;
            synchronized (mLock) {
                mSystemBootCompleted = true;
                if (mCarService != null) {
                    shouldNotify = true;
                }
            }
            if (shouldNotify) {
                notifyAllUnlockedUsers();
            }
            t.traceEnd();
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
    public void onUserUnlocking(@NonNull TargetUser user) {
        Slog.i(TAG, "onUserUnlocking(" + user + ")");
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, user);
        // NOTE: handleUserLockStatusChange() should be called by onUserUnlocked(), but it will be
        // refactored anyways, so we kept the old behavior...
        int userId = user.getUserIdentifier();
        handleUserLockStatusChange(userId, true);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        Slog.i(TAG, "onUserUnlocked(" + user + ")");
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, user);
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        Slog.i(TAG, "onStartUser(" + user + ")");
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STARTING, user);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        Slog.i(TAG, "onStopUser(" + user + ")");
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STOPPING, user);
        int userId = user.getUserIdentifier();
        mCarLaunchParamsModifier.handleUserStopped(userId);
        handleUserLockStatusChange(userId, false);
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        Slog.i(TAG, "onCleanupUser(" + user + ")");
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STOPPED, user);
        int userId = user.getUserIdentifier();
        handleUserLockStatusChange(userId, false);
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        Slog.i(TAG, "onSwitchUser(" + from + ">>" + to + ")");
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING, from, to);
        int userId = to.getUserIdentifier();
        mCarLaunchParamsModifier.handleCurrentUserSwitching(userId);
        synchronized (mLock) {
            mLastSwitchedUser = userId;
            if (mCarService == null) {
                return;  // The event will be delivered upon CarService connection.
            }
        }
        sendSwitchUserBindercall(userId);
    }

    /**
     * Queues a binder operation so it's called when the service is connected.
     */
    private void queueOperationLocked(@NonNull Runnable operation) {
        if (mPendingOperations == null) {
            mPendingOperations = new ArrayList<>(NUMBER_PENDING_OPERATIONS);
        }
        mPendingOperations.add(operation);
    }

    // Sometimes car service onConnected call is delayed a lot. car service binder can be
    // found from ServiceManager directly. So do some polling during boot-up to connect to
    // car service ASAP.
    private void checkForCarServiceConnection(@NonNull TimingsTraceAndSlog t) {
        synchronized (mLock) {
            if (mCarService != null) {
                return;
            }
        }
        t.traceBegin("checkForCarServiceConnection");
        IBinder iBinder = ServiceManager.checkService("car_service");
        if (iBinder != null) {
            if (DBG) {
                Slog.d(TAG, "Car service found through ServiceManager:" + iBinder);
            }
            handleCarServiceConnection(iBinder);
        }
        t.traceEnd();
    }

    @VisibleForTesting void handleCarServiceConnection(IBinder iBinder) {
        int lastSwitchedUser;
        boolean systemBootCompleted;
        ArrayList<Runnable> pendingOperations;
        synchronized (mLock) {
            if (mCarService == iBinder) {
                return; // already connected.
            }
            if (mCarService != null) {
                Slog.i(TAG, "car service binder changed, was:" + mCarService
                        + " new:" + iBinder);
            }
            mCarService = iBinder;
            lastSwitchedUser = mLastSwitchedUser;
            systemBootCompleted = mSystemBootCompleted;
            pendingOperations = mPendingOperations;
            mPendingOperations = null;
        }
        Slog.i(TAG, "**CarService connected**");
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);

        t.traceBegin("send-set-helper");
        sendSetCarServiceHelperBinderCall();
        t.traceEnd();
        if (pendingOperations != null) {
            int numberOperations = pendingOperations.size();
            Slog.i(TAG, "Running " + numberOperations + " pending operations");
            t.traceBegin("send-pending-ops-" + numberOperations);
            for (int i = 0; i < numberOperations; i++) {
                Runnable operation = pendingOperations.get(i);
                try {
                    operation.run();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "exception running operation #" + i + ": " + e);
                }
            }
            t.traceEnd();
        }

        if (systemBootCompleted) {
            notifyAllUnlockedUsers();
        }
        if (lastSwitchedUser != UserHandle.USER_NULL) {
            t.traceBegin("send-switch-helper");
            sendSwitchUserBindercall(lastSwitchedUser);
            t.traceEnd();
        }
    }

    private void handleUserLockStatusChange(@UserIdInt int userId, boolean unlocked) {
        boolean shouldNotify = false;
        synchronized (mLock) {
            Boolean oldStatus = mUserUnlockedStatus.get(userId);
            if (oldStatus == null || oldStatus != unlocked) {
                mUserUnlockedStatus.put(userId, unlocked);
                if (mCarService != null && mSystemBootCompleted) {
                    shouldNotify = true;
                }
            }
        }
        if (shouldNotify) {
            sendSetUserLockStatusBinderCall(userId, unlocked);
        }
    }

    private void setupAndStartUsers(@NonNull TimingsTraceAndSlog t) {
        DevicePolicyManager devicePolicyManager =
                mContext.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager != null && devicePolicyManager.getUserProvisioningState()
                != DevicePolicyManager.STATE_USER_UNMANAGED) {
            Slog.i(TAG, "DevicePolicyManager active, skip user unlock/switch");
            return;
        }
        t.traceBegin("setupAndStartUsers");
        setupAndStartUsers(devicePolicyManager, t);
        t.traceEnd();
    }

    @Nullable
    private UserInfo createInitialAdminUser() {
        UserInfo adminUserInfo = mUserManager.createUser(mDefaultUserName, UserInfo.FLAG_ADMIN);
        if (adminUserInfo == null) {
            // Couldn't create user, most likely because there are too many.
            return null;
        }

        Bitmap bitmap = UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(mContext.getResources(), adminUserInfo.id, false));
        mUserManager.setUserIcon(adminUserInfo.id, bitmap);
        return adminUserInfo;
    }

    private boolean hasInitialSecondaryUser() {
        List<UserInfo> users = mUserManager.getUsers(/* excludeDying= */ true);
        boolean isHeadlessMode = UserManager.isHeadlessSystemUserMode();
        int size = users.size();

        for (int i = 0; i < size; i++) {
            int id = users.get(i).id;
            if (!mUserManager.isManagedProfile(id)) {
                if (isHeadlessMode && (id == UserHandle.USER_SYSTEM)) {
                    continue;
                }
                return true;
            }
        }

        return false;
    }

    private void setupAndStartUsers(@NonNull DevicePolicyManager devicePolicyManager,
            @NonNull TimingsTraceAndSlog t) {
        // Offloading the whole unlock into separate thread did not help due to single locks
        // used in AMS / PMS ended up stopping the world with lots of lock contention.
        // To run these in background, there should be some improvements there.
        int targetUserId;
        if (!hasInitialSecondaryUser()) {
            Slog.i(TAG, "Create new admin user and switch");
            // On very first boot, create an admin user and switch to that user.
            t.traceBegin("createNewAdminUser");
            UserInfo user = createInitialAdminUser();
            t.traceEnd();
            if (user == null) {
                Slog.e(TAG, "cannot create admin user");
                return;
            }
            targetUserId = user.id;
        } else {
            t.traceBegin("getInitialUser");
            targetUserId = mCarUserManagerHelper.getInitialUser();
            t.traceEnd();
            Slog.i(TAG, "Switching to user " + targetUserId + " on boot");
        }

        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            Slog.wtf(TAG, "cannot get ActivityManagerService");
            return;
        }

        // If system user is the only user to unlock, handle it when system completes the boot.
        if (targetUserId == UserHandle.USER_SYSTEM) {
            return;
        }

        unlockSystemUser(t);

        t.traceBegin("ForegroundUserStart" + targetUserId);
        try {
            if (!mActivityManager.startUserInForegroundWithListener(targetUserId, null)) {
                Slog.e(TAG, "cannot start foreground user:" + targetUserId);
            } else {
                mCarUserManagerHelper.setLastActiveUser(targetUserId);
            }
        } catch (RemoteException e) {
            // should not happen for local call.
            Slog.wtf("RemoteException from AMS", e);
        }
        t.traceEnd(); // ForegroundUserStart
    }

    private void unlockSystemUser(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("UnlockSystemUser");
        try {
            // This is for force changing state into RUNNING_LOCKED. Otherwise unlock does not
            // update the state and user 0 unlock happens twice.
            boolean started = mActivityManager.startUserInBackground(UserHandle.USER_SYSTEM);
            if (!started) {
                Slog.w(TAG, "could not restart system user in foreground; trying unlock instead");
                t.traceBegin("forceUnlockSystemUser");
                boolean unlocked = mActivityManager.unlockUser(UserHandle.USER_SYSTEM,
                        /* token= */ null, /* secret= */ null, /* listner= */ null);
                t.traceEnd();
                if (!unlocked) {
                    Slog.w(TAG, "could not unlock system user neither");
                    return;
                }
            }
        } catch (RemoteException e) {
            // should not happen for local call.
            Slog.wtf("RemoteException from AMS", e);
        } finally {
            t.traceEnd();
        }
    }

    private void managePreCreatedUsers() {

        // First gets how many pre-createad users are defined by the OEM
        int numberRequestedGuests = CarProperties.number_pre_created_guests().orElse(0);
        int numberRequestedUsers = CarProperties.number_pre_created_users().orElse(0);
        Slog.i(TAG, "managePreCreatedUsers(): OEM asked for " + numberRequestedGuests
                + " guests and " + numberRequestedUsers + " users");
        if (numberRequestedGuests < 0 || numberRequestedUsers < 0) {
            Slog.w(TAG, "preCreateUsers(): invalid values provided by OEM; "
                    + "number_pre_created_guests=" + numberRequestedGuests
                    + ", number_pre_created_users=" + numberRequestedUsers);
            return;
        }

        if (numberRequestedGuests == 0 && numberRequestedUsers == 0) {
            Slog.i(TAG, "managePreCreatedUsers(): not defined by OEM");
            return;
        }

        // Then checks how many exist already
        List<UserInfo> allUsers = mUserManager.getUsers(/* excludePartial= */ true,
                /* excludeDying= */ true, /* excludePreCreated= */ false);

        int allUsersSize = allUsers.size();
        if (DBG) Slog.d(TAG, "preCreateUsers: total users size is "  + allUsersSize);

        int numberExistingGuests = 0;
        int numberExistingUsers = 0;

        // List of pre-created users that were not properly initialized. Typically happens when
        // the system crashed / rebooted before they were fully started.
        SparseBooleanArray invalidUsers = new SparseBooleanArray();

        for (int i = 0; i < allUsersSize; i++) {
            UserInfo user = allUsers.get(i);
            if (!user.preCreated) continue;
            if (!user.isInitialized()) {
                Slog.w(TAG, "Found invalid pre-created user that needs to be removed: "
                        + user.toFullString());
                invalidUsers.append(user.id, /* notUsed=*/ true);
                continue;
            }
            if (user.isGuest()) {
                numberExistingGuests++;
            } else {
                numberExistingUsers++;
            }
        }
        if (DBG) {
            Slog.i(TAG, "managePreCreatedUsers(): system already has " + numberExistingGuests
                    + " pre-created guests," + numberExistingUsers + " pre-created users, and these"
                    + " invalid users: " + invalidUsers );
        }

        int numberGuests = numberRequestedGuests - numberExistingGuests;
        int numberUsers = numberRequestedUsers - numberExistingUsers;
        int numberInvalidUsers = invalidUsers.size();

        if (numberGuests <= 0 && numberUsers <= 0 && numberInvalidUsers == 0) {
            Slog.i(TAG, "managePreCreatedUsers(): all pre-created and no invalid ones");
            return;
        }

        // Finally, manage them....

        // In theory, we could submit multiple user pre-creations in parallel, but we're
        // submitting just 1 task, for 2 reasons:
        //   1.To minimize it's effect on other system server initialization tasks.
        //   2.The pre-created users will be unlocked in parallel anyways.
        new Thread( () -> {
            TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Async",
                    Trace.TRACE_TAG_SYSTEM_SERVER);

            t.traceBegin("preCreateUsers");
            if (numberUsers > 0) {
                preCreateUsers(t, numberUsers, /* isGuest= */ false);
            }
            if (numberGuests > 0) {
                preCreateUsers(t, numberGuests, /* isGuest= */ true);
            }
            t.traceEnd();

            if (numberInvalidUsers > 0) {
                t.traceBegin("removeInvalidPreCreatedUsers");
                for (int i = 0; i < numberInvalidUsers; i++) {
                    int userId = invalidUsers.keyAt(i);
                    Slog.i(TAG, "removing invalid pre-created user " + userId);
                    mUserManager.removeUser(userId);
                }
                t.traceEnd();
            }
        }, "CarServiceHelperManagePreCreatedUsers").start();
    }

    private void preCreateUsers(@NonNull TimingsTraceAndSlog t, int size, boolean isGuest) {
        String msg = isGuest ? "preCreateGuests-" + size : "preCreateUsers-" + size;
        t.traceBegin(msg);
        for (int i = 1; i <= size; i++) {
            UserInfo preCreated = preCreateUsers(t, isGuest);
            if (preCreated == null) {
                Slog.w(TAG, "Could not pre-create " + (isGuest ? " guest " : "")
                        + " user #" + i);
                continue;
            }
        }
        t.traceEnd();
    }

    // TODO(b/111451156): add unit test?
    @Nullable
    public UserInfo preCreateUsers(@NonNull TimingsTraceAndSlog t, boolean isGuest) {
        String traceMsg =  "pre-create" + (isGuest ? "-guest" : "-user");
        t.traceBegin(traceMsg);
        // NOTE: we want to get rid of UserManagerHelper, so let's call UserManager directly
        String userType =
                isGuest ? UserManager.USER_TYPE_FULL_GUEST : UserManager.USER_TYPE_FULL_SECONDARY;
        UserInfo user = mUserManager.preCreateUser(userType);
        try {
            if (user == null) {
                // Couldn't create user, most likely because there are too many.
                Slog.w(TAG, "couldn't " + traceMsg);
                return null;
            }
        } finally {
            t.traceEnd();
        }
        return user;
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
        if (DBG) {
            Slog.d(TAG, "notifyAllUnlockedUsers:" + users);
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

    private void sendSetUserLockStatusBinderCall(@UserIdInt int userId, boolean unlocked) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeInt(userId);
        data.writeInt(unlocked ? 1 : 0);
        // void setUserLockStatus(in int userId, in int unlocked)
        sendBinderCallToCarService(data, ICAR_CALL_SET_USER_UNLOCK_STATUS);
    }

    private void sendSwitchUserBindercall(@UserIdInt int userId) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeInt(userId);
        // void onSwitchUser(in int userId)
        sendBinderCallToCarService(data, ICAR_CALL_ON_SWITCH_USER);
    }

    private void sendUserLifecycleEvent(int eventType, @NonNull TargetUser user) {
        sendUserLifecycleEvent(eventType, /* from= */ null, user);
    }

    private void sendUserLifecycleEvent(int eventType, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        long now = System.currentTimeMillis();
        synchronized (mLock) {
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing lifecycle event " + eventType + " for user " + to);
                queueOperationLocked(() -> sendUserLifecycleEvent(eventType, now, from, to));
                return;
            }
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        t.traceBegin("send-lifecycle-" + eventType + "-" + to.getUserIdentifier());
        sendUserLifecycleEvent(eventType, now, from, to);
        t.traceEnd();
    }

    private void sendUserLifecycleEvent(int eventType, long timestamp, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        int fromId = from == null ? UserHandle.USER_NULL : from.getUserIdentifier();
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeInt(eventType);
        data.writeLong(timestamp);
        data.writeInt(fromId);
        data.writeInt(to.getUserIdentifier());
        // void onUserLifecycleEvent(int eventType, long timestamp, int from, int to)
        sendBinderCallToCarService(data, ICAR_CALL_ON_USER_LIFECYCLE);
    }

    private void sendBinderCallToCarService(Parcel data, int callNumber) {
        // Cannot depend on ICar which is defined in CarService, so handle binder call directly
        // instead.
        IBinder carService;
        synchronized (mLock) {
            carService = mCarService;
        }
        if (carService == null) {
            Slog.w(TAG, "Not calling txn " + callNumber + " because service is not bound yet",
                    new Exception());
            return;
        }
        int code = IBinder.FIRST_CALL_TRANSACTION + callNumber;
        try {
            if (VERBOSE) Slog.v(TAG, "calling one-way binder transaction with code " + code);
            carService.transact(code, data, null, Binder.FLAG_ONEWAY);
            if (VERBOSE) Slog.v(TAG, "finished one-way binder transaction with code " + code);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Exception calling binder transaction " + callNumber + " (real code: "
                    + code + ")", e);
            throw e;
        } finally {
            data.recycle();
        }
    }

    // Adapted from frameworks/base/services/core/java/com/android/server/Watchdog.java
    // TODO(b/131861630) use implementation common with Watchdog.java
    //
    private static ArrayList<Integer> getInterestingHalPids() {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            ArrayList<IServiceManager.InstanceDebugInfo> dump =
                    serviceManager.debugDump();
            HashSet<Integer> pids = new HashSet<>();
            for (IServiceManager.InstanceDebugInfo info : dump) {
                if (info.pid == IServiceManager.PidConstant.NO_PID) {
                    continue;
                }

                if (Watchdog.HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName) ||
                        CAR_HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName)) {
                    pids.add(info.pid);
                }
            }

            return new ArrayList<Integer>(pids);
        } catch (RemoteException e) {
            return new ArrayList<Integer>();
        }
    }

    // Adapted from frameworks/base/services/core/java/com/android/server/Watchdog.java
    // TODO(b/131861630) use implementation common with Watchdog.java
    //
    private static ArrayList<Integer> getInterestingNativePids() {
        ArrayList<Integer> pids = getInterestingHalPids();

        int[] nativePids = Process.getPidsForCommands(Watchdog.NATIVE_STACKS_OF_INTEREST);
        if (nativePids != null) {
            pids.ensureCapacity(pids.size() + nativePids.length);
            for (int i : nativePids) {
                pids.add(i);
            }
        }

        return pids;
    }

    // Borrowed from Watchdog.java.  Create an ANR file from the call stacks.
    //
    private static void dumpServiceStacks() {
        ArrayList<Integer> pids = new ArrayList<>();
        pids.add(Process.myPid());

        ActivityManagerService.dumpStackTraces(
                pids, null, null, getInterestingNativePids());
    }

    private void handleCarServiceCrash() {
        // Recovery behavior.  Kill the system server and reset
        // everything if enabled by the property.
        boolean restartOnServiceCrash = SystemProperties.getBoolean(PROP_RESTART_RUNTIME, false);

        dumpServiceStacks();
        if (restartOnServiceCrash) {
            Slog.w(TAG, "*** CARHELPER KILLING SYSTEM PROCESS: " + "CarService crash");
            Slog.w(TAG, "*** GOODBYE!");
            Process.killProcess(Process.myPid());
            System.exit(10);
        } else {
            Slog.w(TAG, "*** CARHELPER ignoring: " + "CarService crash");
        }
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

        @Override
        public void setDisplayWhitelistForUser(@UserIdInt int userId, int[] displayIds) {
            mCarLaunchParamsModifier.setDisplayWhitelistForUser(userId, displayIds);
        }

        @Override
        public void setPassengerDisplays(int[] displayIdsForPassenger) {
            mCarLaunchParamsModifier.setPassengerDisplays(displayIdsForPassenger);
        }
    }
}
