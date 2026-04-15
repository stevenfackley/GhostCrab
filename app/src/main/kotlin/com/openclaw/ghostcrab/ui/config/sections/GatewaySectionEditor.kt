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
import com.openclaw.ghostcrab.domain.model.config.AuthMode
import com.openclaw.ghostcrab.domain.model.config.GatewayAuthSection
import com.openclaw.ghostcrab.domain.model.config.GatewayHttpSection
import com.openclaw.ghostcrab.domain.model.config.GatewayMdnsSection
import com.openclaw.ghostcrab.domain.model.config.GatewaySection
import com.openclaw.ghostcrab.domain.model.config.toGatewaySection
import com.openclaw.ghostcrab.domain.model.config.toJsonElement
import com.openclaw.ghostcrab.ui.components.EnumDropdownRow
import com.openclaw.ghostcrab.ui.components.IntFieldRow
import com.openclaw.ghostcrab.ui.components.StringFieldRow
import com.openclaw.ghostcrab.ui.components.ToggleRow
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.JsonElement

/**
 * Typed form editor for the `gateway` top-level config section.
 *
 * Parses [sectionValue] into a [GatewaySection] on entry (falls back to defaults on parse failure).
 * Any field edit re-serializes the full [GatewaySection] and calls [onEdit].
 *
 * Sub-sections rendered:
 * - **HTTP**: host (mono), port (1–65535)
 * - **Auth**: mode dropdown, bearer token field (visible only when mode = bearer)
 * - **mDNS**: enabled toggle, serviceName (mono)
 *
 * @param sectionValue Current raw JSON value for the `gateway` section.
 * @param fieldErrors Map of field path → error message (e.g. `"gateway.http.port"` → `"…"`).
 * @param onEdit Called with the updated full [GatewaySection] encoded as [JsonElement].
 * @param onFieldError Called when a field validates or fails (field path, message or null).
 */
@Composable
fun GatewaySectionEditor(
    sectionValue: JsonElement,
    fieldErrors: Map<String, String>,
    onEdit: (JsonElement) -> Unit,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    val section = remember(sectionValue) {
        runCatching { sectionValue.toGatewaySection() }.getOrDefault(GatewaySection())
    }

    fun update(newSection: GatewaySection) = onEdit(newSection.toJsonElement())

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── HTTP sub-section ──────────────────────────────────────────────────
        Text(
            text = "HTTP",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        StringFieldRow(
            label = "Host",
            value = section.http.host,
            onValueChange = { host ->
                update(section.copy(http = GatewayHttpSection(host = host, port = section.http.port)))
            },
            mono = true,
            error = fieldErrors["gateway.http.host"],
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        IntFieldRow(
            label = "Port",
            value = section.http.port,
            onValueChange = { port ->
                update(section.copy(http = GatewayHttpSection(host = section.http.host, port = port)))
            },
            onError = { err -> onFieldError("gateway.http.port", err) },
            min = 1,
            max = 65535,
        )
        Spacer(modifier = Modifier.height(Spacing.md))

        // ── Auth sub-section ──────────────────────────────────────────────────
        Text(
            text = "Auth",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        EnumDropdownRow(
            label = "Mode",
            selected = section.auth.mode,
            options = AuthMode.entries,
            onSelected = { mode ->
                update(section.copy(auth = GatewayAuthSection(mode = mode, token = section.auth.token)))
            },
        )

        if (section.auth.mode == AuthMode.bearer) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            StringFieldRow(
                label = "Bearer Token",
                value = section.auth.token ?: "",
                onValueChange = { token ->
                    update(
                        section.copy(
                            auth = GatewayAuthSection(
                                mode = section.auth.mode,
                                token = token.ifEmpty { null },
                            ),
                        ),
                    )
                },
                mono = true,
                error = fieldErrors["gateway.auth.token"],
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))

        // ── mDNS sub-section ──────────────────────────────────────────────────
        Text(
            text = "mDNS",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        ToggleRow(
            label = "Enabled",
            checked = section.mdns.enabled,
            onCheckedChange = { enabled ->
                update(
                    section.copy(
                        mdns = GatewayMdnsSection(
                            enabled = enabled,
                            serviceName = section.mdns.serviceName,
                        ),
                    ),
                )
            },
        )
        Spacer(modifier = Modifier.height(Spacing.sm))

        StringFieldRow(
            label = "Service Name",
            value = section.mdns.serviceName,
            onValueChange = { name ->
                update(
                    section.copy(
                        mdns = GatewayMdnsSection(
                            enabled = section.mdns.enabled,
                            serviceName = name,
                        ),
                    ),
                )
            },
            mono = true,
            error = fieldErrors["gateway.mdns.serviceName"],
        )
    }
}
