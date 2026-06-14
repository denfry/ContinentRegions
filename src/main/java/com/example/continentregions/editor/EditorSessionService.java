package com.example.continentregions.editor;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Issues and validates one-time editor tokens. Thread-safe: tokens are created
 * from the command thread and validated from HTTP threads.
 */
public final class EditorSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ConcurrentHashMap<String, EditorSession> sessions = new ConcurrentHashMap<>();
    private final long tokenLifetimeMillis;

    public EditorSessionService(int tokenExpireMinutes) {
        this.tokenLifetimeMillis = TimeUnit.MINUTES.toMillis(tokenExpireMinutes);
    }

    /** Creates a new session for the player and returns it. */
    public EditorSession create(UUID playerUuid, long now) {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final String token = ENCODER.encodeToString(bytes);
        final EditorSession session = new EditorSession(playerUuid, token, now + tokenLifetimeMillis);
        sessions.put(token, session);
        return session;
    }

    /** @return the valid session for this token, or empty if missing/expired. */
    public Optional<EditorSession> validate(String token, long now) {
        if (token == null) {
            return Optional.empty();
        }
        final EditorSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(now)) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    /** Drops all expired tokens; returns the number removed. */
    public int purgeExpired(long now) {
        final int before = sessions.size();
        sessions.values().removeIf(s -> s.isExpired(now));
        return before - sessions.size();
    }

    public int activeCount() {
        return sessions.size();
    }
}
