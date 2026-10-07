// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.model

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.ModelImportRequest
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

class ModelManager(
    private val context: Context,
    private val onPreDeleteModel: ((engineType: String) -> Unit)? = null
) {
    val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun getModelState(engineType: String): ModelState {
        val targetFile = File(modelsDir, engineType)
        if (!targetFile.exists()) {
            return ModelState(engineType, ModelState.STATE_MISSING, "Model not imported")
        }

        return when (engineType) {
            VoiceConstants.ENGINE_PARAKEET -> {
                if (isTransducerModelValid(targetFile)) {
                    ModelState(engineType, ModelState.STATE_READY, "Parakeet TDT v3 model ready")
                } else {
                    ModelState(engineType, ModelState.STATE_ERROR, "Invalid Parakeet TDT model files")
                }
            }
            VoiceConstants.ENGINE_GIGAAM -> {
                if (isTransducerModelValid(targetFile)) {
                    ModelState(engineType, ModelState.STATE_READY, "GigaAM v3 E2E RNN-T ready")
                } else {
                    ModelState(engineType, ModelState.STATE_ERROR, "Invalid GigaAM model files")
                }
            }
            VoiceConstants.ENGINE_PHONON -> {
                if (isPhononModelValid(targetFile)) {
                    ModelState(engineType, ModelState.STATE_READY, "Phonon-2 ONNX ready")
                } else {
                    ModelState(engineType, ModelState.STATE_ERROR, "Invalid Phonon-2 model files (need preprocessor-model.onnx, encoder-model.int8.onnx, decoder_joint-model.int8.onnx, vocab.txt)")
                }
            }
            VoiceConstants.ENGINE_WHISPER -> {
                val whisperFile = File(targetFile, "model.bin").takeIf { it.exists() } ?: targetFile
                if (isWhisperHeaderValid(whisperFile)) {
                    ModelState(engineType, ModelState.STATE_READY, "Whisper model ready")
                } else {
                    ModelState(engineType, ModelState.STATE_ERROR, "Not a valid Whisper ASR model")
                }
            }
            else -> {
                ModelState(engineType, ModelState.STATE_MISSING, "Unsupported engine")
            }
        }
    }

    fun isModelReady(engineType: String): Boolean {
        val targetFile = File(modelsDir, engineType)
        if (!targetFile.exists()) return false

        return when (engineType) {
            VoiceConstants.ENGINE_PARAKEET, VoiceConstants.ENGINE_GIGAAM -> isTransducerModelValid(targetFile)
            VoiceConstants.ENGINE_PHONON -> isPhononModelValid(targetFile)
            VoiceConstants.ENGINE_WHISPER -> {
                val whisperFile = File(targetFile, "model.bin").takeIf { it.exists() } ?: targetFile
                isWhisperHeaderValid(whisperFile)
            }
            else -> false
        }
    }

    fun getModelDir(engineType: String): File {
        return File(modelsDir, engineType)
    }

    fun isParakeetModelValid(fileOrDir: File): Boolean = isTransducerModelValid(fileOrDir)

    /** tiyuvta/Phonon-2-ONNX export layout. */
    fun isPhononModelValid(fileOrDir: File): Boolean {
        if (!fileOrDir.exists()) return false
        if (!fileOrDir.isDirectory) return false
        val hasPreprocessor = File(fileOrDir, "preprocessor-model.onnx").length() > 1024
        val hasEncoder = File(fileOrDir, "encoder-model.int8.onnx").length() > 10L * 1024 * 1024 ||
                File(fileOrDir, "encoder-model.onnx").length() > 10L * 1024 * 1024
        val hasDecoderJoint = File(fileOrDir, "decoder_joint-model.int8.onnx").length() > 1024 * 1024 ||
                File(fileOrDir, "decoder_joint-model.onnx").length() > 1024 * 1024
        val hasVocab = File(fileOrDir, "vocab.txt").length() > 1024
        return hasPreprocessor && hasEncoder && hasDecoderJoint && hasVocab
    }

    fun isTransducerModelValid(fileOrDir: File): Boolean {
        if (!fileOrDir.exists()) return false
        if (fileOrDir.isDirectory) {
            val hasEncoder = (File(fileOrDir, "encoder.int8.onnx").length() > 1024 * 1024) ||
                    (File(fileOrDir, "encoder.onnx").length() > 1024 * 1024) ||
                    (File(fileOrDir, "encoder.fp16.onnx").length() > 1024 * 1024) ||
                    (File(fileOrDir, "model.int8.onnx").length() > 1024 * 1024) ||
                    (File(fileOrDir, "model.onnx").length() > 1024 * 1024) ||
                    (File(fileOrDir, "gigaam_v3_e2e_rnnt_encoder_int8.onnx").length() > 1024 * 1024)
            val hasTokens = (File(fileOrDir, "tokens.txt").length() > 50) ||
                    (File(fileOrDir, "gigaam_v3_e2e_rnnt_tokens.txt").length() > 50)
            val hasDecoder = File(fileOrDir, "decoder.int8.onnx").exists() || 
                    File(fileOrDir, "decoder.onnx").exists() ||
                    File(fileOrDir, "gigaam_v3_e2e_rnnt_decoder.onnx").exists()
            val hasJoiner = File(fileOrDir, "joiner.int8.onnx").exists() || 
                    File(fileOrDir, "joiner.onnx").exists() ||
                    File(fileOrDir, "gigaam_v3_e2e_rnnt_joint.onnx").exists() ||
                    File(fileOrDir, "joint.onnx").exists()
            return hasEncoder && hasTokens && hasDecoder && hasJoiner
        }
        return false
    }

    fun isWhisperHeaderValid(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                if (read < 4) return false
                val isGguf = header[0] == 0x47.toByte() && header[1] == 0x47.toByte() &&
                        header[2] == 0x55.toByte() && header[3] == 0x46.toByte()
                val magic = String(header, Charsets.US_ASCII)
                val isGgml = magic == "ggml" || magic == "lmgg" || magic == "ggmf" || file.name.startsWith("ggml-")
                isGguf || isGgml
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking whisper file header", e)
            false
        }
    }

    fun importModelFile(sourceFile: File, targetEngine: String): Boolean {
        if (!sourceFile.exists() || sourceFile.length() < 1024) {
            Log.e(TAG, "Source model file missing or empty: ${sourceFile.absolutePath}")
            return false
        }

        // Auto-detect engine if there is a mismatch
        var resolvedEngine = targetEngine
        if (isZipFile(sourceFile)) {
            val fileName = sourceFile.name.lowercase()
            resolvedEngine = if (targetEngine == VoiceConstants.ENGINE_GIGAAM || fileName.contains("gigaam")) {
                VoiceConstants.ENGINE_GIGAAM
            } else {
                VoiceConstants.ENGINE_PARAKEET
            }
        } else if (isWhisperHeaderValid(sourceFile)) {
            resolvedEngine = VoiceConstants.ENGINE_WHISPER
        }

        onPreDeleteModel?.invoke(resolvedEngine)
        val finalTarget = File(modelsDir, resolvedEngine)

        try {
            if (resolvedEngine == VoiceConstants.ENGINE_PARAKEET || resolvedEngine == VoiceConstants.ENGINE_GIGAAM) {
                finalTarget.deleteRecursively()
                finalTarget.mkdirs()
                if (isZipFile(sourceFile)) {
                    unzipToDirectory(sourceFile, finalTarget)
                } else {
                    val dest = File(finalTarget, "encoder.int8.onnx")
                    sourceFile.copyTo(dest, overwrite = true)
                }

                if (!isTransducerModelValid(finalTarget)) {
                    Log.e(TAG, "$resolvedEngine model validation failed for: ${finalTarget.name}")
                    finalTarget.deleteRecursively()
                    return false
                }
                Log.i(TAG, "$resolvedEngine model installed successfully: ${finalTarget.absolutePath}")
                return true
            }

            if (resolvedEngine == VoiceConstants.ENGINE_WHISPER) {
                finalTarget.deleteRecursively()
                finalTarget.mkdirs()
                val targetFile = File(finalTarget, "model.bin")
                sourceFile.copyTo(targetFile, overwrite = true)
                if (!isWhisperHeaderValid(targetFile)) {
                    Log.e(TAG, "Whisper model header check failed for: ${targetFile.name}")
                    finalTarget.deleteRecursively()
                    return false
                }
                Log.i(TAG, "Whisper model installed successfully: ${targetFile.absolutePath}")
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model for $resolvedEngine", e)
            finalTarget.deleteRecursively()
            return false
        }
    }

    fun importModelDirectly(request: ModelImportRequest): Pair<Boolean, String> {
        var targetEngine = request.engineType
        val tmpFile = File(modelsDir, "import_${System.currentTimeMillis()}.tmp")

        return try {
            Log.i(TAG, "Starting model import from PFD, requested engine: $targetEngine, size: ${request.sizeBytes} bytes")
            ParcelFileDescriptor.AutoCloseInputStream(request.file).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }

            val sha256 = request.sha256
            if (sha256 != null && !verifySha256(tmpFile, sha256)) {
                Log.e(TAG, "SHA256 verification failed for $targetEngine")
                tmpFile.delete()
                return Pair(false, targetEngine)
            }

            // Auto-detect engine from physical contents
            if (isZipFile(tmpFile)) {
                if (targetEngine != VoiceConstants.ENGINE_GIGAAM) {
                    targetEngine = VoiceConstants.ENGINE_PARAKEET
                }
                // Phonon-2 export zips contain its own file set; detect by the
                // distinctive preprocessor file once extracted, else keep the
                // pre-validated engine type chosen by the caller.
                if (targetEngine == VoiceConstants.ENGINE_PARAKEET && looksLikePhononZip(tmpFile)) {
                    targetEngine = VoiceConstants.ENGINE_PHONON
                }
            } else if (isWhisperHeaderValid(tmpFile)) {
                targetEngine = VoiceConstants.ENGINE_WHISPER
            }

            val success = importModelFile(tmpFile, targetEngine)
            tmpFile.delete()
            Pair(success, targetEngine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model directly", e)
            tmpFile.delete()
            Pair(false, targetEngine)
        }
    }

    fun importModelSafely(request: ModelImportRequest): Boolean {
        return importModelDirectly(request).first
    }

    private fun looksLikePhononZip(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            zip.getEntry("preprocessor-model.onnx") != null &&
                    zip.getEntry("vocab.txt") != null &&
                    (zip.getEntry("encoder-model.int8.onnx") != null || zip.getEntry("encoder-model.onnx") != null)
        }
    } catch (e: Exception) {
        false
    }

    private fun isZipFile(file: File): Boolean {        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun unzipToDirectory(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val fileName = File(entry.name).name
                if (entry.isDirectory || fileName.isEmpty() || fileName.startsWith(".") || entry.name.contains("__MACOSX")) continue
                val outFile = File(targetDir, fileName)
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun deleteModel(engineType: String): Boolean {
        onPreDeleteModel?.invoke(engineType)
        val targetDir = File(modelsDir, engineType)
        return targetDir.deleteRecursively()
    }

    private fun verifySha256(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashBytes = digest.digest()
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            hexString.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ModelManager"
    }
}
