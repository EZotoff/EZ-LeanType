// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline

import android.app.Service
import android.content.Intent
import android.os.IBinder

class VoiceEngineService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        return VoiceEngineLocal.getInstance(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        VoiceEngineLocal.getInstance(applicationContext).release()
    }
}
