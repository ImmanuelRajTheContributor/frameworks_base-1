/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util.legion;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.os.RemoteException;
import android.util.Log;

public class ThemesUtils {

    public static final String TAG = "ThemesUtils";

    public static final String[] SOLARIZED_DARK = {
            "com.android.theme.solarizeddark.system",
            "com.android.theme.solarizeddark.systemui",
    };

    public static final String[] BAKED_GREEN = {
            "com.android.theme.bakedgreen.system",
            "com.android.theme.bakedgreen.systemui",
    };

    public static final String[] CHOCO_X = {
            "com.android.theme.chocox.system",
            "com.android.theme.chocox.systemui",
    };

    public static final String[] PITCH_BLACK = {
            "com.android.theme.pitchblack.system",
            "com.android.theme.pitchblack.systemui",
    };

    public static final String[] DARK_GREY = {
            "com.android.theme.darkgrey.system",
            "com.android.theme.darkgrey.systemui",
    };
    public static final String[] MATERIAL_OCEAN = {
            "com.android.theme.materialocean.system",
            "com.android.theme.materialocean.systemui",
    };
    public static final String[] XTENDED_CLEAR = {
            "com.android.theme.xtendedclear.system",
            "com.android.theme.xtendedclear.systemui",
    };

     // Switch themes
    private static final String[] SWITCH_THEMES = {
        "com.android.system.switch.stock", // 0
        "com.android.system.switch.oneplus", // 1
	"com.android.system.switch.narrow", // 2
        "com.android.system.switch.contained", // 3
        "com.android.system.switch.telegram", // 4
    };

    // Panel_bg
    public static final String[] PANEL_BG_STYLE = {
        "com.jrinfected.panel.batik", // 1
        "com.jrinfected.panel.kece", // 2
        "com.jrinfected.panel.outline", // 3
    };

    // Qs Shape
    public static final String[] QS_SHAPE = {
        "com.jrinfected.qs.shape.a",
        "com.jrinfected.qs.shape.b",
        "com.jrinfected.qs.shape.c",
        "com.jrinfected.qs.shape.d",
        "com.jrinfected.qs.shape.e",
        "com.jrinfected.qs.shape.f",
        "com.jrinfected.qs.shape.g",
        "com.jrinfected.qs.shape.h",
        "com.jrinfected.qs.shape.i",
        "com.jrinfected.qs.shape.j",
        "com.jrinfected.qs.shape.k",
        "com.jrinfected.qs.shape.l",
        "com.jrinfected.qs.shape.m",
        "com.jrinfected.qs.shape.n",
        "com.jrinfected.qs.shape.o",
        "com.jrinfected.qs.shape.p",
        "com.jrinfected.qs.shape.q",
        "com.jrinfected.qs.shape.r",
        "com.jrinfected.qs.shape.s",
};

	// QS header themes
    private static final String[] QS_HEADER_THEMES = {
        "com.android.systemui.qsheader.black", // 0
        "com.android.systemui.qsheader.grey", // 1
        "com.android.systemui.qsheader.lightgrey", // 2
        "com.android.systemui.qsheader.accent", // 3
        "com.android.systemui.qsheader.transparent", // 4
    };

    // Switches qs header style to user selected.
    public static void updateQSHeaderStyle(IOverlayManager om, int userId, int qsHeaderStyle) {
        if (qsHeaderStyle == 0) {
            stockQSHeaderStyle(om, userId);
        } else {
            try {
                om.setEnabled(QS_HEADER_THEMES[qsHeaderStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change qs header theme", e);
            }
        }
    }

    // Switches qs header style back to stock.
    public static void stockQSHeaderStyle(IOverlayManager om, int userId) {
        // skip index 0
        for (int i = 1; i < QS_HEADER_THEMES.length; i++) {
            String qsheadertheme = QS_HEADER_THEMES[i];
            try {
                om.setEnabled(qsheadertheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public static void updateSwitchStyle(IOverlayManager om, int userId, int switchStyle) {
        if (switchStyle == 0) {
            stockSwitchStyle(om, userId);
        } else {
            try {
                om.setEnabled(SWITCH_THEMES[switchStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change switch theme", e);
            }
        }
    }

    public static void stockSwitchStyle(IOverlayManager om, int userId) {
        for (int i = 0; i < SWITCH_THEMES.length; i++) {
            String switchtheme = SWITCH_THEMES[i];
            try {
                om.setEnabled(switchtheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
