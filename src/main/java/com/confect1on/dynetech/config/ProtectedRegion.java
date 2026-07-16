package com.confect1on.dynetech.config;

import java.util.List;

/**
 * An axis-aligned region in a specific dimension that the Structure Shrinker won't touch.
 * Pure Java (no Minecraft deps) so parse/overlap is unit-testable without a mod runtime.
 *
 * Config format: {@code "<dimension> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>"}, bounds
 * inclusive. Corners can be in any order; the constructor normalizes them.
 */
public record ProtectedRegion(
        String dimension,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ) {

    // Same char set Minecraft's ResourceLocation allows. Plain regex here so the class
    // stays free of Minecraft imports and unit tests don't need a mod runtime.
    private static final java.util.regex.Pattern DIMENSION_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9/._\\-]+");

    public ProtectedRegion {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            int tx = Math.min(minX, maxX); maxX = Math.max(minX, maxX); minX = tx;
            int ty = Math.min(minY, maxY); maxY = Math.max(minY, maxY); minY = ty;
            int tz = Math.min(minZ, maxZ); maxZ = Math.max(minZ, maxZ); minZ = tz;
        }
    }

    /** Parses a config entry. Returns null on any malformed input (never throws). */
    public static ProtectedRegion parse(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != 7) return null;
        if (!DIMENSION_ID.matcher(parts[0]).matches()) return null;
        try {
            int x1 = Integer.parseInt(parts[1]);
            int y1 = Integer.parseInt(parts[2]);
            int z1 = Integer.parseInt(parts[3]);
            int x2 = Integer.parseInt(parts[4]);
            int y2 = Integer.parseInt(parts[5]);
            int z2 = Integer.parseInt(parts[6]);
            return new ProtectedRegion(parts[0], x1, y1, z1, x2, y2, z2);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True if same dimension AND the AABBs overlap (bounds inclusive on both ends). */
    public boolean overlaps(String queryDim,
                            int qMinX, int qMinY, int qMinZ,
                            int qMaxX, int qMaxY, int qMaxZ) {
        if (!dimension.equals(queryDim)) return false;
        return qMinX <= maxX && qMaxX >= minX
            && qMinY <= maxY && qMaxY >= minY
            && qMinZ <= maxZ && qMaxZ >= minZ;
    }

    /** First region in {@code regions} that overlaps the query AABB, or null. */
    public static ProtectedRegion findOverlapping(List<ProtectedRegion> regions,
                                                  String queryDim,
                                                  int qMinX, int qMinY, int qMinZ,
                                                  int qMaxX, int qMaxY, int qMaxZ) {
        for (ProtectedRegion r : regions) {
            if (r.overlaps(queryDim, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)) return r;
        }
        return null;
    }
}
