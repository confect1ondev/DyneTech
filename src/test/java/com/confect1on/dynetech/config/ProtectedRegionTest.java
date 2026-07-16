package com.confect1on.dynetech.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedRegionTest {

    @Nested
    class Parse {

        @Test
        void parsesWellFormedEntry() {
            ProtectedRegion r = ProtectedRegion.parse("minecraft:overworld -8 -64 -8 7 319 7");
            assertNotNull(r);
            assertEquals("minecraft:overworld", r.dimension());
            assertEquals(-8, r.minX());
            assertEquals(-64, r.minY());
            assertEquals(-8, r.minZ());
            assertEquals(7, r.maxX());
            assertEquals(319, r.maxY());
            assertEquals(7, r.maxZ());
        }

        @Test
        void normalizesReversedCorners() {
            // Corners given in "wrong" order should be normalized to (min, max).
            ProtectedRegion r = ProtectedRegion.parse("modid:custom_dim 10 100 20 -10 -50 -20");
            assertNotNull(r);
            assertEquals(-10, r.minX());
            assertEquals(-50, r.minY());
            assertEquals(-20, r.minZ());
            assertEquals(10, r.maxX());
            assertEquals(100, r.maxY());
            assertEquals(20, r.maxZ());
        }

        @Test
        void toleratesExtraWhitespace() {
            ProtectedRegion r = ProtectedRegion.parse("  minecraft:the_nether   0  0  0  1  1  1  ");
            assertNotNull(r);
            assertEquals("minecraft:the_nether", r.dimension());
        }

        @Test
        void acceptsPathsWithSlashesAndDots() {
            // Valid ResourceLocation path chars include /, ., _, -.
            assertNotNull(ProtectedRegion.parse("modid:some/nested_id.here-ok 0 0 0 1 1 1"));
        }

        @Test
        void rejectsNull() {
            assertNull(ProtectedRegion.parse(null));
        }

        @Test
        void rejectsWrongTokenCount() {
            assertNull(ProtectedRegion.parse(""));
            assertNull(ProtectedRegion.parse("minecraft:overworld"));
            assertNull(ProtectedRegion.parse("minecraft:overworld 0 0 0 1 1"));       // 6 tokens
            assertNull(ProtectedRegion.parse("minecraft:overworld 0 0 0 1 1 1 1"));   // 8 tokens
        }

        @Test
        void rejectsNonIntegerCoords() {
            assertNull(ProtectedRegion.parse("minecraft:overworld 0.5 0 0 1 1 1"));
            assertNull(ProtectedRegion.parse("minecraft:overworld abc 0 0 1 1 1"));
        }

        @Test
        void rejectsMalformedDimensionId() {
            assertNull(ProtectedRegion.parse("overworld 0 0 0 1 1 1"));           // no namespace
            assertNull(ProtectedRegion.parse("MINECRAFT:overworld 0 0 0 1 1 1")); // uppercase
            assertNull(ProtectedRegion.parse("mine craft:overworld 0 0 0 1 1 1"));// whitespace in id
        }
    }

    @Nested
    class Overlaps {

        private final ProtectedRegion spawn =
                new ProtectedRegion("minecraft:overworld", -8, -64, -8, 7, 319, 7);

        @Test
        void selectionInsideRegionOverlaps() {
            assertTrue(spawn.overlaps("minecraft:overworld", 0, 60, 0, 3, 65, 3));
        }

        @Test
        void selectionExactlyMatchingRegionOverlaps() {
            assertTrue(spawn.overlaps("minecraft:overworld", -8, -64, -8, 7, 319, 7));
        }

        @Test
        void selectionTouchingEdgeOverlaps() {
            // Bounds are inclusive on both ends, so shared-face still counts.
            assertTrue(spawn.overlaps("minecraft:overworld", 7, 60, 7, 20, 65, 20));
        }

        @Test
        void selectionOneBlockPastEdgeDoesNotOverlap() {
            assertFalse(spawn.overlaps("minecraft:overworld", 8, 60, 0, 20, 65, 3));
            assertFalse(spawn.overlaps("minecraft:overworld", -20, 60, -20, -9, 65, -9));
            assertFalse(spawn.overlaps("minecraft:overworld", 0, 320, 0, 3, 400, 3));
            assertFalse(spawn.overlaps("minecraft:overworld", 0, -128, 0, 3, -65, 3));
        }

        @Test
        void differentDimensionNeverOverlaps() {
            // Identical coords in a different dimension should be allowed.
            assertFalse(spawn.overlaps("minecraft:the_nether", 0, 60, 0, 3, 65, 3));
            assertFalse(spawn.overlaps("modid:custom", -8, -64, -8, 7, 319, 7));
        }

        @Test
        void partialOverlapCountsAsOverlap() {
            // Selection straddles the region boundary on X.
            assertTrue(spawn.overlaps("minecraft:overworld", -20, 60, 0, -5, 65, 3));
        }

        @Test
        void selectionEnclosingRegionOverlaps() {
            // Selection is bigger than region and contains it.
            assertTrue(spawn.overlaps("minecraft:overworld", -100, -100, -100, 100, 400, 100));
        }
    }

    @Nested
    class FindOverlapping {

        private final ProtectedRegion overworldSpawn =
                new ProtectedRegion("minecraft:overworld", -8, -64, -8, 7, 319, 7);
        private final ProtectedRegion netherHub =
                new ProtectedRegion("minecraft:the_nether", 0, 0, 0, 15, 127, 15);

        @Test
        void emptyListYieldsNull() {
            assertNull(ProtectedRegion.findOverlapping(List.of(),
                    "minecraft:overworld", 0, 0, 0, 1, 1, 1));
        }

        @Test
        void noOverlapYieldsNull() {
            assertNull(ProtectedRegion.findOverlapping(List.of(overworldSpawn, netherHub),
                    "minecraft:overworld", 100, 60, 100, 110, 65, 110));
        }

        @Test
        void returnsFirstMatchingRegion() {
            ProtectedRegion match = ProtectedRegion.findOverlapping(
                    List.of(overworldSpawn, netherHub),
                    "minecraft:overworld", 0, 60, 0, 3, 65, 3);
            assertSame(overworldSpawn, match);
        }

        @Test
        void matchesOnCorrectDimensionOnly() {
            // Overworld selection identical to nether region: no match.
            assertNull(ProtectedRegion.findOverlapping(List.of(netherHub),
                    "minecraft:overworld", 0, 0, 0, 15, 127, 15));
            // Same selection in the nether — matches.
            assertSame(netherHub, ProtectedRegion.findOverlapping(List.of(netherHub),
                    "minecraft:the_nether", 0, 0, 0, 15, 127, 15));
        }

        @Test
        void shortCircuitsAtFirstHit() {
            // If two overlapping regions exist, the FIRST one in list order is returned.
            // callers relying on that order (e.g. for user-facing region names) can trust it.
            ProtectedRegion earlier = new ProtectedRegion("minecraft:overworld", -100, 0, -100, 100, 100, 100);
            ProtectedRegion later = new ProtectedRegion("minecraft:overworld", -50, 0, -50, 50, 50, 50);
            ProtectedRegion match = ProtectedRegion.findOverlapping(
                    List.of(earlier, later),
                    "minecraft:overworld", 0, 10, 0, 5, 15, 5);
            assertSame(earlier, match);
        }
    }

    @Test
    void configDefaultParsesRoundTrip() {
        // Documents the DTConfig default so drift in either place shows up here.
        String defaultEntry = "minecraft:overworld -8 -64 -8 7 319 7";
        ProtectedRegion r = ProtectedRegion.parse(defaultEntry);
        assertNotNull(r);
        assertEquals("minecraft:overworld", r.dimension());
        assertEquals(16, r.maxX() - r.minX() + 1);
        assertEquals(16, r.maxZ() - r.minZ() + 1);
        assertEquals(-64, r.minY());
        assertEquals(319, r.maxY());
    }
}
