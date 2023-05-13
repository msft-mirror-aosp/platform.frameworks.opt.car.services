/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.Condition;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiSelector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public final class ImeSmokeTest {

    private static final long KEYBOARD_LAUNCH_TIMEOUT = 5_000;

    private static final String PLAIN_TEXT_EDIT_RESOURCE_ID =
            "com.google.android.car.kitchensink:id/plain_text_edit";

    private static final String KITCHEN_SINK_APP =
            "com.google.android.car.kitchensink";

    private Instrumentation mInstrumentation;
    private UiDevice mDevice;

    @Before
    public void setUp() throws IOException {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mDevice = UiDevice.getInstance(mInstrumentation);
        closeKitchenSink();
    }

    @After
    public void tearDown() throws IOException {
        closeKitchenSink();
    }

    private void closeKitchenSink() throws IOException {
        mDevice.executeShellCommand(String.format("am force-stop %s", KITCHEN_SINK_APP));
    }

    @Test
    public void canOpenIME() throws UiObjectNotFoundException {
        // Open KitchenSink > Carboard
        Intent intent = mInstrumentation
                .getContext()
                .getPackageManager()
                .getLaunchIntentForPackage(KITCHEN_SINK_APP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("select", "carboard");
        mInstrumentation.getContext().startActivity(intent);

        UiObject editText = mDevice.findObject((new UiSelector().resourceId(
                PLAIN_TEXT_EDIT_RESOURCE_ID)));
        editText.click();

        mDevice.wait(isKeyboardOpened(), KEYBOARD_LAUNCH_TIMEOUT);
    }

    private static Condition<UiDevice, Boolean> isKeyboardOpened() {
        return unusedDevice -> {
            for (AccessibilityWindowInfo window :
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true;
                }
            }
            return false;
        };
    }
}
