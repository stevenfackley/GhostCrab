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
import com.openclaw.ghostcrab.domain.model.config.GatewaySection
import com.openclaw.ghostcrab.domain.model.config.OPENCLAW_REDACTED
import com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull
import com.openclaw.ghostcrab.domain.util.withLeaf
import com.openclaw.ghostcrab.ui.components.EnumDropdownRow
import com.openclaw.ghostcrab.ui.components.IntFieldRow
import com.openclaw.ghostcrab.ui.components.StringFieldRow
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The top-level key [GatewaySectionEditor] edits. */
private const val GATEWAY_SECTION = "gateway"

/** Writes one leaf (path from the section root) into the section JSON. */
private typealias SetLeaf = (path: List<String>, value: JsonElement) -> Unit

private const val FIELD_MODE = "gateway.mode"
private const val FIELD_PORT = "gateway.port"
private const val FIELD_BIND = "gateway.bind"
private const val FIELD_TOKEN = "gateway.auth.token"
private const val BIND_LOOPBACK = "loopback"
private const val PORT_MIN = 1
private const val PORT_MAX = 65535

/**
 * Typed form editor for the top-level `gateway` section (upstream 2026.10.3 schema, ADR-0007).
 *
 * - When [sectionValue] decodes, typed fields are shown and each edit rewrites **exactly one leaf** of the original
 *   JSON ([withLeaf]): unknown keys survive, absent objects stay absent.
 * - When it does not decode (e.g. an `auth.mode` this app does not know), the raw JSON editor is shown with a
 *   notice — never a form pre-filled with defaults.
 *
 * The token field never shows the gateway's secret: `config.get` returns it as `__OPENCLAW_REDACTED__`. Clearing the
 * field writes that sentinel back, which the gateway resolves to the stored secret (config RPC secret restoration).
 *
 * @param sectionValue Current raw JSON for the `gateway` section (pending edit or server value).
 * @param fieldErrors Field path → error message (e.g. `"gateway.port"` → `"…"`).
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
            RawJsonSectionEditor(sectionKey = GATEWAY_SECTION, sectionValue = sectionValue, onEdit = onEdit)
        } else {
            GatewayListenerFields(section, fieldErrors, setLeaf, onFieldError)
            Spacer(modifier = Modifier.height(Spacing.md))
            GatewayAuthFields(section, fieldErrors, setLeaf)
        }
    }
}

@Composable
private fun GatewayListenerFields(
    section: GatewaySection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    Text(text = stringResource(R.string.config_gateway_listener_title), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    StringFieldRow(
        label = stringResource(R.string.config_gateway_mode_label),
        value = section.mode.orEmpty(),
        onValueChange = { mode -> setLeaf(listOf("mode"), JsonPrimitive(mode)) },
        mono = true,
        error = fieldErrors[FIELD_MODE],
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    IntFieldRow(
        label = stringResource(R.string.config_gateway_port_label),
        value = section.port,
        onValueChange = { port -> setLeaf(listOf("port"), JsonPrimitive(port)) },
        onError = { err -> onFieldError(FIELD_PORT, err) },
        min = PORT_MIN,
        max = PORT_MAX,
        error = fieldErrors[FIELD_PORT],
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    StringFieldRow(
        label = stringResource(R.string.config_gateway_bind_label),
        value = section.bind.orEmpty(),
        onValueChange = { bind -> setLeaf(listOf("bind"), JsonPrimitive(bind)) },
        mono = true,
        error = fieldErrors[FIELD_BIND],
    )
}

@Composable
private fun GatewayAuthFields(
    section: GatewaySection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
) {
    val auth = section.auth
    Text(text = stringResource(R.string.config_gateway_auth_title), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    EnumDropdownRow(
        label = stringResource(
            if (auth.mode == null) {
                R.string.config_gateway_auth_mode_unset_label
            } else {
                R.string.config_gateway_auth_mode_label
            },
        ),
        selected = auth.mode ?: AuthMode.Token,
        options = AuthMode.entries,
        onSelected = { mode -> setLeaf(listOf("auth", "mode"), JsonPrimitive(mode.wireName)) },
        labelFor = { it.wireName },
    )
    if (auth.mode == AuthMode.None && section.bind != null && section.bind != BIND_LOOPBACK) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.config_gateway_auth_none_warning),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTokens.colorAmberWarn,
        )
    }
    if (section.showsTokenField()) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        StringFieldRow(
            label = stringResource(
                if (auth.token == OPENCLAW_REDACTED) {
                    R.string.config_gateway_auth_token_redacted_label
                } else {
                    R.string.config_gateway_auth_token_label
                },
            ),
            value = auth.token?.takeIf { it != OPENCLAW_REDACTED }.orEmpty(),
            onValueChange = { token ->
                setLeaf(listOf("auth", "token"), JsonPrimitive(token.ifEmpty { OPENCLAW_REDACTED }))
            },
            mono = true,
            secret = true,
            error = fieldErrors[FIELD_TOKEN],
        )
    }
}

/**
 * Config paths the section editor for [sectionKey] shows an error under, given the value it would render. The
 * config editor uses it to decide whether a gateway validation error is visible inline or must also be reported
 * as a save error.
 *
 * @param sectionKey Top-level section key.
 * @param sectionValue The section JSON the editor renders (pending edit or server value).
 * @return Full config paths (e.g. `gateway.port`); empty for a raw JSON section and for a `gateway` section the
 *   typed editor cannot model.
 */
fun inlineErrorFieldsOf(sectionKey: String, sectionValue: JsonElement): Set<String> {
    val section = sectionValue.takeIf { sectionKey == GATEWAY_SECTION }?.toGatewaySectionOrNull()
        ?: return emptySet()
    return setOf(FIELD_MODE, FIELD_PORT, FIELD_BIND) + listOfNotNull(FIELD_TOKEN.takeIf { section.showsTokenField() })
}

/** The token field is shown for `auth.mode` token and while the mode is unset (the gateway infers it). */
private fun GatewaySection.showsTokenField(): Boolean = auth.mode == AuthMode.Token || auth.mode == null
