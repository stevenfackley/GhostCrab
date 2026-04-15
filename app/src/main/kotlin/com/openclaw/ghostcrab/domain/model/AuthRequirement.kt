package com.openclaw.ghostcrab.domain.model

/**
 * Authentication mode required by a gateway.
 *
 * Determined by probing `/health` then `/status` without credentials.
 * Password-based auth is mapped to [Token] for v1.0; documented in ADR-002.
 */
sealed interface AuthRequirement {

    /** Gateway accepts requests without any authentication. Security risk — surface banner. */
    data object None : AuthRequirement

    /** Gateway requires a Bearer token in the `Authorization` header. */
    data object Token : AuthRequirement
}
