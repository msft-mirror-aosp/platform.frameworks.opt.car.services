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

package com.android.internal.car.updatable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.car.CarServiceHelperInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class ExtraDisplayMonitorTest extends AbstractExtendedMockitoTestCase {
    private ExtraDisplayMonitor mExtraDisplayMonitor;

    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private CarServiceHelperInterface mHelper;
    @Mock
    private Display mTestDisplay;
    private final int mTestDisplayId = 1234;
    private final int mTestUserId = 999;
    private final int mAnotherUserId = 998;
    @Captor
    private ArgumentCaptor<DisplayListener> mDisplayListenerCaptor;

    @Before
    public void setUp() {
        mExtraDisplayMonitor = new ExtraDisplayMonitor(
                mDisplayManager, /* handler= */ null, mHelper);
        doNothing().when(mDisplayManager).registerDisplayListener(
                mDisplayListenerCaptor.capture(), any());
        when(mDisplayManager.getDisplay(mTestDisplayId)).thenReturn(mTestDisplay);

        mExtraDisplayMonitor.init();
        mExtraDisplayMonitor.handleCurrentUserSwitching(mTestUserId);
    }

    @Test
    public void assignsOverlayDisplayToDriver() {
        when(mHelper.isOverlayDisplay(mTestDisplayId)).thenReturn(true);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);

        verify(mHelper, times(1)).assignUserToExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void doesNotAssignNonOverlayDisplayToDriver() {
        when(mHelper.isOverlayDisplay(mTestDisplayId)).thenReturn(false);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);

        verify(mHelper, never()).assignUserToExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void unassignsOverlayDisplayFromDriver() {
        when(mHelper.isOverlayDisplay(mTestDisplayId)).thenReturn(true);
        when(mHelper.assignUserToExtraDisplay(mTestUserId, mTestDisplayId)).thenReturn(true);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);
        mDisplayListenerCaptor.getValue().onDisplayRemoved(mTestDisplayId);

        verify(mHelper, times(1)).unassignUserFromExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void doesNotUnassignsNonOverlayDisplayFromDriver() {
        when(mHelper.isOverlayDisplay(mTestDisplayId)).thenReturn(false);
        when(mHelper.assignUserToExtraDisplay(mTestUserId, mTestDisplayId)).thenReturn(true);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);
        mDisplayListenerCaptor.getValue().onDisplayRemoved(mTestDisplayId);

        verify(mHelper, never()).unassignUserFromExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void assignsVirtualDisplayToDriver() {
        when(mHelper.isVirtualDisplay(mTestDisplayId)).thenReturn(true);
        when(mHelper.getOwnerUserIdOnDisplay(mTestDisplayId)).thenReturn(mTestUserId);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);

        verify(mHelper, times(1)).assignUserToExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void doesNotAssignNonVirtualDisplayToDriver() {
        when(mHelper.isVirtualDisplay(mTestDisplayId)).thenReturn(false);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);

        verify(mHelper, never()).assignUserToExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void unassignsVirtualDisplayFromDriver() {
        when(mHelper.isVirtualDisplay(mTestDisplayId)).thenReturn(true);
        when(mHelper.getOwnerUserIdOnDisplay(mTestDisplayId)).thenReturn(mTestUserId);
        when(mHelper.assignUserToExtraDisplay(mTestUserId, mTestDisplayId)).thenReturn(true);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);
        mDisplayListenerCaptor.getValue().onDisplayRemoved(mTestDisplayId);

        verify(mHelper, times(1)).unassignUserFromExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void doesNotUnassignsNonVirtualDisplayFromDriver() {
        when(mHelper.isVirtualDisplay(mTestDisplayId)).thenReturn(false);
        when(mHelper.getOwnerUserIdOnDisplay(mTestDisplayId)).thenReturn(mTestUserId);
        when(mHelper.assignUserToExtraDisplay(mTestUserId, mTestDisplayId)).thenReturn(true);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);
        mDisplayListenerCaptor.getValue().onDisplayRemoved(mTestDisplayId);

        verify(mHelper, never()).unassignUserFromExtraDisplay(mTestUserId, mTestDisplayId);
    }

    @Test
    public void doesNotAssignAnotherUserVirtualDisplayToDriver() {
        when(mHelper.isVirtualDisplay(mTestDisplayId)).thenReturn(true);
        when(mHelper.getOwnerUserIdOnDisplay(mTestDisplayId)).thenReturn(mAnotherUserId);
        when(mHelper.assignUserToExtraDisplay(mTestUserId, mTestDisplayId)).thenReturn(true);

        mDisplayListenerCaptor.getValue().onDisplayAdded(mTestDisplayId);

        verify(mHelper, never()).assignUserToExtraDisplay(mTestUserId, mTestDisplayId);
    }
}
