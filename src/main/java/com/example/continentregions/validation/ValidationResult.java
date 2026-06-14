package com.example.continentregions.validation;

import java.util.List;

/**
 * Outcome of validating a continent: a list of blocking errors and a list of
 * non-blocking warnings. The continent is acceptable when {@link #isValid()}.
 */
public record ValidationResult(List<String> errors, List<String> warnings) {

    public boolean isValid() {
        return errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /** All error messages joined into a single user-facing line. */
    public String errorMessage() {
        return String.join("; ", errors);
    }

    public static ValidationResult ok() {
        return new ValidationResult(List.of(), List.of());
    }
}
