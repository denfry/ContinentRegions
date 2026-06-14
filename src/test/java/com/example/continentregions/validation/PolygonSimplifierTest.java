package com.example.continentregions.validation;

import com.example.continentregions.model.ContinentPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonSimplifierTest {

    @Test
    void removesCollinearMidpointsOnAStraightEdge() {
        // A square whose edges are densely sampled with collinear points; RDP
        // should collapse each edge back to its two corners.
        final List<ContinentPoint> dense = new ArrayList<>();
        for (int x = 0; x <= 100; x += 10) dense.add(new ContinentPoint(x, 0));
        for (int z = 10; z <= 100; z += 10) dense.add(new ContinentPoint(100, z));
        for (int x = 90; x >= 0; x -= 10) dense.add(new ContinentPoint(x, 100));
        for (int z = 90; z >= 10; z -= 10) dense.add(new ContinentPoint(0, z));

        final List<ContinentPoint> simplified = PolygonSimplifier.simplify(dense, 1.0);
        assertTrue(simplified.size() <= 5, "expected ~4 corners, got " + simplified.size());
        assertTrue(simplified.size() >= 4);
    }

    @Test
    void keepsShapeWithinTolerance() {
        // A point that bulges out by 8 blocks must survive a tolerance of 5.
        final List<ContinentPoint> pts = List.of(
                new ContinentPoint(0, 0),
                new ContinentPoint(50, 8),
                new ContinentPoint(100, 0),
                new ContinentPoint(100, 100),
                new ContinentPoint(0, 100));
        final List<ContinentPoint> simplified = PolygonSimplifier.simplify(pts, 5.0);
        assertTrue(simplified.contains(new ContinentPoint(50, 8)),
                "the 8-block bulge exceeds tolerance 5 and must be kept");
    }

    @Test
    void dropsDeviationBelowTolerance() {
        // A 2-block bulge under tolerance 5 should be removed.
        final List<ContinentPoint> pts = List.of(
                new ContinentPoint(0, 0),
                new ContinentPoint(50, 2),
                new ContinentPoint(100, 0),
                new ContinentPoint(100, 100),
                new ContinentPoint(0, 100));
        final List<ContinentPoint> simplified = PolygonSimplifier.simplify(pts, 5.0);
        assertFalse(simplified.contains(new ContinentPoint(50, 2)));
    }

    @Test
    void neverCollapsesBelowTriangle() {
        final List<ContinentPoint> triangle = List.of(
                new ContinentPoint(0, 0),
                new ContinentPoint(100, 0),
                new ContinentPoint(50, 100));
        final List<ContinentPoint> simplified = PolygonSimplifier.simplify(triangle, 1000.0);
        assertEquals(3, simplified.size());
    }

    @Test
    void simplifiedSquareStaysSimpleAndKeepsArea() {
        final List<ContinentPoint> dense = new ArrayList<>();
        for (int x = 0; x <= 100; x += 5) dense.add(new ContinentPoint(x, 0));
        for (int z = 5; z <= 100; z += 5) dense.add(new ContinentPoint(100, z));
        for (int x = 95; x >= 0; x -= 5) dense.add(new ContinentPoint(x, 100));
        for (int z = 95; z >= 5; z -= 5) dense.add(new ContinentPoint(0, z));

        final List<ContinentPoint> simplified = PolygonSimplifier.simplify(dense, 1.0);
        assertFalse(PolygonValidator.isSelfIntersecting(simplified));
        assertEquals(10000.0, PolygonValidator.area(simplified), 1.0);
    }
}
