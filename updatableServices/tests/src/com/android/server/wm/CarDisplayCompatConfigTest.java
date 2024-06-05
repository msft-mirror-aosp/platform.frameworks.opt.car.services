/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.CarDisplayCompatConfig.ANY_PACKAGE;
import static com.android.server.wm.CarDisplayCompatScaleProviderUpdatableImpl.NO_SCALE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

@RunWith(AndroidJUnit4.class)
public class CarDisplayCompatConfigTest {
    private MockitoSession mMockingSession;

    private String mOnlyDisplayConfigXml =
            "<config>"
            + "<scale display=\"0\">.7</scale>"
            + "</config>";

    private String mDisplayAndUserIdConfigXml =
            "<config>"
            + "<scale display=\"0\" userId=\"10\">.7</scale>"
            + "</config>";

    private String mDisplayAndPackageNameConfigXml =
            "<config>"
            + "<scale display=\"0\" packageName=\"com.test\">.7</scale>"
            + "</config>";

    private String mDisplayAndUserIdAndPackageNameConfigXml =
            "<config>"
            + "<scale display=\"0\" packageName=\"com.test\" userId=\"10\">.7</scale>"
            + "</config>";

    private CarDisplayCompatConfig mConfig;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mConfig = new CarDisplayCompatConfig();
    }

    @After
    public void tearDown() {
        // If the exception is thrown during the MockingSession setUp, mMockingSession can be null.
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void populate_readsDisplay() throws XmlPullParserException, IOException,
            SecurityException {
        try (InputStream in = new ByteArrayInputStream(mOnlyDisplayConfigXml.getBytes())) {
            mConfig.populate(in);
        }
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE, UserHandle.ALL);
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(0.7f);
    }

    @Test
    public void populate_readsUserId() throws XmlPullParserException, IOException,
            SecurityException {
        try (InputStream in = new ByteArrayInputStream(mDisplayAndUserIdConfigXml.getBytes())) {
            mConfig.populate(in);
        }
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, ANY_PACKAGE, UserHandle.of(10));
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(0.7f);
    }

    @Test
    public void populate_readsPackageName() throws XmlPullParserException, IOException,
            SecurityException {
        try (InputStream in =
                new ByteArrayInputStream(mDisplayAndPackageNameConfigXml.getBytes())) {
            mConfig.populate(in);
        }
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "com.test", UserHandle.ALL);
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(0.7f);
    }

    @Test
    public void populate_readsAllAttributes() throws XmlPullParserException, IOException,
            SecurityException {
        try (InputStream in =
                new ByteArrayInputStream(mDisplayAndUserIdAndPackageNameConfigXml.getBytes())) {
            mConfig.populate(in);
        }
        CarDisplayCompatConfig.Key key =
                new CarDisplayCompatConfig.Key(DEFAULT_DISPLAY, "com.test", UserHandle.of(10));
        assertThat(mConfig.getScaleFactor(key, NO_SCALE)).isEqualTo(0.7f);
    }
}
