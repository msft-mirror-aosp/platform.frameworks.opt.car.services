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

import static com.android.car.internal.CommonConstants.CAR_SERVICE_INTERFACE;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING;
import static com.android.car.internal.SystemConstants.ICAR_SYSTEM_SERVER_CLIENT;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.automotive.watchdog.ICarWatchdogMonitor;
import android.automotive.watchdog.PowerCycle;
import android.automotive.watchdog.StateType;
import android.car.userlib.CarUserManagerHelper;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TimeUtils;

import com.android.car.internal.EventLogTags;
import com.android.car.internal.ICarServiceHelper;
import com.android.car.internal.ICarSystemServerClient;
import com.android.car.internal.UserHelperLite;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.CarLaunchParamsModifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System service side companion service for CarService. Starts car service and provide necessary
 * API for CarService. Only for car product.
 */
public class CarServiceHelperService extends SystemService {

    static final String DUMP_SERVICE = "car_service_server";

    private static final String TAG = "CarServiceHelper";

    // TODO(b/154033860): STOPSHIP if they're still true
    private static final boolean DBG = true;
    private static final boolean VERBOSE = true;

    private static final String PROP_RESTART_RUNTIME = "ro.car.recovery.restart_runtime.enabled";

    private static final List<String> CAR_HAL_INTERFACES_OF_INTEREST = Arrays.asList(
            "android.hardware.automotive.vehicle@2.0::IVehicle",
            "android.hardware.automotive.audiocontrol@1.0::IAudioControl",
            "android.hardware.automotive.audiocontrol@2.0::IAudioControl"
    );

    // Message ID representing post-processing of process dumping.
    private static final int WHAT_POST_PROCESS_DUMPING = 1;
    // Message ID representing process killing.
    private static final int WHAT_PROCESS_KILL = 2;
    // Message ID representing service unresponsiveness.
    private static final int WHAT_SERVICE_UNRESPONSIVE = 3;

    private static final long CAR_SERVICE_BINDER_CALL_TIMEOUT = 15_000;

    private static final long LIFECYCLE_TIMESTAMP_IGNORE = 0;

    // Typically there are ~2-5 ops while system and non-system users are starting.
    private final int NUMBER_PENDING_OPERATIONS = 5;

    @UserIdInt
    @GuardedBy("mLock")
    private int mLastSwitchedUser = UserHandle.USER_NULL;

    private final ICarServiceHelperImpl mHelper = new ICarServiceHelperImpl();
    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IBinder mCarServiceBinder;
    @GuardedBy("mLock")
    private ICarSystemServerClient mCarService;
    @GuardedBy("mLock")
    private boolean mSystemBootCompleted;

    // Key: user id, value: lifecycle
    @GuardedBy("mLock")
    private final SparseIntArray mLastUserLifecycle = new SparseIntArray();

    private final CarUserManagerHelper mCarUserManagerHelper;
    private final UserManager mUserManager;
    private final CarLaunchParamsModifier mCarLaunchParamsModifier;

    private final Handler mHandler;
    private final HandlerThread mHandlerThread = new HandlerThread("CarServiceHelperService");

    private final ProcessTerminator mProcessTerminator = new ProcessTerminator();
    private final CarServiceConnectedCallback mCarServiceConnectedCallback =
            new CarServiceConnectedCallback();

    /**
     * End-to-end time (from process start) for unlocking the first non-system user.
     */
    private long mFirstUnlockedUserDuration;

    // TODO(b/150413515): rather than store Runnables, it would be more efficient to store some
    // parcelables representing the operation, then pass them to setCarServiceHelper
    @GuardedBy("mLock")
    private ArrayList<Runnable> mPendingOperations;

    @GuardedBy("mLock")
    private boolean mCarServiceHasCrashed;

    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final ICarWatchdogMonitorImpl mCarWatchdogMonitor = new ICarWatchdogMonitorImpl(this);
    private final CarWatchdogDaemonHelper.OnConnectionChangeListener mConnectionListener =
            (connected) -> {
                if (connected) {
                    registerMonitorToWatchdogDaemon();
                }
            };

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

