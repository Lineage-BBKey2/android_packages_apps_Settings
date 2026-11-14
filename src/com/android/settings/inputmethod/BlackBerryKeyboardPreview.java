/* Copyright (C) 2026 The LineageOS Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.inputmethod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.KeyboardLayout;
import android.icu.lang.UCharacter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Preview data for a candidate layout over its native physical-family map. */
final class BlackBerryKeyboardPreview {
    private static final String PACKAGE = "org.lineageos.blackberry.inputdevices";
    private static final String PREFIX = PACKAGE + "/" + PACKAGE
            + ".InputDeviceReceiver/keyboard_";
    private final JSONObject mGlyphs;
    private final boolean mRtl;

    private BlackBerryKeyboardPreview(JSONObject glyphs, boolean rtl) {
        mGlyphs = glyphs;
        mRtl = rtl;
    }

    static String familyFor(InputManager inputManager, Bundle arguments) {
        if (arguments == null) return null;
        InputDeviceIdentifier identifier = arguments.getParcelable(
                InputPeripheralsSettingsUtils.EXTRA_INPUT_DEVICE_IDENTIFIER);
        if (identifier == null) return null;
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device == null || device.isExternal() || device.isVirtual()
                    || device.getVendorId() != 0x0483 || device.getProductId() != 0x1850
                    || !device.getDescriptor().equals(identifier.getDescriptor())) continue;
            switch (device.getName()) {
                case "stmpe_keypad": return "qwerty";
                case "stmpe_azerty_keypad": return "azerty";
                case "stmpe_qwertz_keypad": return "qwertz";
                default: return null;
            }
        }
        return null;
    }

    static BlackBerryKeyboardPreview load(Context context, KeyboardLayout layout, String family) {
        String descriptor = layout.getDescriptor();
        if (!descriptor.startsWith(PREFIX + family + "_")
                || !family.equals(layout.getLayoutType())) return null;
        String name = descriptor.substring(descriptor.lastIndexOf('/') + 1);
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(PACKAGE);
            int id = resources.getIdentifier("bb_preview_" + name.toLowerCase(Locale.ROOT),
                    "raw", PACKAGE);
            if (id == 0) return null;
            try (InputStream stream = resources.openRawResource(id);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new IOException("Oversized preview");
                    bytes.write(buffer, 0, count);
                }
                JSONObject data = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if (data.getInt("version") != 1 || !family.equals(data.getString("family"))
                        || !name.equals(data.getString("layout"))) return null;
                boolean rtl = layout.getLocales().size() > 0
                        && TextUtils.getLayoutDirectionFromLocale(layout.getLocales().get(0))
                                == View.LAYOUT_DIRECTION_RTL;
                return new BlackBerryKeyboardPreview(data.getJSONObject("glyphs"), rtl);
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException
                | IOException | JSONException error) {
            Log.w("BlackBerryKeyboardPreview", "Cannot load candidate preview", error);
            return null;
        }
    }

    String glyph(int scanCode, int layer) {
        JSONArray values = mGlyphs.optJSONArray(Integer.toString(scanCode));
        String value = values == null ? "" : values.optString(layer, "");
        // Show the shape used in an RTL paragraph, not an isolated LTR glyph.
        // These substitutions affect drawing only; candidate KCM values and
        // typed Unicode code points remain unchanged.
        if (mRtl && !value.isEmpty()) {
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < value.length();) {
                int codePoint = value.codePointAt(i);
                display.appendCodePoint(UCharacter.getMirror(codePoint));
                i += Character.charCount(codePoint);
            }
            value = display.toString();
        }
        if (!value.isEmpty() && Character.getType(value.charAt(0))
                == Character.NON_SPACING_MARK) return "\u25cc" + value;
        return value;
    }
}
