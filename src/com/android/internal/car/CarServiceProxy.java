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

import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.car.internal.ICarSystemServerClient;
import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService.TargetUser;
import com.android.server.utils.TimingsTraceAndSlog;

import java.io.PrintWriter;

/**
 * Manages CarService operations requested by CarServiceHelperService.
 *
 * <p>
 * It is used to send and re-send binder calls to CarService when it connects and dies & reconnects.
 * It does not simply queue the operations, because it needs to "replay" some of them on every
 * reconnection.
 */
final class CarServiceProxy {

    /*
     * The logic of re-queue:
     * There are two sparse array - mLastUserLifecycle and mPendingOperations
     * First sparse array - mLastUserLifecycle - is to keep track of the life-cycle events for each
     * user. It would have the last life-cycle event of each running user (typically user 0 and the
     * current user). All life-cycle events seen so far would be replayed on connection and
     * reconnection.
     * Second sparse array - mPendingOperations - would keep all the non-life-cycle events related
     * operations (currently initBootUser and preCreateUsers). It is a boolean sparse array just to
     * keep if the call is completed or yet to complete.
     */

    // Operation ID for each non life-cycle event calls
    private static final int INIT_BOOT_USER = 0;
    private static final int PRE_CREATE_USERS = 1;
    // Operation Name for each non life-cycle event calls
    private static final String[] OPERATION_NAME = {"initBootUser", "preCreateUsers"};

    private static final boolean DBG = false;
    private static final String TAG = CarServiceProxy.class.getSimpleName();

    private static final long LIFECYCLE_TIMESTAMP_IGNORE = 0;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mCarServiceCrashed;
    @UserIdInt
    @GuardedBy("mLock")
    private int mLastSwitchedUser = UserHandle.USER_NULL;
    @UserIdInt
    @GuardedBy("mLock")
    private int mPreviousUserOfLastSwitchedUser = UserHandle.USER_NULL;
    // Key: user id, value: life-cycle
    @GuardedBy("mLock")
    private final SparseIntArray mLastUserLifecycle = new SparseIntArray();
    // Key: Operation id, value: true/false
    @GuardedBy("mLock")
    private final SparseBooleanArray mPendingOperations = new SparseBooleanArray();
    @GuardedBy("mLock")
    private ICarSystemServerClient mCarService;

    private final CarServiceHelperService mCarServiceHelperService;
    private final UserMetrics mUserMetrics = new UserMetrics();

    CarServiceProxy(CarServiceHelperService carServiceHelperService) {
        mCarServiceHelperService = carServiceHelperService;
    }

    /**
     * Handles new CarService Connection.
     */
    void handleCarServiceConnection(ICarSystemServerClient carService) {
        Slog.i(TAG, "CarService connected.");
        TimingsTraceAndSlog t = newTimingsTraceAndSlog();
        t.traceBegin("handleCarServiceConnection");
        synchronized (mLock) {
            mCarService = carService;
            mCarServiceCrashed = false;
            if (mPendingOperations.get(INIT_BOOT_USER)) saveOrRun(INIT_BOOT_USER);
            if (mPendingOperations.get(PRE_CREATE_USERS)) saveOrRun(PRE_CREATE_USERS);
        }
        sendLifeCycleEvents();
        t.traceEnd();
    }

    private void sendLifeCycleEvents() {
        int lastSwitchedUser;
        SparseIntArray lastUserLifecycle;

        synchronized (mLock) {
            lastSwitchedUser = mLastSwitchedUser;
            lastUserLifecycle = mLastUserLifecycle.clone();
        }

        // Send user0 events first
        int user0Lifecycle = lastUserLifecycle.get(UserHandle.USER_SYSTEM);
        boolean user0IsCurrent = lastSwitchedUser == UserHandle.USER_SYSTEM;
        // If user0Lifecycle is 0, then no life-cycle event received yet.
        if (user0Lifecycle != 0) {
            sendAllLifecyleToUser(UserHandle.USER_SYSTEM, user0Lifecycle, user0IsCurrent);
        }
        lastUserLifecycle.delete(UserHandle.USER_SYSTEM);

        // Send current user events next
        if (!user0IsCurrent) {
            int currentUserLifecycle = lastUserLifecycle.get(lastSwitchedUser);
            // If currentUserLifecycle is 0, then no life-cycle event received yet.
            if (currentUserLifecycle != 0) {
                sendAllLifecyleToUser(lastSwitchedUser, currentUserLifecycle,
                        /* isCurrentUser= */ true);
            }
        }

        lastUserLifecycle.delete(lastSwitchedUser);

        // Send all other users' events
        for (int i = 0; i < lastUserLifecycle.size(); i++) {
            int userId = lastUserLifecycle.keyAt(i);
            int lifecycle = lastUserLifecycle.valueAt(i);
            sendAllLifecyleToUser(userId, lifecycle, /* isCurrentUser= */ false);
        }
    }

