package com.example.continentregions.validation;

import com.example.continentregions.model.ContinentPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonGeometryTest {

    private static List<ContinentPoint> square(int x0, int z0, int size) {
        return List.of(
                new ContinentPoint(x0, z0),
                new ContinentPoint(x0 + size, z0),
                new ContinentPoint(x0 + size, z0 + size),
                new ContinentPoint(x0, z0 + size));
    }

    @Test
    void disjointSquaresDoNotOverlap() {
        assertFalse(PolygonGeometry.overlaps(square(0, 0, 10), square(100, 100, 10)));
    }

    @Test
    void edgeAdjacentSquaresDoNotOverlap() {
        // Share the x=10 edge exactly — tiling neighbours, not an overlap.
        assertFalse(PolygonGeometry.overlaps(square(0, 0, 10), square(10, 0, 10)));
    }

    @Test
    void partiallyOverlappingSquaresOverlap() {
        assertTrue(PolygonGeometry.overlaps(square(0, 0, 10), square(5, 5, 10)));
    }

    @Test
    void containedSquareOverlaps() {
        assertTrue(PolygonGeometry.overlaps(square(0, 0, 100), square(40, 40, 10)));
    }

    @Test
    void identicalSquaresOverlap() {
        assertTrue(PolygonGeometry.overlaps(square(0, 0, 50), square(0, 0, 50)));
    }

    @Test
    void sharedCornerOnlyDoesNotOverlap() {
        // Touch at the single point (10,10).
        assertFalse(PolygonGeometry.overlaps(square(0, 0, 10), square(10, 10, 10)));
    }
}
