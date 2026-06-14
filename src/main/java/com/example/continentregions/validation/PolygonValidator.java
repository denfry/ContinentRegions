package com.example.continentregions.validation;

import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates continents before they are persisted and applied (technical
 * specification section 14). Pure logic: geometry checks take primitive
 * coordinates and an optional {@link WorldBounds}, so the class is fully
 * unit-testable without a running server.
 */
public final class PolygonValidator {

    /** Region id pattern from the spec. */
    public static final Pattern REGION_ID = Pattern.compile("^[a-z0-9_\\-]{3,64}$");

    /** A polygon needs at least a triangle. */
    public static final int MIN_POINTS = 3;

    private final int maxPoints;
    private final boolean validateSelfIntersection;
    private final double minArea;
    private final double warnArea;

    /**
     * @param maxPoints                maximum allowed outline points
     * @param validateSelfIntersection enable the self-intersection check
     * @param minArea                  minimum polygon area (0 disables the check)
     * @param warnArea                 area above which a warning is emitted (0 disables)
     */
    public PolygonValidator(int maxPoints, boolean validateSelfIntersection, double minArea, double warnArea) {
        this.maxPoints = maxPoints;
        this.validateSelfIntersection = validateSelfIntersection;
        this.minArea = minArea;
        this.warnArea = warnArea;
    }

    /**
     * Validates a full continent.
     *
     * @param continent the continent to validate
     * @param bounds    world border bounds, or {@code null} to skip the border check
     */
    public ValidationResult validate(Continent continent, WorldBounds bounds) {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        if (!isValidRegionId(continent.getId())) {
            errors.add("Invalid continent id: must match " + REGION_ID.pattern());
        }
        if (continent.getMinY() >= continent.getMaxY()) {
            errors.add("minY (" + continent.getMinY() + ") must be less than maxY (" + continent.getMaxY() + ")");
        }

        validatePoints(continent.getPoints(), bounds, errors, warnings);

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    public boolean isValidRegionId(String id) {
        return id != null && REGION_ID.matcher(id).matches();
    }

    private void validatePoints(List<ContinentPoint> points, WorldBounds bounds,
                                List<String> errors, List<String> warnings) {
        final int n = points.size();
        if (n < MIN_POINTS) {
            errors.add("A continent needs at least " + MIN_POINTS + " points (got " + n + ")");
            return;
        }
        if (n > maxPoints) {
            errors.add("Too many points: " + n + " > max " + maxPoints);
        }

        if (bounds != null) {
            for (ContinentPoint p : points) {
                if (!bounds.contains(p.x(), p.z())) {
                    errors.add("Point (" + p.x() + ", " + p.z() + ") is outside the world border");
                    break;
                }
            }
        }

        final double area = area(points);
        if (minArea > 0 && area < minArea) {
            errors.add("Polygon area " + Math.round(area) + " is smaller than the minimum " + Math.round(minArea));
        }
        if (warnArea > 0 && area > warnArea) {
            warnings.add("Polygon area " + Math.round(area) + " is very large (> " + Math.round(warnArea) + ")");
        }

        if (validateSelfIntersection && isSelfIntersecting(points)) {
            errors.add("Polygon edges intersect themselves");
        }
    }

    /** Absolute polygon area via the shoelace formula. */
    public static double area(List<ContinentPoint> points) {
        return Math.abs(signedArea(points));
    }

    static double signedArea(List<ContinentPoint> points) {
        final int n = points.size();
        long sum = 0;
        for (int i = 0; i < n; i++) {
            final ContinentPoint a = points.get(i);
            final ContinentPoint b = points.get((i + 1) % n);
            sum += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return sum / 2.0;
    }

    /**
     * Returns true when any two non-adjacent edges of the closed polygon
     * intersect (touching counts as intersecting).
     */
    public static boolean isSelfIntersecting(List<ContinentPoint> points) {
        final int n = points.size();
        if (n < 4) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            final ContinentPoint a1 = points.get(i);
            final ContinentPoint a2 = points.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                // Skip edges that share a vertex: consecutive edges, and the
                // wrap-around pair (edge 0 and edge n-1 share point 0).
                if (j == i + 1 || (i == 0 && j == n - 1)) {
                    continue;
                }
                final ContinentPoint b1 = points.get(j);
                final ContinentPoint b2 = points.get((j + 1) % n);
                if (segmentsIntersect(a1, a2, b1, b2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(ContinentPoint p1, ContinentPoint p2,
                                             ContinentPoint p3, ContinentPoint p4) {
        final long d1 = cross(p3, p4, p1);
        final long d2 = cross(p3, p4, p2);
        final long d3 = cross(p1, p2, p3);
        final long d4 = cross(p1, p2, p4);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        if (d1 == 0 && onSegment(p3, p4, p1)) return true;
        if (d2 == 0 && onSegment(p3, p4, p2)) return true;
        if (d3 == 0 && onSegment(p1, p2, p3)) return true;
        return d4 == 0 && onSegment(p1, p2, p4);
    }

    /** Cross product (b-a) x (c-a); sign gives orientation. */
    private static long cross(ContinentPoint a, ContinentPoint b, ContinentPoint c) {
        return (long) (b.x() - a.x()) * (c.z() - a.z()) - (long) (b.z() - a.z()) * (c.x() - a.x());
    }

    /** Given c is collinear with a-b, is it within the segment bounds? */
    private static boolean onSegment(ContinentPoint a, ContinentPoint b, ContinentPoint c) {
        return Math.min(a.x(), b.x()) <= c.x() && c.x() <= Math.max(a.x(), b.x())
                && Math.min(a.z(), b.z()) <= c.z() && c.z() <= Math.max(a.z(), b.z());
    }
}
