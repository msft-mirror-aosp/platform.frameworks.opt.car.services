package com.android.internal.car;

import android.annotation.SystemApi;

/**
 * Class to hold common config for carservicehelper.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class CarServiceHelperConfig {
    private CarServiceHelperConfig() {
        throw new UnsupportedOperationException("contains only static members");
    }

    /**
     * Represents a minor version change of car service helper for the same
     * {@link android.os.Build.VERSION#SDK_INT}.
     *
     * <p>It will reset to {@code 0} whenever {@link android.os.Build.VERSION#SDK_INT} is updated
     * and will increase by {@code 1} if car service helper builtin API is changed with the same
     * {@link android.os.Build.VERSION#SDK_INT}. Updatable car service helper may check this version
     * to have different behavior across minor revision.
     */
    public static final int VERSION_MINOR_INT = 0;
}
