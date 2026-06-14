package com.example.continentregions.editor;

import java.util.UUID;

/**
 * A short-lived editor authorization (technical specification section 13):
 * a token bound to a player UUID with an absolute expiry timestamp.
 */
public record EditorSession(UUID playerUuid, String token, long expiresAt) {

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }
}
