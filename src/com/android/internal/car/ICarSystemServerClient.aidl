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

/**
 * API to communicate from CarServiceHelperService to car service.
 * @hide
 */
interface ICarSystemServerClient {
    /**
     * Notify of user lifecycle events.
     *
     * @param eventType - type as defined by CarUserManager.UserLifecycleEventType
     * @param timestampMs - when the event happened
     * @param fromUserId - user id of previous user when type is SWITCHING (or UserHandle.USER_NULL)
     * @param toUserId - user id of new user.
     */
    oneway void onUserLifecycleEvent(int eventType, long timestampMs, int fromUserId,
            int toUserId);

    /**
     * Notify when first user was unlocked, for metrics (and lifecycle) purposes.
     *
     * @param userId - id of first non-system user locked
     * @param timestampMs - when the user was unlocked
     * @param duration - how long it took to unlock (from SystemServer start)
     * @param halResponseTime - see CarServiceHelperService.mHalResponseTime
     */
    oneway void onFirstUserUnlocked(int userId, long timestampMs, long duration,
            int halResponseTime);

    /**
     * Notify User HAL to get the initial user info.
     *
     * @param requestType - as defined by InitialUserInfoRequestType.
     * @param timeoutMs - how long to wait for HAL's response.
     * @param receiver - a com.android.internal.os.IResultReceiver callback.
     */
    oneway void getInitialUserInfo(int requestType, int timeoutMs, in IBinder receiver);

    /**
     * Notify the initial user after boot.
     *
     * @param userId - the id of the initial user
     */
    // TODO(b/149797595): should pass UserInfo instead, but for some reason passing the whole
    // UserInfo through a raw binder transaction on CarServiceHelper is not working.
    oneway void setInitialUser(int userId);
}