    private void sendAllLifecyleToUser(@UserIdInt int userId, int lifecycle,
            boolean isCurrentUser) {
        if (DBG) {
            Slog.d(TAG, "sendAllLifecyleToUser, user:" + userId + " lifecycle:" + lifecycle);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_STARTING) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STARTING, UserHandle.USER_NULL,
                    userId);
        }

        if (isCurrentUser && userId != UserHandle.USER_SYSTEM) {
            synchronized (mLock) {
                sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING,
                        mPreviousUserOfLastSwitchedUser, userId);
            }
        }

        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_UNLOCKING) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, UserHandle.USER_NULL,
                    userId);
        }

        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_UNLOCKED) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, UserHandle.USER_NULL,
                    userId);
        }
    }

    /**
     * Initializes boot user.
     */
    void initBootUser() {
        saveOrRun(INIT_BOOT_USER);
    }

    /**
     * Pre-creates required number of user.
     */
    void preCreateUsers() {
        saveOrRun(PRE_CREATE_USERS);
    }

    private void saveOrRun(int operationId) {
        synchronized (mLock) {
            if (mCarService == null) {
                if (DBG) {
                    Slog.d(TAG, "CarService null. Operation " + OPERATION_NAME[operationId]
                            + " deferred.");
                }
                mPendingOperations.put(operationId, true);
                return;
            }
            try {
                if (isServiceCrashedLoggedLocked(OPERATION_NAME[operationId])) {
                    return;
                }
                sendCarServiceActionLocked(operationId);
                mPendingOperations.delete(operationId);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException from car service", e);
                handleCarServiceCrash();
            }
        }
    }

    private void sendCarServiceActionLocked(int operationId) throws RemoteException {
        if (DBG) {
            Slog.d(TAG, "sendCarServiceActionLocked: Operation " + OPERATION_NAME[operationId]);
        }
        switch (operationId) {
            case INIT_BOOT_USER:
                mCarService.initBootUser();
                break;
            case PRE_CREATE_USERS:
                mCarService.preCreateUsers();
                break;
            default:
                Slog.wtf(TAG, "Invalid Operation. OperationId -" + operationId);
        }
    }

    /**
     * Sends user life-cycle events to CarService.
     */
    // TODO (b/158026653): add @UserLifecycleEventType for eventType
    void sendUserLifecycleEvent(int eventType, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        long now = System.currentTimeMillis();
        int fromId = from == null ? UserHandle.USER_NULL : from.getUserIdentifier();
        int toId = to.getUserIdentifier();
        mUserMetrics.onEvent(eventType, now, fromId, toId);

        synchronized (mLock) {
            if (eventType == USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
                mLastSwitchedUser = to.getUserIdentifier();
                mPreviousUserOfLastSwitchedUser = from.getUserIdentifier();
                mLastUserLifecycle.put(to.getUserIdentifier(), eventType);
            } else if (eventType == USER_LIFECYCLE_EVENT_TYPE_STOPPING
                    || eventType == USER_LIFECYCLE_EVENT_TYPE_STOPPED) {
                mLastUserLifecycle.delete(to.getUserIdentifier());
            } else {
                mLastUserLifecycle.put(to.getUserIdentifier(), eventType);
            }
            if (mCarService == null) {
                if (DBG) {
                    Slog.d(TAG, "CarService null. sendUserLifecycleEvent() deferred for lifecycle"
                            + " event " + eventType + " for user " + to);
                }
                return;
            }
        }
        sendUserLifecycleEvent(eventType, fromId, toId);
    }

    private void sendUserLifecycleEvent(int eventType, @UserIdInt int fromId, @UserIdInt int toId) {
        if (DBG) {
            Slog.d(TAG, "sendUserLifecycleEvent():" + " eventType=" + eventType + ", fromId="
                    + fromId + ", toId=" + toId);
        }
        try {
            synchronized (mLock) {
                if (isServiceCrashedLoggedLocked("sendUserLifecycleEvent")) return;
                mCarService.onUserLifecycleEvent(eventType, fromId, toId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        }
    }

    private void handleCarServiceCrash() {
        synchronized (mLock) {
            mCarServiceCrashed = true;
            mCarService = null;
        }
        Slog.w(TAG, "CarServiceCrashed. No more car service calls before reconnection.");
        mCarServiceHelperService.handleCarServiceCrash();
    }

    private TimingsTraceAndSlog newTimingsTraceAndSlog() {
        return new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private boolean isServiceCrashedLoggedLocked(String operation) {
        if (mCarServiceCrashed) {
            Slog.w(TAG, "CarServiceCrashed. " + operation + " will be executed after reconnection");
            return true;
        }
        return false;
    }

    /**
     * Dump
     */
    void dump(PrintWriter writer) {
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

        dumpUserMetrics(writer);
    }

    /**
     * Dump User metrics
     */
    void dumpUserMetrics(PrintWriter writer) {
        mUserMetrics.dump(writer);
    }
}
