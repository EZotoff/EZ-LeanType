// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.IVoiceEngine
import com.leanbitlab.leantype.voice.ModelImportRequest
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceEngineInfo
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import com.leanbitlab.leantype.voice.offline.VoiceEngineLocal
import helium314.keyboard.latin.utils.Log

class VoicePluginManager(private val context: Context) : IBinder.DeathRecipient {

    interface PluginConnectionListener {
        fun onPluginConnected(info: VoiceEngineInfo?)
        fun onPluginDisconnected()
    }

    private val localEngine = VoiceEngineLocal.getInstance(context)
    private var connectionListener: PluginConnectionListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLoggedModelState: Pair<String, Int?>? = null

    fun setConnectionListener(listener: PluginConnectionListener?) {
        this.connectionListener = listener
        if (listener != null) {
            val info = getInfo()
            mainHandler.post {
                listener.onPluginConnected(info)
            }
        }
    }

    fun isPluginInstalled(): Boolean = true

    fun isPluginConnected(): Boolean = true

    fun bindIfNeeded(): Boolean {
        val info = getInfo()
        mainHandler.post {
            connectionListener?.onPluginConnected(info)
        }
        return true
    }

    fun unbind() {
        // In-process engine: no-op unbind
    }

    override fun binderDied() {
        // In-process engine: no-op
    }

    fun getInfo(): VoiceEngineInfo? {
        return try {
            localEngine.info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get plugin info", e)
            null
        }
    }

    fun getModelState(engineType: String): ModelState? {
        return try {
            val state = localEngine.getModelState(engineType)
            if (lastLoggedModelState?.first != engineType || lastLoggedModelState?.second != state?.state) {
                lastLoggedModelState = Pair(engineType, state?.state)
                Log.i(TAG, "getModelState for $engineType: ${state?.state} (${state?.message})")
            }
            state
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get model state for $engineType", e)
            null
        }
    }

    fun importModelDirectly(request: ModelImportRequest): Pair<Boolean, String> {
        return try {
            localEngine.importModelDirectly(request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model directly", e)
            Pair(false, request.engineType)
        } finally {
            try {
                request.file.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close PFD for model import", e)
            }
        }
    }

    fun importModelSafely(request: ModelImportRequest) {
        importModelDirectly(request)
    }

    suspend fun bindAndImport(request: ModelImportRequest): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            localEngine.importModel(request)
            true
        } catch (e: Exception) {
            Log.e(TAG, "importModel failed", e)
            false
        } finally {
            try { request.file.close() } catch (_: Exception) {}
        }
    }

    fun unloadModel(engineType: String) {
        try {
            localEngine.unloadModel(engineType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unload model $engineType", e)
        }
    }

    fun deleteModel(engineType: String) {
        try {
            localEngine.deleteModel(engineType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model $engineType", e)
        }
    }

    fun startSession(
        config: VoiceSessionConfig,
        audioInput: ParcelFileDescriptor,
        callback: IVoiceCallback
    ): Boolean {
        return try {
            localEngine.startSession(config, audioInput, callback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice session", e)
            false
        }
    }

    fun stopSession() {
        try {
            localEngine.stopSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop session", e)
        }
    }

    fun cancelSession() {
        try {
            localEngine.cancelSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel session", e)
        }
    }

    fun release() {
        try {
            localEngine.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release voice engine", e)
        }
    }

    companion object {
        private const val TAG = "VoicePluginManager"
    }
}
