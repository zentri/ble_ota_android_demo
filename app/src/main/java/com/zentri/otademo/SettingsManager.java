/*
 * Copyright (C) 2015, Zentri, Inc. All Rights Reserved.
 *
 * The Zentri BLE Android Libraries and Zentri BLE example applications are provided free of charge
 * by Zentri. The combined source code, and all derivatives, are licensed by Zentri SOLELY for use
 * with devices manufactured by Zentri, or devices approved by Zentri.
 *
 * Use of this software on any other devices or hardware platforms is strictly prohibited.
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AS IS AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.zentri.otademo;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager
{
    private static final String OTA_FILENAME_DEFAULT = "1.5.1.0";

    private static final String SAVED_USE_LATEST = "SAVED_USE_LATEST";
    private static final String SAVED_FILENAME = "SAVED_FILENAME";

    public static Settings loadSettings(Activity activity)
    {
        SharedPreferences sharedPref = activity.getPreferences(Context.MODE_PRIVATE);

        Settings settings = new Settings();

        settings.setFirmwareFilename(sharedPref.getString(SAVED_FILENAME, OTA_FILENAME_DEFAULT));
        settings.setUseLatest(sharedPref.getBoolean(SAVED_USE_LATEST, true));

        return settings;
    }

    public static void saveSettings(Activity activity, Settings settings)
    {
        SharedPreferences.Editor editor = activity.getPreferences(Context.MODE_PRIVATE).edit();

        editor.putString(SAVED_FILENAME, settings.getFirmwareFilename());//want to remember last entry for this setting only
        editor.putBoolean(SAVED_USE_LATEST, settings.useLatest());

        editor.commit();
    }
}
