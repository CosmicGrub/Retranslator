/*
 * Copyright (C) 2012 Google Inc.
 * Copyright (C) 2013-2015 Reece H. Dunn
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
 *
 * Trimmed down for direct in-app bundling (Retroid Translator): keeps only
 * the data-path/resource-check helpers that SpeechSynthesis.java needs. The
 * original Activity-based "download voice data" UI is not used here because
 * espeak-ng-data ships as an APK asset and is unpacked by EspeakDataInstaller
 * on first run instead of being downloaded.
 */

package com.reecedunn.espeak;

import android.content.Context;
import android.util.Log;

import java.io.File;

public class CheckVoiceData {
    private static final String TAG = "CheckVoiceData";

    // NOTE: upstream espeak-ng's list also checks for a "version" file, but the
    // official signed release's compiled espeak-ng-data (which this app's
    // assets/espeak-ng-data was extracted from) does not ship one — only the
    // files below. Requiring it would make hasBaseResources() always fail.
    private static final String[] BASE_RESOURCES = {
            "intonations",
            "phondata",
            "phonindex",
            "phontab",
            "en_dict",
    };

    public static File getDataPath(Context context) {
        return new File(context.getDir("voices", Context.MODE_PRIVATE), "espeak-ng-data");
    }

    public static boolean hasBaseResources(Context context) {
        final File dataPath = getDataPath(context);
        for (String resource : BASE_RESOURCES) {
            final File resourceFile = new File(dataPath, resource);
            if (!resourceFile.exists()) {
                Log.e(TAG, "Missing base resource: " + resourceFile.getPath());
                return false;
            }
        }
        return true;
    }
}
