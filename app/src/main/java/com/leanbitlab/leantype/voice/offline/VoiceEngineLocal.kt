// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline

import android.content.Context

object VoiceEngineLocal {
    @Volatile private var controller: VoiceEngineController? = null

    fun getInstance(context: Context): VoiceEngineController {
        return controller ?: synchronized(this) {
            controller ?: VoiceEngineController(context.applicationContext).also { controller = it }
        }
    }
}