    private final BroadcastReceiver mShutdownEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Skip immediately if intent is not relevant to device shutdown.
            // FLAG_RECEIVER_FOREGROUND is checked to ignore the intent from UserController when
            // a user is stopped.
            if ((!intent.getAction().equals(Intent.ACTION_REBOOT)
                    && !intent.getAction().equals(Intent.ACTION_SHUTDOWN))
                    || (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) == 0) {
                return;
            }
            int powerCycle = PowerCycle.POWER_CYCLE_SUSPEND;
            try {
                mCarWatchdogDaemonHelper.notifySystemStateChange(StateType.POWER_CYCLE,
                        powerCycle, /* arg2= */ 0);
                if (DBG) {
                    Slog.d(TAG, "Notified car watchdog daemon a power cycle(" + powerCycle + ")");
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "Notifying system state change failed: " + e);
            }
        }
    };

    public CarServiceHelperService(Context context) {
        this(context,
                new CarUserManagerHelper(context),
                UserManager.get(context),
                new CarLaunchParamsModifier(context),
                new CarWatchdogDaemonHelper(TAG)
        );
    }

    @VisibleForTesting
    CarServiceHelperService(
            Context context,
            CarUserManagerHelper userManagerHelper,
            UserManager userManager,
            CarLaunchParamsModifier carLaunchParamsModifier,
            CarWatchdogDaemonHelper carWatchdogDaemonHelper) {
        super(context);
        mContext = context;
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mCarUserManagerHelper = userManagerHelper;
        mUserManager = userManager;
        mCarLaunchParamsModifier = carLaunchParamsModifier;
        mCarWatchdogDaemonHelper = carWatchdogDaemonHelper;
    }

    @Override
    public void onBootPhase(int phase) {
        EventLog.writeEvent(EventLogTags.CAR_HELPER_BOOT_PHASE, phase);
        if (DBG) Slog.d(TAG, "onBootPhase:" + phase);

        TimingsTraceAndSlog t = newTimingsTraceAndSlog();
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            t.traceBegin("onBootPhase.3pApps");
            mCarLaunchParamsModifier.init();
            setupAndStartUsers(t);
            t.traceEnd();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            t.traceBegin("onBootPhase.completed");
            preCreateUsers();
            synchronized (mLock) {
                mSystemBootCompleted = true;
            }
            try {
                mCarWatchdogDaemonHelper.notifySystemStateChange(
                        StateType.BOOT_PHASE, phase, /* arg2= */ 0);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "Failed to notify boot phase change: " + e);
            }
            t.traceEnd();
        }
    }

    @Override
    public void onStart() {
        EventLog.writeEvent(EventLogTags.CAR_HELPER_START);

        IntentFilter filter = new IntentFilter(Intent.ACTION_REBOOT);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiverForAllUsers(mShutdownEventReceiver, filter, null, null);
        mCarWatchdogDaemonHelper.addOnConnectionChangeListener(mConnectionListener);
        mCarWatchdogDaemonHelper.connect();
        Intent intent = new Intent();
        intent.setPackage("com.android.car");
        intent.setAction(CAR_SERVICE_INTERFACE);
        if (!mContext.bindServiceAsUser(intent, mCarServiceConnection, Context.BIND_AUTO_CREATE,
                mHandler, UserHandle.SYSTEM)) {
            Slog.wtf(TAG, "cannot start car service");
        }
        loadNativeLibrary();

        // Register a binder for dumping CarServiceHelperService state
        ServiceManager.addService(DUMP_SERVICE, new Binder() {
            @Override
            protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
                writer.printf("*Dump car service*\n");
                writer.printf("mSystemBootCompleted=%s\n", mSystemBootCompleted);
                writer.printf("mFirstUnlockedUserDuration=%s\n", mFirstUnlockedUserDuration);
                int count = 0;
                if (mPendingOperations != null) {
                    count = mPendingOperations.size();
                }
                writer.printf("mPendingOperations Count=%s\n", count);
                writer.printf("mCarServiceHasCrashed=%s\n", mCarServiceHasCrashed);
                writer.printf("mLastSwitchedUser=%s\n", mLastSwitchedUser);
                writer.printf("mLastUserLifecycle:\n");
                String indent = "    ";
                int user0Lifecycle = mLastUserLifecycle.get(UserHandle.USER_SYSTEM, 0);
                if (user0Lifecycle != 0) {
                    writer.printf("%sSystemUser Lifecycle Event:%s\n", indent, user0Lifecycle);
                } else {
                    writer.printf("%sSystemUser not initialized\n", indent);
                }

                int lastUserLifecycle = mLastUserLifecycle.get(mLastSwitchedUser, 0);
                if (mLastSwitchedUser != UserHandle.USER_SYSTEM && user0Lifecycle != 0) {
                    writer.printf("%slast user (%s) Lifecycle Event:%s\n", indent,
                            mLastSwitchedUser, lastUserLifecycle);
                }
            }

        });
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_UNLOCKING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_UNLOCKING, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserUnlocking(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, user);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_UNLOCKED)) return;
        int userId = user.getUserIdentifier();
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_UNLOCKED, userId);
        if (DBG) Slog.d(TAG, "onUserUnlocked(" + user + ")");

        if (mFirstUnlockedUserDuration == 0 && !UserHelperLite.isHeadlessSystemUser(userId)) {
            mFirstUnlockedUserDuration = SystemClock.elapsedRealtime()
                    - Process.getStartElapsedRealtime();
            Slog.i(TAG, "Time to unlock 1st user(" + user + "): "
                    + TimeUtils.formatDuration(mFirstUnlockedUserDuration));
            synchronized (mLock) {
                mLastUserLifecycle.put(userId, USER_LIFECYCLE_EVENT_TYPE_UNLOCKED);
            }
        }
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, user);
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_STARTING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_STARTING, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserStarting(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STARTING, user);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_STOPPING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_STOPPING, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserStopping(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STOPPING, user);
        int userId = user.getUserIdentifier();
        mCarLaunchParamsModifier.handleUserStopped(userId);
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_STOPPED)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_STOPPED, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserStopped(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STOPPED, user);
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (isPreCreated(to, USER_LIFECYCLE_EVENT_TYPE_SWITCHING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_SWITCHING,
                from == null ? UserHandle.USER_NULL : from.getUserIdentifier(),
                to.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserSwitching(" + from + ">>" + to + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING, from, to);
        int userId = to.getUserIdentifier();
        mCarLaunchParamsModifier.handleCurrentUserSwitching(userId);
    }

    @VisibleForTesting
    void loadNativeLibrary() {
        System.loadLibrary("car-framework-service-jni");
    }

    private boolean isPreCreated(@NonNull TargetUser user, int eventType) {
        UserInfo userInfo = user.getUserInfo();
        if (userInfo == null) {
            // Happens in some race conditions on stop, where only the UserHandle is available.
            Slog.w(TAG, "no UserInfo on " + user + " on eventType " + eventType);
            return false;
        }
        if (!userInfo.preCreated) return false;

        if (DBG) {
            Slog.d(TAG, "Ignoring event of type " + eventType + " for pre-created user "
                    + userInfo.toFullString());
        }
        return true;
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

    @VisibleForTesting
    void handleCarServiceConnection(IBinder iBinder) {
        synchronized (mLock) {
            if (mCarServiceBinder == iBinder) {
                return; // already connected.
            }
            Slog.i(TAG, "car service binder changed, was:" + mCarServiceBinder + " new:" + iBinder);
            mCarServiceBinder = iBinder;
            Slog.i(TAG, "**CarService connected**");
        }

        sendSetSystemServerConnectionsCall();

        mHandler.removeMessages(WHAT_SERVICE_UNRESPONSIVE);
        mHandler.sendMessageDelayed(
                obtainMessage(CarServiceHelperService::handleCarServiceUnresponsive, this)
                        .setWhat(WHAT_SERVICE_UNRESPONSIVE), CAR_SERVICE_BINDER_CALL_TIMEOUT);
    }

    private void sendAllLifecyleToUser(@UserIdInt int userId, int lifecycle,
            boolean isCurrentUser) {
        if (DBG) {
            Slog.d(TAG, "sendAllLifecyleToUser, user:" + userId + " lifecycle:" + lifecycle);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_STARTING) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STARTING, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_NULL, userId);
        }
        if (isCurrentUser && userId != UserHandle.USER_SYSTEM) {
            // Do not care about actual previous user.
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_SYSTEM, userId);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_UNLOCKING) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_NULL, userId);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_UNLOCKED) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_NULL, userId);
        }
    }

    private TimingsTraceAndSlog newTimingsTraceAndSlog() {
        return new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
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
        initBootUser();
        t.traceEnd();
    }

    @VisibleForTesting
    void initBootUser() {
        synchronized (mLock) {
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing startInitialUser() call");
                queueOperationLocked(() -> sendInitBootUser());
                return;
            }
        }
        sendInitBootUser();
    }

    private void sendInitBootUser() {
        ICarSystemServerClient carService;
        synchronized (mLock) {
            carService = mCarService;
        }
        try {
            carService.initBootUser();
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        }
    }

    @VisibleForTesting
    void preCreateUsers() {
        synchronized (mLock) {
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing preCreateUsers() call");
                queueOperationLocked(() -> sendPreCreateUsers());
                return;
            }
        }
        sendPreCreateUsers();
    }

    private void sendPreCreateUsers() {
        ICarSystemServerClient carService;
        synchronized (mLock) {
            carService = mCarService;
        }
        try {
            carService.preCreateUsers();
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        }
    }

    private void handleCarServiceUnresponsive() {
        // This should not happen. Calling this method means ICarSystemServerClient binder is not
        // returned after service connection. and CarService has not connected in the given time.
        Slog.w(TAG, "*** CARHELPER KILLING SYSTEM PROCESS: " + "CarService unresponsive.");
        Slog.w(TAG, "*** GOODBYE!");
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private void sendSetSystemServerConnectionsCall() {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(CAR_SERVICE_INTERFACE);
        data.writeStrongBinder(mHelper.asBinder());
        data.writeStrongBinder(mCarServiceConnectedCallback.asBinder());
        IBinder binder;
        synchronized (mLock) {
            binder = mCarServiceBinder;
        }
        int code = IBinder.FIRST_CALL_TRANSACTION;
        try {
            if (VERBOSE) Slog.v(TAG, "calling one-way binder transaction with code " + code);
            // oneway void setSystemServerConnections(in IBinder helper, in IBinder receiver) = 0;
            binder.transact(code, data, null, Binder.FLAG_ONEWAY);
            if (VERBOSE) Slog.v(TAG, "finished one-way binder transaction with code " + code);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Exception calling binder transaction (real code: "
                    + code + ")", e);
            throw e;
        } finally {
            data.recycle();
        }
    }

    private void sendUserLifecycleEvent(int eventType, @NonNull TargetUser user) {
        sendUserLifecycleEvent(eventType, /* from= */ null, user);
    }

    private void sendUserLifecycleEvent(int eventType, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        long now = System.currentTimeMillis();
        synchronized (mLock) {
            if (eventType == USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
                mLastSwitchedUser = to.getUserIdentifier();
            } else if (eventType == USER_LIFECYCLE_EVENT_TYPE_STOPPING
                    || eventType == USER_LIFECYCLE_EVENT_TYPE_STOPPED) {
                mLastUserLifecycle.delete(to.getUserIdentifier());
            } else {
                mLastUserLifecycle.put(to.getUserIdentifier(), eventType);
            }
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing lifecycle event " + eventType + " for user " + to);
                queueOperationLocked(() -> sendUserLifecycleEvent(eventType, now, from, to));
                return;
            }
        }
        TimingsTraceAndSlog t = newTimingsTraceAndSlog();
        t.traceBegin("send-lifecycle-" + eventType + "-" + to.getUserIdentifier());
        sendUserLifecycleEvent(eventType, now, from, to);
        t.traceEnd();
    }

    private void sendUserLifecycleEvent(int eventType, long timestamp, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        int fromId = from == null ? UserHandle.USER_NULL : from.getUserIdentifier();
        int toId = to.getUserIdentifier();
        sendUserLifecycleEvent(eventType, timestamp, fromId, toId);
    }

    private void sendUserLifecycleEvent(int eventType, long timestamp, @UserIdInt int fromId,
            @UserIdInt int toId) {
        ICarSystemServerClient carService;
        synchronized (mLock) {
            carService = mCarService;
        }
        try {
            carService.onUserLifecycleEvent(eventType, timestamp, fromId, toId);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
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
                pids, null, null, getInterestingNativePids(), null);
    }

    @VisibleForTesting
    void handleCarServiceCrash() {
        // Recovery behavior.  Kill the system server and reset
        // everything if enabled by the property.
        boolean restartOnServiceCrash = SystemProperties.getBoolean(PROP_RESTART_RUNTIME, false);

        mHandler.removeMessages(WHAT_SERVICE_UNRESPONSIVE);

        dumpServiceStacks();
        if (restartOnServiceCrash) {
            Slog.w(TAG, "*** CARHELPER KILLING SYSTEM PROCESS: " + "CarService crash");
            Slog.w(TAG, "*** GOODBYE!");
            Process.killProcess(Process.myPid());
            System.exit(10);
        } else {
            Slog.w(TAG, "*** CARHELPER ignoring: " + "CarService crash");
        }
        synchronized (mLock) {
            mCarServiceHasCrashed = true;
            mCarService = null;
        }
    }

    private void handleClientsNotResponding(@NonNull int[] pids) {
        mProcessTerminator.requestTerminateProcess(pids);
    }

    private void registerMonitorToWatchdogDaemon() {
        try {
            mCarWatchdogDaemonHelper.registerMonitor(mCarWatchdogMonitor);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot register to car watchdog daemon: " + e);
        }
    }

    private void killProcessAndReportToMonitor(int pid) {
        String processName = getProcessName(pid);
        Process.killProcess(pid);
        Slog.w(TAG, "carwatchdog killed " + processName + " (pid: " + pid + ")");
        try {
            mCarWatchdogDaemonHelper.tellDumpFinished(mCarWatchdogMonitor, pid);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot report monitor result to car watchdog daemon: " + e);
        }
    }

    private static String getProcessName(int pid) {
        String unknownProcessName = "unknown process";
        String filename = "/proc/" + pid + "/cmdline";
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line = reader.readLine().replace('\0', ' ').trim();
            int index = line.indexOf(' ');
            if (index != -1) {
                line = line.substring(0, index);
            }
            return Paths.get(line).getFileName().toString();
        } catch (IOException e) {
            Slog.w(TAG, "Cannot read " + filename);
            return unknownProcessName;
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
        public void setDisplayAllowlistForUser(@UserIdInt int userId, int[] displayIds) {
            mCarLaunchParamsModifier.setDisplayAllowlistForUser(userId, displayIds);
        }

        @Override
        public void setPassengerDisplays(int[] displayIdsForPassenger) {
            mCarLaunchParamsModifier.setPassengerDisplays(displayIdsForPassenger);
        }

        @Override
        public void setSourcePreferredComponents(boolean enableSourcePreferred,
                @Nullable List<ComponentName> sourcePreferredComponents) {
            mCarLaunchParamsModifier.setSourcePreferredComponents(
                    enableSourcePreferred, sourcePreferredComponents);
        }
    }

    private class ICarWatchdogMonitorImpl extends ICarWatchdogMonitor.Stub {
        private final WeakReference<CarServiceHelperService> mService;

        private ICarWatchdogMonitorImpl(CarServiceHelperService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void onClientsNotResponding(int[] pids) {
            CarServiceHelperService service = mService.get();
            if (service == null || pids == null || pids.length == 0) {
                return;
            }
            service.handleClientsNotResponding(pids);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }

    private final class ProcessTerminator {

        private static final long ONE_SECOND_MS = 1_000L;

        private final Object mProcessLock = new Object();
        private ExecutorService mExecutor;
        @GuardedBy("mProcessLock")
        private int mQueuedTask;

        public void requestTerminateProcess(@NonNull int[] pids) {
            synchronized (mProcessLock) {
                // If there is a running thread, we re-use it instead of starting a new thread.
                if (mExecutor == null) {
                    mExecutor = Executors.newSingleThreadExecutor();
                }
                mQueuedTask++;
            }
            mExecutor.execute(() -> {
                for (int pid : pids) {
                    dumpAndKillProcess(pid);
                }
                // mExecutor will be stopped from the main thread, if there is no queued task.
                mHandler.sendMessage(obtainMessage(ProcessTerminator::postProcessing, this)
                        .setWhat(WHAT_POST_PROCESS_DUMPING));
            });
        }

        private void postProcessing() {
            synchronized (mProcessLock) {
                mQueuedTask--;
                if (mQueuedTask == 0) {
                    mExecutor.shutdown();
                    mExecutor = null;
                }
            }
        }

        private void dumpAndKillProcess(int pid) {
            if (DBG) {
                Slog.d(TAG, "Dumping and killing process(pid: " + pid + ")");
            }
            ArrayList<Integer> javaPids = new ArrayList<>(1);
            ArrayList<Integer> nativePids = new ArrayList<>();
            try {
                if (isJavaApp(pid)) {
                    javaPids.add(pid);
                } else {
                    nativePids.add(pid);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Cannot get process information: " + e);
                return;
            }
            nativePids.addAll(getInterestingNativePids());
            long startDumpTime = SystemClock.uptimeMillis();
            ActivityManagerService.dumpStackTraces(javaPids, null, null, nativePids, null);
            long dumpTime = SystemClock.uptimeMillis() - startDumpTime;
            if (DBG) {
                Slog.d(TAG, "Dumping process took " + dumpTime + "ms");
            }
            // To give clients a chance of wrapping up before the termination.
            if (dumpTime < ONE_SECOND_MS) {
                mHandler.sendMessageDelayed(obtainMessage(
                        CarServiceHelperService::killProcessAndReportToMonitor,
                        CarServiceHelperService.this, pid).setWhat(WHAT_PROCESS_KILL),
                        ONE_SECOND_MS - dumpTime);
            } else {
                killProcessAndReportToMonitor(pid);
            }
        }

        private boolean isJavaApp(int pid) throws IOException {
            Path exePath = new File("/proc/" + pid + "/exe").toPath();
            String target = Files.readSymbolicLink(exePath).toString();
            // Zygote's target exe is also /system/bin/app_process32 or /system/bin/app_process64.
            // But, we can be very sure that Zygote will not be the client of car watchdog daemon.
            return target == "/system/bin/app_process32" || target == "/system/bin/app_process64";
        }
    }

    private final class CarServiceConnectedCallback extends IResultReceiver.Stub {
        @Override
        public void send(int resultCode, Bundle resultData) {
            mHandler.removeMessages(WHAT_SERVICE_UNRESPONSIVE);

            IBinder binder;
            if (resultData == null || (binder =
                    resultData.getBinder(ICAR_SYSTEM_SERVER_CLIENT)) == null) {
                Slog.wtf(TAG, "setSystemServerConnections return NULL Binder.");
                handleCarServiceUnresponsive();
                return;
            }

            boolean carServiceHasCrashed;
            int lastSwitchedUser;
            ArrayList<Runnable> pendingOperations;
            SparseIntArray lastUserLifecycle = null;
            synchronized (mLock) {
                mCarService = ICarSystemServerClient.Stub.asInterface(binder);
                carServiceHasCrashed = mCarServiceHasCrashed;
                mCarServiceHasCrashed = false;
                lastSwitchedUser = mLastSwitchedUser;
                pendingOperations = mPendingOperations;
                mPendingOperations = null;
                if (carServiceHasCrashed) {
                    lastUserLifecycle = mLastUserLifecycle.clone();
                }
            }
            int numberOperations = pendingOperations == null ? 0 : pendingOperations.size();
            EventLog.writeEvent(EventLogTags.CAR_HELPER_SVC_CONNECTED, numberOperations);

            Slog.i(TAG, "ICarSystemServerClient binder received.");

            if (carServiceHasCrashed) {
                int numUsers = lastUserLifecycle.size();
                TimingsTraceAndSlog t = newTimingsTraceAndSlog();
                t.traceBegin("send-uses-after-reconnect-" + numUsers);
                // Send user0 events first
                int user0Lifecycle = lastUserLifecycle.get(UserHandle.USER_SYSTEM,
                        USER_LIFECYCLE_EVENT_TYPE_STARTING);
                lastUserLifecycle.delete(UserHandle.USER_SYSTEM);
                boolean user0IsCurrent = lastSwitchedUser == UserHandle.USER_SYSTEM;
                sendAllLifecyleToUser(UserHandle.USER_SYSTEM, user0Lifecycle, user0IsCurrent);
                // Send current user events next
                if (!user0IsCurrent) {
                    int currentUserLifecycle = lastUserLifecycle.get(lastSwitchedUser,
                            USER_LIFECYCLE_EVENT_TYPE_STARTING);
                    lastUserLifecycle.delete(lastSwitchedUser);
                    sendAllLifecyleToUser(lastSwitchedUser, currentUserLifecycle,
                            /* isCurrentUser= */ true);
                }
                // Send all other users' events
                for (int i = 0; i < lastUserLifecycle.size(); i++) {
                    int userId = lastUserLifecycle.keyAt(i);
                    int lifecycle = lastUserLifecycle.valueAt(i);
                    sendAllLifecyleToUser(userId, lifecycle, /* isCurrentUser= */ false);
                }
                t.traceEnd();
            } else if (pendingOperations != null) {
                if (DBG) Slog.d(TAG, "Running " + numberOperations + " pending operations");
                TimingsTraceAndSlog t = newTimingsTraceAndSlog();
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
        }
    }
}
