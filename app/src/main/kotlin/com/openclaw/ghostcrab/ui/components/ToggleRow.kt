package com.openclaw.ghostcrab.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * A labeled toggle row rendered inside a [GlassSurface].
 *
 * Label uses Inter (standard UI text). Switch is on the right.
 *
 * @param label Row label displayed on the left.
 * @param checked Current switch state.
 * @param onCheckedChange Called when the user toggles the switch.
 * @param modifier Applied to the outer [GlassSurface].
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.fillMaxWidth(), contentPadding = Spacing.sm) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}
