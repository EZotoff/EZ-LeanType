// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.leanbitlab.leantype.voice.offline.VoiceEngineLocal
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object VoiceDownloadDispatcher {

    private const val TAG = "VoiceDownloadDispatcher"

    val downloadingModelId = mutableStateOf<String?>(null)
    val downloadProgress = mutableFloatStateOf(0f)

    fun hasInternetPermission(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    }

    fun isModelDownloading(modelId: String): Boolean {
        return downloadingModelId.value == modelId
    }

    suspend fun downloadAndInstall(
        context: Context,
        model: VoiceModelItem,
        pluginManager: VoicePluginManager,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!hasInternetPermission(context)) {
            withContext(Dispatchers.Main) {
                fallbackToBrowser(context, model)
            }
            return@withContext
        }

        withContext(Dispatchers.Main) {
            downloadingModelId.value = model.id
            downloadProgress.floatValue = 0f
        }

        val cacheDir = File(context.cacheDir, "models")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val tempFile = File(cacheDir, "download_${model.id}.bin")
        if (tempFile.exists()) tempFile.delete()

        try {
            val fileName = model.downloadUrl.substringAfterLast('/')

            // 1. FAST-PATH: Check if model file already exists on local device storage
            val localCandidates = listOf(
                File("/workspace/wise-bose/build-outputs", fileName),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName),
                File("/sdcard/Download", fileName),
                File("/storage/emulated/0/Download", fileName)
            )

            var localFoundFile: File? = null
            for (cand in localCandidates) {
                try {
                    if (cand.exists() && cand.isFile && cand.length() > 1024 * 1024) {
                        localFoundFile = cand
                        break
                    }
                } catch (_: Exception) {}
            }

            if (localFoundFile != null) {
                Log.i(TAG, "Found pre-existing local copy at ${localFoundFile.absolutePath} (${localFoundFile.length()} bytes)")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Installing local file ${localFoundFile.name}...", Toast.LENGTH_SHORT).show()
                }
                localFoundFile.inputStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        val totalBytes = localFoundFile.length()
                        var copied = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            copied += bytesRead
                            if (totalBytes > 0) {
                                val prog = (copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                withContext(Dispatchers.Main) {
                                    downloadProgress.floatValue = prog
                                }
                            }
                        }
                    }
                }
            } else {
                // 2. NETWORK DOWNLOAD: Try candidate URLs
                val urlsToTry = mutableListOf<String>()

                if (fileName.isNotEmpty()) {
                    urlsToTry.add("http://127.0.0.1:8080/$fileName")
                    urlsToTry.add("http://127.0.0.1:8888/$fileName")
                    urlsToTry.add("http://localhost:8080/$fileName")
                    urlsToTry.add("http://192.168.50.52:8080/$fileName")
                    urlsToTry.add("http://192.168.50.52:8888/$fileName")
                }
                if (!urlsToTry.contains(model.downloadUrl)) {
                    urlsToTry.add(model.downloadUrl)
                }
                if (model.backupDownloadUrl.isNotEmpty() && !urlsToTry.contains(model.backupDownloadUrl)) {
                    urlsToTry.add(model.backupDownloadUrl)
                }

                var downloadSuccess = false
                var lastException: Exception? = null

                for (tryUrl in urlsToTry) {
                    try {
                        Log.i(TAG, "Attempting in-app download for ${model.displayName} from $tryUrl")
                        var currentUrl = URL(tryUrl)
                        var conn = currentUrl.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "LeanType-Android")
                        conn.setRequestProperty("Accept-Encoding", "identity")
                        conn.connectTimeout = 3000
                        conn.readTimeout = 60000
                        conn.connect()

                        var redirectCount = 0
                        while ((conn.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                                    conn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                                    conn.responseCode == 307 || conn.responseCode == 308) && redirectCount < 8) {
                            val location = conn.getHeaderField("Location") ?: break
                            currentUrl = URL(location)
                            conn = currentUrl.openConnection() as HttpURLConnection
                            conn.instanceFollowRedirects = true
                            conn.setRequestProperty("User-Agent", "LeanType-Android")
                            conn.setRequestProperty("Accept-Encoding", "identity")
                            conn.connectTimeout = 3000
                            conn.readTimeout = 60000
                            conn.connect()
                            redirectCount++
                        }

                        if (conn.responseCode != HttpURLConnection.HTTP_OK && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                            throw Exception("HTTP ${conn.responseCode}")
                        }

                        val totalBytes = conn.contentLengthLong
                        var downloadedBytes = 0L

                        conn.inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(65536)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    if (totalBytes > 0L) {
                                        val prog = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                        withContext(Dispatchers.Main) {
                                            downloadProgress.floatValue = prog
                                        }
                                    }
                                }
                            }
                        }
                        downloadSuccess = true
                        Log.i(TAG, "Successfully downloaded ${model.displayName} from $tryUrl (${tempFile.length()} bytes)")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Download attempt failed for $tryUrl: ${e.message}")
                        lastException = e
                        try { tempFile.delete() } catch (_: Exception) {}
                    }
                }

                if (!downloadSuccess) {
                    throw lastException ?: Exception("All download mirrors failed")
                }
            }

            Log.i(TAG, "Download complete (${tempFile.length()} bytes). Importing model...")

            val imported = VoiceEngineLocal.getInstance(context)
                .modelManager
                .importModelFile(tempFile, model.engineType)

            try { tempFile.delete() } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                downloadingModelId.value = null
                downloadProgress.floatValue = 0f
                if (imported) {
                    context.prefs().edit().putString("installed_model_${model.engineType}", model.id).apply()
                    Toast.makeText(context, "${model.displayName} model installed successfully!", Toast.LENGTH_LONG).show()
                    onSuccess()
                } else {
                    onError("Failed to verify and install ${model.displayName} files.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            try { tempFile.delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                downloadingModelId.value = null
                downloadProgress.floatValue = 0f
                val msg = "Download failed: ${e.message ?: "network error"}. Tap 'Import File' or open browser."
                onError(msg)
                fallbackToBrowser(context, model)
            }
        }
    }

    private fun fallbackToBrowser(context: Context, model: VoiceModelItem) {
        val targetUrl = model.browserUrl.ifEmpty { model.downloadUrl }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Opening download link in browser...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "No browser available to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
