/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.settings.bluetooth;

import android.app.Activity;
import android.os.Bundle;

import com.android.settings.R;

/**
 * Activity for Bluetooth device picker dialog. The device picker logic
 * is implemented in the {@link BluetoothSettings} fragment.
 */
public final class DevicePickerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bluetooth_device_picker);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        try {
            super.onBackPressed();
        } catch (java.lang.IllegalStateException e) {
            android.util.Log.w("DevicePickerActivity" , "IllegalStateException");
        }
    }
}
