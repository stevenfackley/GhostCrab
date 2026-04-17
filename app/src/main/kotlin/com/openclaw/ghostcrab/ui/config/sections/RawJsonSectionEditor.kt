package com.openclaw.ghostcrab.ui.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val PrettyJson = Json { prettyPrint = true }

/**
 * A multi-line raw JSON editor for an arbitrary config section.
 *
 * Displays the pretty-printed [sectionValue]. On each keystroke it attempts to parse
 * the edited text as JSON; if parsing fails, an inline error is shown and [onEdit] is
 * NOT called. [onEdit] is called only when the current text is valid JSON.
 *
 * Uses JetBrains Mono throughout.
 *
 * @param sectionKey Top-level section key (used only for the label).
 * @param sectionValue Current raw JSON value.
 * @param onEdit Called with the new [JsonElement] when the text is valid JSON.
 */
@Composable
fun RawJsonSectionEditor(
    sectionKey: String,
    sectionValue: JsonElement,
    onEdit: (JsonElement) -> Unit,
) {
    val prettyJson = remember(sectionValue) {
        PrettyJson.encodeToString(JsonElement.serializer(), sectionValue)
    }
    var rawText by remember(prettyJson) { mutableStateOf(prettyJson) }
    var parseError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = rawText,
            onValueChange = { text ->
                rawText = text
                val parsed = runCatching { Json.parseToJsonElement(text) }
                if (parsed.isSuccess) {
                    parseError = null
                    onEdit(parsed.getOrThrow())
                } else {
                    parseError = parsed.exceptionOrNull()?.message ?: "Invalid JSON"
                }
            },
            label = { Text(sectionKey) },
            isError = parseError != null,
            textStyle = TextStyle(fontFamily = MonoFontFamily),
            singleLine = false,
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        if (parseError != null) {
            Text(
                text = parseError!!,
                color = BrandTokens.colorCrimsonError,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs),
            )
        }
    }
}
