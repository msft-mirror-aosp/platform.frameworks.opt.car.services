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

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Objects;

/**
 * Class for accessing car display compat package overrides.
 *
 * The methods of class are not thread safe.
 */
final class CarDisplayCompatConfig {

    private static final String TAG = CarDisplayCompatConfig.class.getSimpleName();
    private static final String ENCODING = "UTF-8";
    // Config file doesn't expect any namespace before xml elements.
    private static final String NAMESPACE = null;
    private static final String CONFIG = "config";
    private static final String SCALE = "scale";
    private static final String DISPLAY = "display";
    private static final String USER = "userId";
    private static final String PACKAGE = "packageName";
    static final String ANY_PACKAGE = "*";
    static final float DEFAULT_SCALE = 1f;

    /**
     * Maps a combination of package name, user id, display id to a scale factor.
     * display id is required.
     * * means all packages
     * -1 (UserHandle.ALL) means all the users
     *
     * ex: com.android@10@0
     * ex: *@10@0
     * ex: com.android@-1@0
     * ex: *@-1@0
     */
    private final ArrayMap<Key, Float> mPackageUserDisplayScaleFactorMap = new ArrayMap<>();

    /**
     * Set a new scaling rule.
     */
    public void setScaleFactor(@NonNull Key key, float value) {
        mPackageUserDisplayScaleFactorMap.put(key, value);
    }

    /**
     * {@link ScaleFactor} when all values are set.
     *
     * Because this class is not thread safe, we're accepting the key as a parameter so that
     * the class that's calling this method can make sure they key is created in a
     * thread safe manner.
     */
    public float getScaleFactor(@NonNull Key key, float defaultValue) {
        return mPackageUserDisplayScaleFactorMap.getOrDefault(key, defaultValue);
    }

    /**
     * Returns the internal data in xml format as a String.
     */
    public String dump() {
        String configStr = "";
        try {
            XmlSerializer xmlSerializer = Xml.newSerializer();
            StringWriter writer = new StringWriter();
            xmlSerializer.setOutput(writer);
            xmlSerializer.startDocument(ENCODING, /* standalone */ null);
            xmlSerializer.startTag(NAMESPACE, CONFIG);
            for (int i = 0; i < mPackageUserDisplayScaleFactorMap.size(); i++) {
                Key key = mPackageUserDisplayScaleFactorMap.keyAt(i);
                float value = mPackageUserDisplayScaleFactorMap.valueAt(i);
                xmlSerializer.startTag(NAMESPACE, SCALE);
                xmlSerializer.attribute(NAMESPACE, DISPLAY, String.valueOf(key.displayId));
                if (!ANY_PACKAGE.equals(key.packageName)) {
                    xmlSerializer.attribute(NAMESPACE, PACKAGE, key.packageName);
                }
                if (key.userId != UserHandle.ALL.getIdentifier()) {
                    xmlSerializer.attribute(NAMESPACE, USER, String.valueOf(key.userId));
                }
                xmlSerializer.text(String.valueOf(value));
                xmlSerializer.endTag(NAMESPACE, SCALE);
            }
            xmlSerializer.endTag(NAMESPACE, CONFIG);
            xmlSerializer.endDocument();
            xmlSerializer.flush();
            configStr = writer.toString();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            Slogf.e(TAG, "failed to dump config!", e);
        }
        return configStr;
    }

    /**
     * Populate the internal data from the given {@link InputStream}
     */
    public void populate(InputStream inputStream) throws XmlPullParserException,
            IOException {
        mPackageUserDisplayScaleFactorMap.clear();
        XmlPullParserFactory parserFactory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = parserFactory.newPullParser();
        // Config file doesn't expect any namespace before xml elements.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(inputStream, ENCODING);
        parser.nextTag();
        readConfig(parser);
    }

    private void readConfig(@NonNull XmlPullParser parser) throws XmlPullParserException,
            IOException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, CONFIG);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            if (SCALE.equals(name)) {
                readScale(parser);
            } else {
                skipTag(parser);
            }
        }
    }

    private void readScale(@NonNull XmlPullParser parser) throws XmlPullParserException,
            IOException {
        parser.require(XmlPullParser.START_TAG, NAMESPACE, SCALE);

        int display = DEFAULT_DISPLAY;
        try {
            display = Integer.parseInt(parser.getAttributeValue(NAMESPACE, DISPLAY));
        } catch (NullPointerException | NumberFormatException e) {
            Slogf.e(TAG, "parse failed: %s = %s", DISPLAY,
                    parser.getAttributeValue(NAMESPACE, DISPLAY));
        }

        String packageName = parser.getAttributeValue(NAMESPACE, PACKAGE);
        packageName = (packageName == null) ? ANY_PACKAGE : packageName;

        int userId = UserHandle.ALL.getIdentifier();
        try {
            userId = Integer.parseInt(parser.getAttributeValue(NAMESPACE, USER));
        } catch (NullPointerException | NumberFormatException e) {
            Slogf.e(TAG, "parse failed: %s = %s", USER, parser.getAttributeValue(NAMESPACE, USER));
        }

        float value = DEFAULT_SCALE;
        if (parser.next() == XmlPullParser.TEXT) {
            try {
                value = Float.parseFloat(parser.getText());
            } catch (NullPointerException | NumberFormatException e) {
                Slogf.e(TAG, "parse failed: TEXT = %s", parser.getText());
            }
            parser.nextTag();
        }
        parser.require(XmlPullParser.END_TAG, NAMESPACE, SCALE);

        setScaleFactor(new Key(display, packageName, UserHandle.of(userId)), value);
    }

    /**
     * Skips to the next tag.
     */
    private void skipTag(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    static class Key {
        public int displayId;
        public String packageName;
        public int userId;

        Key(int displayId, @NonNull String packageName, @NonNull UserHandle user) {
            this.displayId = displayId;
            this.packageName = packageName;
            this.userId = user.getIdentifier();
        }

        @Override
        public String toString() {
            return String.format(Locale.getDefault(), "%d@%s@%d", displayId, packageName, userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(displayId, packageName, userId);
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Key)) return false;
            if (((Key) other).displayId != displayId) return false;
            if (!((Key) other).packageName.equals(packageName)) return false;
            if (((Key) other).userId != userId) return false;
            return true;
        }
    }
}
