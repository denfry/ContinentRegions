package com.example.continentregions.validation;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonValidatorTest {

    private final PolygonValidator validator = new PolygonValidator(1000, true, 0.0, 0.0);

    private static List<ContinentPoint> square() {
        return List.of(
                new ContinentPoint(0, 0),
                new ContinentPoint(10, 0),
                new ContinentPoint(10, 10),
                new ContinentPoint(0, 10));
    }

    private static Continent continent(List<ContinentPoint> points, int minY, int maxY) {
        final Continent c = new Continent();
        c.setId("europe");
        c.setMinY(minY);
        c.setMaxY(maxY);
        c.setPoints(points);
        return c;
    }

    @Test
    void squareIsSimpleAndAreaIsCorrect() {
        assertFalse(PolygonValidator.isSelfIntersecting(square()));
        assertEquals(100.0, PolygonValidator.area(square()), 1e-9);
    }

    @Test
    void bowtieIsSelfIntersecting() {
        final List<ContinentPoint> bowtie = List.of(
                new ContinentPoint(0, 0),
                new ContinentPoint(10, 10),
                new ContinentPoint(10, 0),
                new ContinentPoint(0, 10));
        assertTrue(PolygonValidator.isSelfIntersecting(bowtie));
    }

    @Test
    void regionIdPattern() {
        assertTrue(validator.isValidRegionId("europe"));
        assertTrue(validator.isValidRegionId("continent_europe-1"));
        assertFalse(validator.isValidRegionId("EU"));
        assertFalse(validator.isValidRegionId("ab"));
        assertFalse(validator.isValidRegionId("has space"));
        assertFalse(validator.isValidRegionId(null));
    }

    @Test
    void tooFewPointsFails() {
        final ValidationResult r = validator.validate(
                continent(List.of(new ContinentPoint(0, 0), new ContinentPoint(1, 1)), -64, 320), null);
        assertFalse(r.isValid());
    }

    @Test
    void validContinentPasses() {
        final ValidationResult r = validator.validate(continent(square(), -64, 320), null);
        assertTrue(r.isValid(), r.errorMessage());
    }

    @Test
    void minYMustBeLessThanMaxY() {
        final ValidationResult r = validator.validate(continent(square(), 320, -64), null);
        assertFalse(r.isValid());
    }

    @Test
    void pointOutsideBorderFails() {
        // Square spans to (10,10) but the border only reaches +-5.
        final WorldBounds bounds = new WorldBounds(-5, -5, 5, 5);
        final ValidationResult r = validator.validate(continent(square(), -64, 320), bounds);
        assertFalse(r.isValid());
    }
}
