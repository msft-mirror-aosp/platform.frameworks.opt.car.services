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

import android.annotation.NonNull;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.util.DebugUtils;

/**
 * Provides constants that are defined somewhere else and must be cloned here
 */
// TODO(b/149797595): move to a common project used by this project and CarService
final class ExternalConstants {

    private ExternalConstants() {
        throw new UnsupportedOperationException("contains only static constants");
    }

    static final class ICarConstants {
        static final String CAR_SERVICE_INTERFACE = "android.car.ICar";

        // These numbers should match with binder call order of
        // packages/services/Car/car-lib/src/android/car/ICar.aidl
        static final int ICAR_CALL_SET_CAR_SERVICE_HELPER = 0;
        static final int ICAR_CALL_ON_USER_LIFECYCLE = 1;
        static final int ICAR_CALL_FIRST_USER_UNLOCKED = 2;
        static final int ICAR_CALL_GET_INITIAL_USER_INFO = 3;

        // TODO(145689885) remove once refactored
        static final int ICAR_CALL_SET_USER_UNLOCK_STATUS = 9;
        static final int ICAR_CALL_ON_SWITCH_USER = 10;

        private ICarConstants() {
            throw new UnsupportedOperationException("contains only static constants");
        }
    }

    static final class CarUserManagerConstants {

        static final int USER_LIFECYCLE_EVENT_TYPE_STARTING = 1;
        static final int USER_LIFECYCLE_EVENT_TYPE_SWITCHING = 2;
        static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKING = 3;
        static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKED = 4;
        static final int USER_LIFECYCLE_EVENT_TYPE_STOPPING = 5;
        static final int USER_LIFECYCLE_EVENT_TYPE_STOPPED = 6;

        private CarUserManagerConstants() {
            throw new UnsupportedOperationException("contains only static constants");
        }
    }

    static final class CarUserServiceConstants {

        static final String BUNDLE_USER_ID = "user.id";
        static final String BUNDLE_USER_FLAGS = "user.flags";
        static final String BUNDLE_USER_NAME = "user.name";
        static final String BUNDLE_INITIAL_INFO_ACTION = "initial_info.action";

        private CarUserServiceConstants() {
            throw new UnsupportedOperationException("contains only static constants");
        }
    }

    static final class UserHalServiceConstants {

        static final int STATUS_OK = 1;
        static final int STATUS_HAL_SET_TIMEOUT = 2;
        static final int STATUS_HAL_RESPONSE_TIMEOUT = 3;
        static final int STATUS_WRONG_HAL_RESPONSE = 4;

        private UserHalServiceConstants() {
            throw new UnsupportedOperationException("contains only static constants and methods");
        }

        @NonNull
        static String statusToString(int status) {
            switch (status) {
                case STATUS_OK:
                    return "OK";
                case STATUS_HAL_SET_TIMEOUT:
                    return "SET_TIMEOUT";
                case STATUS_HAL_RESPONSE_TIMEOUT:
                    return "RESPONSE_TIMEOUT";
                case STATUS_WRONG_HAL_RESPONSE:
                    return "WRONG_HAL_RESPONSE";
                default:
                    return "UNKNOWN(" + status + ")";
            }
        }
    }

    static final class VHalResponseActionConstants {

        static final int DEFAULT = 0;
        static final int SWITCH = 1;
        static final int CREATE = 2;

        private VHalResponseActionConstants() {
            throw new UnsupportedOperationException("contains only static constants");
        }
    }

    static final class VHalUserFlagsConstants {

        // NOTE: must be public because of DebugUtils.toString()
        public static final int NONE = 0;
        public static final int SYSTEM = 1;
        public static final int GUEST = 2;
        public static final int EPHEMERAL = 4;
        public static final int ADMIN = 8;

        private VHalUserFlagsConstants() {
            throw new UnsupportedOperationException("contains only static constants and methods");
        }

        // TODO(b/150413515): add unit test for methods

        /**
         * Checks if a flag contains {@link #SYSTEM}.
         */
        public static boolean isSystem(int flags) {
            return (flags & SYSTEM) != 0;
        }

        /**
         * Checks if a flag contains {@link #GUEST}.
         */
        public static boolean isGuest(int flags) {
            return (flags & GUEST) != 0;
        }

        /**
         * Checks if a flag contains {@link #EPHEMERAL}.
         */
        public static boolean isEphemeral(int flags) {
            return (flags & EPHEMERAL) != 0;
        }

        /**
         * Checks if a flag contains {@link #ADMIN}.
         */
        public static boolean isAdmin(int flags) {
            return (flags & ADMIN) != 0;
        }

        /**
         * Converts HAL flags to Android's.
         */
        @UserInfoFlag
        public static int toUserInfoFlags(int halFlags) {
            int flags = 0;
            if (isEphemeral(halFlags)) {
                flags |=UserInfo.FLAG_EPHEMERAL;
            }
            if (isAdmin(halFlags)) {
                flags |=UserInfo.FLAG_ADMIN;
            }
            return flags;
        }

        @NonNull
        public static String toString(int flags) {
            return DebugUtils.flagsToString(VHalUserFlagsConstants.class, "", flags);
        }
    }
}
