package com.example.continentregions.validation;

import java.util.Locale;

/**
 * How the plugin reacts when a continent overlaps another in the same world (v2).
 */
public enum OverlapPolicy {

    /** Reject the save/apply with an error. */
    ERROR,
    /** Allow it but attach a warning. */
    WARN,
    /** Skip the overlap check entirely. */
    OFF;

    public static OverlapPolicy fromConfig(String raw) {
        if (raw == null) {
            return WARN;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "error", "reject", "deny" -> ERROR;
            case "off", "none", "false", "disabled" -> OFF;
            default -> WARN;
        };
    }
}
