// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.leanbitlab.leantype.voice.VoiceConstants
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.TextInputDialog
import org.json.JSONArray

/**
 * Editor for the voice-input custom vocabulary (one word per entry), stored as
 * a JSON array in the voice_custom_vocabulary preference. Read by
 * [helium314.keyboard.latin.voice.VoiceInputPrefs].
 */
@Composable
fun VoiceVocabularyScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val words = remember(refreshTrigger) { loadVocabulary(ctx) }
    var editWord by remember { mutableStateOf<Pair<Int, String>?>(null) } // index, word ("" = new)

    Box(Modifier.fillMaxSize()) {
        SearchScreen(
            onClickBack = onClickBack,
            title = { Text("Custom Vocabulary (${words.size})") },
            filteredItems = { term ->
                words.mapIndexed { i, w -> i to w }.filter {
                    it.second.lowercase().contains(term.lowercase())
                }
            },
            itemContent = { (index, word) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { editWord = index to word },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    ) {
                        Text(word, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            removeWord(ctx, index)
                            refreshTrigger++
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bin),
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                }
            }
        )
        ExtendedFloatingActionButton(
            onClick = { editWord = -1 to "" },
            text = { Text(stringResource(R.string.user_dict_add_word_button)) },
            icon = { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.user_dict_add_word_button)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(all = 12.dp)
                .then(Modifier.safeDrawingPadding())
        )
    }

    val current = editWord
    if (current != null) {
        TextInputDialog(
            onDismissRequest = { editWord = null; refreshTrigger++ },
            onConfirmed = {
                saveWord(ctx, current.first, it.trim())
                editWord = null
                refreshTrigger++
            },
            title = { Text(if (current.first >= 0) stringResource(R.string.user_dict_settings_edit_dialog_title) else stringResource(R.string.user_dict_add_word_button)) },
            initialText = current.second,
            checkTextValid = { it.isNotBlank() }
        )
    }
}

private fun loadVocabulary(ctx: android.content.Context): List<String> {
    return try {
        val raw = ctx.prefs().getString(VoiceConstants.PREF_VOICE_CUSTOM_VOCABULARY, "") ?: ""
        if (raw.trimStart().startsWith("[")) {
            JSONArray(raw).let { arr -> List(arr.length()) { arr.optString(it) } }.filter { it.isNotBlank() }
        } else {
            // Legacy comma/newline format
            raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun storeWords(ctx: android.content.Context, words: List<String>) {
    val arr = JSONArray()
    words.forEach { arr.put(it) }
    ctx.prefs().edit().putString(VoiceConstants.PREF_VOICE_CUSTOM_VOCABULARY, arr.toString()).apply()
}

private fun saveWord(ctx: android.content.Context, index: Int, word: String) {
    val words = loadVocabulary(ctx).toMutableList()
    if (index in words.indices) words[index] = word else words.add(word)
    storeWords(ctx, words)
}

private fun removeWord(ctx: android.content.Context, index: Int) {
    val words = loadVocabulary(ctx).toMutableList()
    if (index in words.indices) {
        words.removeAt(index)
        storeWords(ctx, words)
    }
}
