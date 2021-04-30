/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_IME_PLACEHOLDER;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider for platform-default car display area policy for reference design.
 */
public class CarDisplayAreaPolicyProvider implements DisplayAreaPolicy.Provider {

    private static final int DEFAULT_APP_TASK_CONTAINER = FEATURE_DEFAULT_TASK_CONTAINER;
    private static final int FOREGROUND_DISPLAY_AREA_ROOT = FEATURE_VENDOR_FIRST + 1;
    private static final int BACKGROUND_TASK_CONTAINER = FEATURE_VENDOR_FIRST + 2;

    @Override
    public DisplayAreaPolicy instantiate(WindowManagerService wmService, DisplayContent content,
            RootDisplayArea root, DisplayArea.Tokens imeContainer) {

        final TaskDisplayArea backgroundTaskDisplayArea = new TaskDisplayArea(content, wmService,
                "backgroundTaskDisplayArea", BACKGROUND_TASK_CONTAINER);

        final List<TaskDisplayArea> backgroundTdaList = new ArrayList<>();
        backgroundTdaList.add(backgroundTaskDisplayArea);

        // Root
        DisplayAreaPolicyBuilder.HierarchyBuilder rootHierarchy =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(root)
                        .setTaskDisplayAreas(backgroundTdaList)
                        .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(wmService.mPolicy,
                                "ImePlaceholder", FEATURE_IME_PLACEHOLDER)
                                .and(TYPE_INPUT_METHOD, TYPE_INPUT_METHOD_DIALOG)
                                .build());

        // Default application launches here
        final RootDisplayArea defaultAppsRoot = new DisplayAreaGroup(wmService,
                "FeatureApplication",
                FOREGROUND_DISPLAY_AREA_ROOT);
        final TaskDisplayArea defaultAppTaskDisplayArea = new TaskDisplayArea(content, wmService,
                "DefaultApplicationTaskDisplayArea", DEFAULT_APP_TASK_CONTAINER);
        final List<TaskDisplayArea> firstTdaList = new ArrayList<>();
        firstTdaList.add(defaultAppTaskDisplayArea);
        DisplayAreaPolicyBuilder.HierarchyBuilder applicationHierarchy =
                new DisplayAreaPolicyBuilder.HierarchyBuilder(defaultAppsRoot)
                        .setTaskDisplayAreas(firstTdaList)
                        .setImeContainer(imeContainer)
                        .addFeature(new DisplayAreaPolicyBuilder.Feature.Builder(wmService.mPolicy,
                                "ImePlaceholder", FEATURE_IME_PLACEHOLDER)
                                .and(TYPE_INPUT_METHOD, TYPE_INPUT_METHOD_DIALOG)
                                .build());

        return new DisplayAreaPolicyBuilder()
                .setRootHierarchy(rootHierarchy)
                .addDisplayAreaGroupHierarchy(applicationHierarchy)
                .build(wmService);
    }
}
