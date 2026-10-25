// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.offline.model.ModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BarkModelInstallTest {

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        modelManager = ModelManager(context)
    }

    /** Sherpa transducer layout zip: same file names as GigaAM zips. */
    private fun createBarkZip(name: String): File {
        val zip = File(context.cacheDir, name)
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("encoder.int8.onnx"))
            zos.write(ByteArray(1_200_000))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("decoder.int8.onnx"))
            zos.write(ByteArray(2048))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("joiner.int8.onnx"))
            zos.write(ByteArray(2048))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("tokens.txt"))
            zos.write("a 0\nb 1\nc 2\nd 3\ne 4\nf 5\ng 6\nh 7\ni 8\nj 9\nk 10\nl 11\nm 12\nn 13\n".toByteArray())
            zos.closeEntry()
        }
        return zip
    }

    @Test
    fun registry_containsBarkEntry() {
        val item = VoiceModelRegistry.findById("underdog-bark-3-0-int8")
        assertNotNull(item)
        assertEquals(VoiceConstants.ENGINE_BARK, item!!.engineType)
        assertEquals("en", item.languageCode)
        assertEquals(VoiceConstants.ENGINE_BARK, VoiceConstants.OFFLINE_ENGINE_BARK)
    }

    @Test
    fun barkZipImportWithExplicitEngineIsValid() {
        val zip = createBarkZip("underdog-bark-3.0-int8.zip")
        assertTrue(modelManager.importModelFile(zip, VoiceConstants.ENGINE_BARK))
        assertTrue(modelManager.isModelReady(VoiceConstants.ENGINE_BARK))
        val state = modelManager.getModelState(VoiceConstants.ENGINE_BARK)
        assertEquals(ModelState.STATE_READY, state.state)
    }

    @Test
    fun barkZipImportByFilenameAutoDetectsBark() {
        // Same layout as GigaAM: a "bark" filename is what disambiguates.
        val zip = createBarkZip("bark-something.zip")
        assertTrue(modelManager.importModelFile(zip, VoiceConstants.ENGINE_WHISPER))
        assertTrue(modelManager.isModelReady(VoiceConstants.ENGINE_BARK))
    }

    @Test
    fun transducerZipWithoutBarkHintDoesNotInstallBark() {
        val zip = createBarkZip("mystery-model.zip")
        assertTrue(modelManager.importModelFile(zip, VoiceConstants.ENGINE_WHISPER))
        // Resolves to the default transducer engine (GigaAM), never Bark.
        assertFalse(modelManager.isModelReady(VoiceConstants.ENGINE_BARK))
        assertTrue(modelManager.isModelReady(VoiceConstants.ENGINE_GIGAAM))
    }
}
