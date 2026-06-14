package com.example.continentregions.validation;

/**
 * Axis-aligned X/Z bounds used for the world-border check. Kept as a plain
 * record so {@link PolygonValidator} stays free of Bukkit types and unit-testable.
 */
public record WorldBounds(double minX, double minZ, double maxX, double maxZ) {

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
