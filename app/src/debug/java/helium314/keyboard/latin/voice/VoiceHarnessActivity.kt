// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Debug-build-only trampoline: started from Shizuku/adb (exported, NoDisplay)
 * with the same extras as VoiceHarnessService; forwards to the non-exported
 * service, which cannot be started cross-uid directly.
 */
class VoiceHarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = Intent(this, VoiceHarnessService::class.java)
        svc.putExtras(intent)
        startService(svc)
        finish()
    }
}
