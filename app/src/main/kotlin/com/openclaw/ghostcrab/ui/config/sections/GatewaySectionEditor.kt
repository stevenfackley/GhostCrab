package com.openclaw.ghostcrab.ui.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.components.IntFieldRow
import com.openclaw.ghostcrab.ui.components.StringFieldRow
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The top-level key [GatewaySectionEditor] edits. */
private const val GATEWAY_SECTION = "gateway"

/** Writes one leaf (path from the section root) into the section JSON. */
private typealias SetLeaf = (path: List<String>, value: JsonElement) -> Unit

private const val BIND_LOOPBACK = "loopback"
private const val PORT_MIN = 1
private const val PORT_MAX = 65535

/**
 * A field the typed gateway editor can compose, in screen order. [GatewaySectionEditor] composes exactly the
 * entries [gatewayFieldsFor] returns and renders `fieldErrors[path]` under a field exactly when [showsError] is
 * set; [inlineErrorFieldsOf] reads the same two facts, so the config editor knows when a validation error is
 * visible inline.
 *
 * @property path Full config path, as `config.patch` validation issues name it.
 * @property showsError Whether the field renders an error under itself.
 */
internal enum class GatewayField(val path: String, val showsError: Boolean) {
    /** Read-only: a wrong mode keeps the gateway from starting, so it is changed on the host. */
    Mode("gateway.mode", showsError = false),
    Port("gateway.port", showsError = true),
    Bind("gateway.bind", showsError = true),

    /** A dropdown of the known modes; it cannot hold an invalid value. */
    AuthMode("gateway.auth.mode", showsError = false),
    Token("gateway.auth.token", showsError = true),
}

/**
 * @param section The decoded section the editor renders.
 * @return The fields [GatewaySectionEditor] composes for [section], in screen order.
 */
internal fun gatewayFieldsFor(section: GatewaySection): List<GatewayField> =
    GatewayField.entries.filter { it != GatewayField.Token || section.showsTokenField() }

/**
 * Typed form editor for the top-level `gateway` section (upstream 2026.10.3 schema, ADR-0007).
 *
 * - When [sectionValue] decodes, the [gatewayFieldsFor] fields are shown and each edit rewrites **exactly one
 *   leaf** of the original JSON ([withLeaf]): unknown keys survive, absent objects stay absent.
 * - When it does not decode (e.g. an `auth.mode` this app does not know), the raw JSON editor is shown with a
 *   notice — never a form pre-filled with defaults.
 *
 * `gateway.mode` is shown read-only: a wrong value keeps the gateway from starting and the diff sheet is the only
 * confirmation, so it is changed on the gateway host.
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
            gatewayFieldsFor(section).forEach { field ->
                GatewayFieldRow(
                    field = field,
                    section = section,
                    error = fieldErrors[field.path].takeIf { field.showsError },
                    setLeaf = setLeaf,
                    onFieldError = { err -> onFieldError(field.path, err) },
                )
            }
        }
    }
}

@Composable
private fun GatewayFieldRow(
    field: GatewayField,
    section: GatewaySection,
    error: String?,
    setLeaf: SetLeaf,
    onFieldError: (String?) -> Unit,
) {
    when (field) {
        GatewayField.Mode -> ModeRow(section.mode)
        GatewayField.Port -> {
            Spacer(modifier = Modifier.height(Spacing.sm))
            IntFieldRow(
                label = stringResource(R.string.config_gateway_port_label),
                value = section.port,
                onValueChange = { port -> setLeaf(listOf("port"), JsonPrimitive(port)) },
                onError = onFieldError,
                min = PORT_MIN,
                max = PORT_MAX,
                error = error,
            )
        }
        GatewayField.Bind -> {
            Spacer(modifier = Modifier.height(Spacing.sm))
            StringFieldRow(
                label = stringResource(R.string.config_gateway_bind_label),
                value = section.bind.orEmpty(),
                onValueChange = { bind -> setLeaf(listOf("bind"), JsonPrimitive(bind)) },
                mono = true,
                error = error,
            )
        }
        GatewayField.AuthMode -> AuthModeRow(section, setLeaf)
        GatewayField.Token -> TokenRow(section.auth.token, error, setLeaf)
    }
}

/** `gateway.mode`, read-only, under the Listener heading. */
@Composable
private fun ModeRow(mode: String?) {
    Text(text = stringResource(R.string.config_gateway_listener_title), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = Spacing.sm) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xs)) {
            Text(
                text = stringResource(R.string.config_gateway_mode_label),
                style = MaterialTheme.typography.labelSmall,
                color = BrandTokens.colorTextSecondary,
            )
            Text(
                text = mode ?: stringResource(R.string.config_gateway_mode_unset),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.config_gateway_mode_readonly_hint),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorTextSecondary,
            )
        }
    }
}

/** `gateway.auth.mode` under the Auth heading, with the `none`-off-loopback warning. */
@Composable
private fun AuthModeRow(section: GatewaySection, setLeaf: SetLeaf) {
    val mode = section.auth.mode
    Spacer(modifier = Modifier.height(Spacing.md))
    Text(text = stringResource(R.string.config_gateway_auth_title), style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    EnumDropdownRow(
        label = stringResource(
            if (mode == null) {
                R.string.config_gateway_auth_mode_unset_label
            } else {
                R.string.config_gateway_auth_mode_label
            },
        ),
        selected = mode ?: AuthMode.Token,
        options = AuthMode.entries,
        onSelected = { selected -> setLeaf(listOf("auth", "mode"), JsonPrimitive(selected.wireName)) },
        labelFor = { it.wireName },
    )
    if (mode == AuthMode.None && section.bind != null && section.bind != BIND_LOOPBACK) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.config_gateway_auth_none_warning),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTokens.colorAmberWarn,
        )
    }
}

/** `gateway.auth.token`; the redacted secret is never shown, and an empty field restores it. */
@Composable
private fun TokenRow(token: String?, error: String?, setLeaf: SetLeaf) {
    Spacer(modifier = Modifier.height(Spacing.sm))
    StringFieldRow(
        label = stringResource(
            if (token == OPENCLAW_REDACTED) {
                R.string.config_gateway_auth_token_redacted_label
            } else {
                R.string.config_gateway_auth_token_label
            },
        ),
        value = token?.takeIf { it != OPENCLAW_REDACTED }.orEmpty(),
        onValueChange = { typed ->
            setLeaf(listOf("auth", "token"), JsonPrimitive(typed.ifEmpty { OPENCLAW_REDACTED }))
        },
        mono = true,
        secret = true,
        error = error,
    )
}

/**
 * Config paths the section editor for [sectionKey] shows an error under, given the value it would render. The
 * config editor uses it to decide whether a gateway validation error is visible inline or must also be reported
 * as a save error.
 *
 * @param sectionKey Top-level section key.
 * @param sectionValue The section JSON the editor renders (pending edit or server value).
 * @return Full config paths (e.g. `gateway.port`) of the composed fields that render an error; empty for a raw
 *   JSON section and for a `gateway` section the typed editor cannot model.
 */
fun inlineErrorFieldsOf(sectionKey: String, sectionValue: JsonElement): Set<String> {
    val section = sectionValue.takeIf { sectionKey == GATEWAY_SECTION }?.toGatewaySectionOrNull()
        ?: return emptySet()
    return gatewayFieldsFor(section).filter { it.showsError }.mapTo(mutableSetOf()) { it.path }
}

/** The token field is shown for `auth.mode` token and while the mode is unset (the gateway infers it). */
private fun GatewaySection.showsTokenField(): Boolean = auth.mode == AuthMode.Token || auth.mode == null
