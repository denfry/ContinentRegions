package com.example.continentregions.validation;

import com.example.continentregions.model.ContinentPoint;

import java.util.List;

/**
 * Geometry helpers for detecting whether two continent outlines overlap (v2).
 *
 * <p>"Overlap" here means the polygon interiors share area — neighbours that
 * merely touch along a shared edge or vertex are <em>not</em> considered
 * overlapping, so continents can be tiled edge-to-edge. Pure logic, unit-testable.
 */
public final class PolygonGeometry {

    private PolygonGeometry() {
    }

    /**
     * @return {@code true} when the interiors of the two simple polygons overlap.
     */
    public static boolean overlaps(List<ContinentPoint> a, List<ContinentPoint> b) {
        if (a == null || b == null || a.size() < 3 || b.size() < 3) {
            return false;
        }
        // 1) Any pair of edges that properly cross (interior intersection).
        final int na = a.size();
        final int nb = b.size();
        for (int i = 0; i < na; i++) {
            final ContinentPoint a1 = a.get(i);
            final ContinentPoint a2 = a.get((i + 1) % na);
            for (int j = 0; j < nb; j++) {
                final ContinentPoint b1 = b.get(j);
                final ContinentPoint b2 = b.get((j + 1) % nb);
                if (segmentsProperlyIntersect(a1, a2, b1, b2)) {
                    return true;
                }
            }
        }
        // 2) Containment without crossing edges: a vertex of one strictly inside
        //    the other, or one centroid strictly inside the other (catches the
        //    identical / fully-nested case where all vertices lie on a boundary).
        for (ContinentPoint p : a) {
            if (strictlyInside(p.x(), p.z(), b)) {
                return true;
            }
        }
        for (ContinentPoint p : b) {
            if (strictlyInside(p.x(), p.z(), a)) {
                return true;
            }
        }
        final double[] ca = centroid(a);
        final double[] cb = centroid(b);
        return strictlyInside(ca[0], ca[1], b) || strictlyInside(cb[0], cb[1], a);
    }

    /**
     * Point-in-polygon test for region membership: {@code true} when (x, z) is
     * inside the polygon or exactly on its boundary (edge-inclusive).
     */
    public static boolean containsPoint(List<ContinentPoint> poly, double x, double z) {
        if (poly == null || poly.size() < 3) {
            return false;
        }
        final int n = poly.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = poly.get(i).x();
            final double zi = poly.get(i).z();
            final double xj = poly.get(j).x();
            final double zj = poly.get(j).z();
            if (onSegment(x, z, xi, zi, xj, zj)) {
                return true; // on the boundary counts as inside
            }
            final boolean crosses = (zi > z) != (zj > z)
                    && x < (xj - xi) * (z - zi) / (zj - zi) + xi;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Standard even-odd ray cast; points exactly on an edge return {@code false}. */
    static boolean strictlyInside(double x, double z, List<ContinentPoint> poly) {
        final int n = poly.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = poly.get(i).x();
            final double zi = poly.get(i).z();
            final double xj = poly.get(j).x();
            final double zj = poly.get(j).z();
            if (onSegment(x, z, xi, zi, xj, zj)) {
                return false; // boundary is not "strictly inside"
            }
            final boolean crosses = (zi > z) != (zj > z)
                    && x < (xj - xi) * (z - zi) / (zj - zi) + xi;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onSegment(double px, double pz, double x1, double z1, double x2, double z2) {
        final double cross = (px - x1) * (z2 - z1) - (pz - z1) * (x2 - x1);
        if (Math.abs(cross) > 1e-9) {
            return false;
        }
        return Math.min(x1, x2) - 1e-9 <= px && px <= Math.max(x1, x2) + 1e-9
                && Math.min(z1, z2) - 1e-9 <= pz && pz <= Math.max(z1, z2) + 1e-9;
    }

    /**
     * Proper intersection: the two segments cross at a point interior to both.
     * Shared endpoints and collinear overlaps return {@code false} so that
     * edge-adjacent polygons are not flagged.
     */
    static boolean segmentsProperlyIntersect(ContinentPoint p1, ContinentPoint p2,
                                             ContinentPoint p3, ContinentPoint p4) {
        final long d1 = cross(p3, p4, p1);
        final long d2 = cross(p3, p4, p2);
        final long d3 = cross(p1, p2, p3);
        final long d4 = cross(p1, p2, p4);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static long cross(ContinentPoint a, ContinentPoint b, ContinentPoint c) {
        return (long) (b.x() - a.x()) * (c.z() - a.z()) - (long) (b.z() - a.z()) * (c.x() - a.x());
    }

    static double[] centroid(List<ContinentPoint> poly) {
        // Area-weighted polygon centroid (falls back to vertex average for
        // degenerate zero-area input).
        final int n = poly.size();
        double area2 = 0;
        double cx = 0;
        double cz = 0;
        for (int i = 0; i < n; i++) {
            final ContinentPoint a = poly.get(i);
            final ContinentPoint b = poly.get((i + 1) % n);
            final double cross = (double) a.x() * b.z() - (double) b.x() * a.z();
            area2 += cross;
            cx += (a.x() + b.x()) * cross;
            cz += (a.z() + b.z()) * cross;
        }
        if (Math.abs(area2) < 1e-9) {
            double sx = 0;
            double sz = 0;
            for (ContinentPoint p : poly) {
                sx += p.x();
                sz += p.z();
            }
            return new double[]{sx / n, sz / n};
        }
        return new double[]{cx / (3 * area2), cz / (3 * area2)};
    }
}
