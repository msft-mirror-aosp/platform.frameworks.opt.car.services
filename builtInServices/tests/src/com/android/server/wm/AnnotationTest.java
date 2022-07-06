/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertWithMessage;

import android.car.annotation.AddedIn;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class AnnotationTest {
    private static final String[] CAR_SERVICE_HELPER_SERVICE_CLASSES = new String[] {
            "com.android.internal.car.CarServiceHelperInterface",
            "com.android.internal.car.CarServiceHelperServiceUpdatable",
            "com.android.server.wm.ActivityOptionsWrapper",
            "com.android.server.wm.ActivityRecordWrapper",
            "com.android.server.wm.CalculateParams",
            "com.android.server.wm.CarLaunchParamsModifierInterface",
            "com.android.server.wm.CarLaunchParamsModifierUpdatable",
            "com.android.server.wm.LaunchParamsWrapper",
            "com.android.server.wm.RequestWrapper",
            "com.android.server.wm.TaskDisplayAreaWrapper",
            "com.android.server.wm.TaskWrapper",
            "com.android.server.wm.WindowLayoutWrapper"
            };
    @Test
    public void testCarHelperServiceAPIAddedInAnnotation() throws Exception {
        checkForAnnotation(CAR_SERVICE_HELPER_SERVICE_CLASSES, AddedIn.class);
    }

    private void checkForAnnotation(String[] classes, Class... annotationClasses) throws Exception {
        List<String> errorsNoAnnotation = new ArrayList<>();
        List<String> errorsExtraAnnotation = new ArrayList<>();

        for (int i = 0; i < classes.length; i++) {
            String className = classes[i];
            Field[] fields = Class.forName(className).getDeclaredFields();
            for (int j = 0; j < fields.length; j++) {
                Field field = fields[j];
                boolean isAnnotated = containsAddedInAnnotation(field, annotationClasses);
                boolean isPrivate = Modifier.isPrivate(field.getModifiers());

                if (isPrivate && isAnnotated) {
                    errorsExtraAnnotation.add(className + " FIELD: " + field.getName());
                }

                if (!isPrivate && !isAnnotated) {
                    errorsNoAnnotation.add(className + " FIELD: " + field.getName());
                }
            }

            Method[] methods = Class.forName(className).getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                Method method = methods[j];

                // These are some internal methods
                if (method.getName().contains("$")) continue;

                boolean isAnnotated = containsAddedInAnnotation(method, annotationClasses);
                boolean isPrivate = Modifier.isPrivate(method.getModifiers());

                if (isPrivate && isAnnotated) {
                    errorsExtraAnnotation.add(className + " METHOD: " + method.getName());
                }

                if (!isPrivate && !isAnnotated) {
                    errorsNoAnnotation.add(className + " METHOD: " + method.getName());
                }
            }
        }

        StringBuilder errorFlatten = new StringBuilder();
        if (!errorsNoAnnotation.isEmpty()) {
            errorFlatten.append("Errors:\nNo AddedIn annotation found for-\n");
            for (int i = 0; i < errorsNoAnnotation.size(); i++) {
                errorFlatten.append(errorsNoAnnotation.get(i) + "\n");
            }
        }

        if (!errorsExtraAnnotation.isEmpty()) {
            errorFlatten.append("\nErrors:\nExtra AddedIn annotation found for-\n");
            for (int i = 0; i < errorsExtraAnnotation.size(); i++) {
                errorFlatten.append(errorsExtraAnnotation.get(i) + "\n");
            }
        }

        assertWithMessage(errorFlatten.toString())
                .that(errorsExtraAnnotation.size() + errorsNoAnnotation.size()).isEqualTo(0);
    }

    @SuppressWarnings("unchecked")
    private boolean containsAddedInAnnotation(Field field, Class... annotationClasses) {
        for (int i = 0; i < annotationClasses.length; i++) {
            if (field.getAnnotation(annotationClasses[i]) != null) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean containsAddedInAnnotation(Method method, Class... annotationClasses) {
        for (int i = 0; i < annotationClasses.length; i++) {
            if (method.getAnnotation(annotationClasses[i]) != null) {
                return true;
            }
        }
        return false;
    }
}

