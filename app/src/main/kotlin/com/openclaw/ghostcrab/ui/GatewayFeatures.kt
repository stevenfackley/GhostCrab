package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.GatewayConnection

/**
 * Which gateway screens this session may open. The connection manager lists a capability only when the gateway
 * offers its methods **and** `hello-ok` granted the scope they need (ADR-0007: `config` needs `operator.admin`,
 * `models` and `skills` need `operator.read`), so a token-only socket (`scopes: []`) offers none of them and the UI
 * hides their entries instead of failing with a missing scope.
 *
 * @param config The config editor (`config.get` + `config.patch`).
 * @param models The model manager (`models.list`).
 * @param skills The installed-skills screen (`skills.status`); release builds also need
 *   `BuildConfig.SKILLS_INSTALL_ENABLED`.
 */
data class GatewayFeatures(
    val config: Boolean,
    val models: Boolean,
    val skills: Boolean,
) {
    companion object {
        /** No session: nothing is offered. */
        val NONE = GatewayFeatures(config = false, models = false, skills = false)

        /**
         * @param connection A live session.
         * @return The features its capabilities allow.
         */
        fun of(connection: GatewayConnection.Connected): GatewayFeatures = GatewayFeatures(
            config = "config" in connection.capabilities,
            models = "models" in connection.capabilities,
            skills = "skills" in connection.capabilities,
        )

        /**
         * @param connection Any connection state.
         * @return [of] for a [GatewayConnection.Connected] (a silent reconnect keeps its scopes), else [NONE].
         */
        fun of(connection: GatewayConnection): GatewayFeatures =
            if (connection is GatewayConnection.Connected) of(connection) else NONE
    }
}
