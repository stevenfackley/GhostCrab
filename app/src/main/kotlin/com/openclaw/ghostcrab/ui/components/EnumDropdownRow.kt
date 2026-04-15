package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * A labeled exposed dropdown menu for selecting an enum value, rendered inside a [GlassSurface].
 *
 * @param T Enum type.
 * @param label Field label.
 * @param selected Currently selected enum value.
 * @param options List of valid options to display.
 * @param onSelected Called when the user selects a new option.
 * @param labelFor Converts an enum value to a display string (default: [Enum.name]).
 * @param modifier Applied to the outer [GlassSurface].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Enum<T>> EnumDropdownRow(
    label: String,
    selected: T,
    options: List<T>,
    onSelected: (T) -> Unit,
    labelFor: (T) -> String = { it.name },
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    GlassSurface(modifier = modifier.fillMaxWidth(), contentPadding = Spacing.sm) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs),
        ) {
            OutlinedTextField(
                value = labelFor(selected),
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelFor(option)) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}
