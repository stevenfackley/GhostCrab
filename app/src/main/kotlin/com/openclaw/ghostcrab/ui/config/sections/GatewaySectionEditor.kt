package com.openclaw.ghostcrab.ui.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.config.AuthMode
import com.openclaw.ghostcrab.domain.model.config.GatewayAuthSection
import com.openclaw.ghostcrab.domain.model.config.GatewayHttpSection
import com.openclaw.ghostcrab.domain.model.config.GatewayMdnsSection
import com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull
import com.openclaw.ghostcrab.domain.util.withLeaf
import com.openclaw.ghostcrab.ui.components.EnumDropdownRow
import com.openclaw.ghostcrab.ui.components.IntFieldRow
import com.openclaw.ghostcrab.ui.components.StringFieldRow
import com.openclaw.ghostcrab.ui.components.ToggleRow
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Writes one leaf (path from the section root) into the section JSON. */
private typealias SetLeaf = (path: List<String>, value: JsonElement) -> Unit

/**
 * Typed form editor for the `gateway` top-level config section.
 *
 * - When [sectionValue] decodes, typed fields are shown and each edit rewrites **exactly one leaf**
 *   of the original JSON ([withLeaf]): unknown keys survive, absent objects stay absent, and a value
 *   equal to a model default is still written.
 * - When it does not decode (e.g. an `auth.mode` this app does not know), the raw JSON editor is
 *   shown with a notice — never a form pre-filled with defaults.
 *
 * @param sectionValue Current raw JSON for the `gateway` section (pending edit or server value).
 * @param fieldErrors Field path → error message (e.g. `"gateway.http.port"` → `"…"`).
 * @param onEdit Called with the full updated section JSON.
 * @param onFieldError Called when a field validates or fails (field path, message or null).
 */
@Composable
fun GatewaySectionEditor(
    sectionValue: JsonElement,
    fieldErrors: Map<String, String>,
    onEdit: (JsonElement) -> Unit,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    val section = remember(sectionValue) { sectionValue.toGatewaySectionOrNull() }
    val setLeaf: SetLeaf = { path, value -> onEdit(sectionValue.withLeaf(path, value)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (section == null) {
            Text(
                text = stringResource(R.string.config_gateway_untyped_notice),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorAmberWarn,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            RawJsonSectionEditor(sectionKey = "gateway", sectionValue = sectionValue, onEdit = onEdit)
        } else {
            GatewayHttpFields(section.http, fieldErrors, setLeaf, onFieldError)
            Spacer(modifier = Modifier.height(Spacing.md))
            GatewayAuthFields(section.auth, fieldErrors, setLeaf)
            Spacer(modifier = Modifier.height(Spacing.md))
            GatewayMdnsFields(section.mdns, fieldErrors, setLeaf)
        }
    }
}

@Composable
private fun GatewayHttpFields(
    http: GatewayHttpSection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    Text(text = "HTTP", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    StringFieldRow(
        label = "Host",
        value = http.host,
        onValueChange = { host -> setLeaf(listOf("http", "host"), JsonPrimitive(host)) },
        mono = true,
        error = fieldErrors["gateway.http.host"],
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    IntFieldRow(
        label = "Port",
        value = http.port,
        onValueChange = { port -> setLeaf(listOf("http", "port"), JsonPrimitive(port)) },
        onError = { err -> onFieldError("gateway.http.port", err) },
        min = 1,
        max = 65535,
    )
}

@Composable
private fun GatewayAuthFields(
    auth: GatewayAuthSection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
) {
    Text(text = "Auth", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    EnumDropdownRow(
        label = "Mode",
        selected = auth.mode,
        options = AuthMode.entries,
        onSelected = { mode -> setLeaf(listOf("auth", "mode"), JsonPrimitive(mode.name)) },
    )
    if (auth.mode == AuthMode.bearer) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        StringFieldRow(
            label = "Bearer Token",
            value = auth.token ?: "",
            onValueChange = { token ->
                // Merge-patch semantics: JSON null removes the key.
                setLeaf(listOf("auth", "token"), if (token.isEmpty()) JsonNull else JsonPrimitive(token))
            },
            mono = true,
            error = fieldErrors["gateway.auth.token"],
        )
    }
}

@Composable
private fun GatewayMdnsFields(
    mdns: GatewayMdnsSection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
) {
    Text(text = "mDNS", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    ToggleRow(
        label = "Enabled",
        checked = mdns.enabled,
        onCheckedChange = { enabled -> setLeaf(listOf("mdns", "enabled"), JsonPrimitive(enabled)) },
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    StringFieldRow(
        label = "Service Name",
        value = mdns.serviceName,
        onValueChange = { name -> setLeaf(listOf("mdns", "serviceName"), JsonPrimitive(name)) },
        mono = true,
        error = fieldErrors["gateway.mdns.serviceName"],
    )
}
