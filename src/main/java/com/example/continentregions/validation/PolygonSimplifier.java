package com.example.continentregions.validation;

import com.example.continentregions.model.ContinentPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Ramer–Douglas–Peucker polygon simplification (v2). Reduces the number of
 * outline points while keeping the overall shape within {@code tolerance} blocks
 * of the original. Pure logic — no Bukkit dependency, fully unit-testable.
 *
 * <p>The outline is a closed ring, so RDP is run on the open chain of points and
 * the ring is kept closed implicitly (the last point connects back to the first).
 */
public final class PolygonSimplifier {

    private PolygonSimplifier() {
    }

    /**
     * Simplifies a closed polygon ring.
     *
     * @param points    the ring (no duplicated closing point)
     * @param tolerance maximum allowed deviation in blocks ({@code <= 0} returns a copy)
     * @return a simplified ring with at least 3 points (or the original when it
     * already has 3 or fewer)
     */
    public static List<ContinentPoint> simplify(List<ContinentPoint> points, double tolerance) {
        if (points == null || points.size() <= 3 || tolerance <= 0) {
            return points == null ? new ArrayList<>() : new ArrayList<>(points);
        }

        // Anchor on the two extreme points so the closed ring is split into two
        // chains; simplifying each chain and stitching them preserves the loop.
        final int n = points.size();
        int aIdx = 0;
        int bIdx = 0;
        long maxDistSq = -1;
        for (int i = 1; i < n; i++) {
            final long dx = (long) points.get(i).x() - points.get(0).x();
            final long dz = (long) points.get(i).z() - points.get(0).z();
            final long d = dx * dx + dz * dz;
            if (d > maxDistSq) {
                maxDistSq = d;
                bIdx = i;
            }
        }

        final List<ContinentPoint> first = new ArrayList<>();
        for (int i = aIdx; i <= bIdx; i++) {
            first.add(points.get(i));
        }
        final List<ContinentPoint> second = new ArrayList<>();
        for (int i = bIdx; i < n; i++) {
            second.add(points.get(i));
        }
        second.add(points.get(aIdx));

        final List<ContinentPoint> s1 = simplifyChain(first, tolerance);
        final List<ContinentPoint> s2 = simplifyChain(second, tolerance);

        final List<ContinentPoint> result = new ArrayList<>(s1);
        // s2 starts at bIdx (already the tail of s1) and ends at aIdx (the head of
        // s1); drop both shared endpoints to avoid duplicates.
        for (int i = 1; i < s2.size() - 1; i++) {
            result.add(s2.get(i));
        }

        // Never collapse below a triangle.
        return result.size() >= 3 ? result : new ArrayList<>(points);
    }

    /** RDP on an open chain (endpoints are always kept). */
    private static List<ContinentPoint> simplifyChain(List<ContinentPoint> chain, double tolerance) {
        final int n = chain.size();
        if (n < 3) {
            return new ArrayList<>(chain);
        }
        final boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        rdp(chain, 0, n - 1, tolerance, keep);

        final List<ContinentPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(chain.get(i));
            }
        }
        return out;
    }

    private static void rdp(List<ContinentPoint> pts, int first, int last, double tol, boolean[] keep) {
        if (last <= first + 1) {
            return;
        }
        double maxDist = -1;
        int index = -1;
        final ContinentPoint a = pts.get(first);
        final ContinentPoint b = pts.get(last);
        for (int i = first + 1; i < last; i++) {
            final double d = perpendicularDistance(pts.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        if (maxDist > tol && index != -1) {
            keep[index] = true;
            rdp(pts, first, index, tol, keep);
            rdp(pts, index, last, tol, keep);
        }
    }

    /** Distance from point {@code p} to the line segment {@code a-b}. */
    static double perpendicularDistance(ContinentPoint p, ContinentPoint a, ContinentPoint b) {
        final double dx = b.x() - a.x();
        final double dz = b.z() - a.z();
        final double lenSq = dx * dx + dz * dz;
        if (lenSq == 0.0) {
            final double ex = p.x() - a.x();
            final double ez = p.z() - a.z();
            return Math.sqrt(ex * ex + ez * ez);
        }
        // Projection factor of p onto the (infinite) line, clamped to the segment.
        double t = ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        final double projX = a.x() + t * dx;
        final double projZ = a.z() + t * dz;
        final double ex = p.x() - projX;
        final double ez = p.z() - projZ;
        return Math.sqrt(ex * ex + ez * ez);
    }
}
