package org.mcaccess.minecraftaccess.utils.position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlatAreaFinderTest {
    private static final int SIZE = 8;

    @Test
    void reportsAWholeOpenGridAsOneArea() {
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, grid(0, 0, SIZE, SIZE));

        List<FlatAreaFinder.Area> areas = FlatAreaFinder.find(floors, SIZE, SIZE, 3, 3, 4);

        assertEquals(1, areas.size());
        FlatAreaFinder.Area area = areas.getFirst();
        assertEquals(0, area.x());
        assertEquals(0, area.z());
        assertEquals(SIZE, area.width());
        assertEquals(SIZE, area.depth());
        assertEquals(64, area.y());
    }

    @Test
    void takesTheLargestRectangleOutOfAnLShape() {
        boolean[] open = grid(0, 0, SIZE, 4);
        mark(open, 0, 0, 4, SIZE);
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, open);

        List<FlatAreaFinder.Area> areas = FlatAreaFinder.find(floors, SIZE, SIZE, 2, 2, 1);

        assertEquals(1, areas.size());
        // The long arm of the L is the bigger prize; the narrow foot beside it only comes back on a later pass.
        assertEquals(SIZE * 4, areas.getFirst().width() * areas.getFirst().depth());
    }

    @Test
    void splitsTwoFieldsIntoTwoAreas() {
        boolean[] open = new boolean[SIZE * SIZE];
        mark(open, 0, 0, 6, 2);
        mark(open, 0, 5, 6, 2);
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, open);

        List<FlatAreaFinder.Area> areas = FlatAreaFinder.find(floors, SIZE, SIZE, 2, 2, 4);

        assertEquals(2, areas.size());
        for (FlatAreaFinder.Area area : areas) {
            assertEquals(6, Math.max(area.width(), area.depth()));
            assertEquals(2, Math.min(area.width(), area.depth()));
        }
    }

    @Test
    void acceptsEitherOrientation() {
        // A fresh grid per call: find() clears the cells it reports, so one grid cannot answer two searches.
        assertEquals(1, FlatAreaFinder.find(single(30, grid(0, 0, 6, 2)), SIZE, SIZE, 2, 6, 1).size());
        assertEquals(1, FlatAreaFinder.find(single(30, grid(0, 0, 6, 2)), SIZE, SIZE, 6, 2, 1).size());
    }

    @Test
    void keepsHeightsApart() {
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(30, grid(0, 0, 2, SIZE));
        floors.put(40, grid(2, 0, 2, SIZE));

        // Side by side these shelves would read as four wide; at any one height only two blocks are flat.
        List<FlatAreaFinder.Area> areas = FlatAreaFinder.find(floors, SIZE, SIZE, 2, 2, 4);

        assertEquals(2, areas.size());
        assertEquals(Set.of(30, 40), areas.stream().map(FlatAreaFinder.Area::y).collect(Collectors.toSet()));
        for (FlatAreaFinder.Area area : areas) {
            assertTrue(area.width() <= 2);
        }
    }

    @Test
    void findsNothingBelowTheAskedSize() {
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, grid(0, 0, 3, 3));

        assertTrue(FlatAreaFinder.find(floors, SIZE, SIZE, 4, 4, 4).isEmpty());
    }

    @Test
    void respectsTheResultLimit() {
        assertEquals(1, FlatAreaFinder.find(twoShelves(), SIZE, SIZE, 1, 1, 1).size());
        assertEquals(2, FlatAreaFinder.find(twoShelves(), SIZE, SIZE, 1, 1, 5).size());
    }

    @Test
    void ignoresNonsenseInput() {
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, grid(0, 0, SIZE, SIZE));

        assertTrue(FlatAreaFinder.find(floors, SIZE, SIZE, 0, 2, 4).isEmpty());
        assertTrue(FlatAreaFinder.find(floors, SIZE, SIZE, 2, 2, 0).isEmpty());
        assertTrue(FlatAreaFinder.find(Map.of(), SIZE, SIZE, 1, 1, 4).isEmpty());
    }

    @Test
    void neverReportsTheSameCellsTwice() {
        // Two equal fields on one height: the first is taken, then the second, never the same one again.
        boolean[] open = new boolean[SIZE * SIZE];
        mark(open, 0, 0, 2, 2);
        mark(open, 5, 5, 2, 2);
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, open);

        List<FlatAreaFinder.Area> areas = FlatAreaFinder.find(floors, SIZE, SIZE, 2, 2, 4);

        assertEquals(2, areas.size());
        assertFalse(areas.get(0).x() == areas.get(1).x() && areas.get(0).z() == areas.get(1).z());
        for (FlatAreaFinder.Area area : areas) {
            assertEquals(2, area.width());
            assertEquals(2, area.depth());
        }
    }

    private static Map<Integer, boolean[]> single(int y, boolean[] open) {
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(y, open);
        return floors;
    }

    private static Map<Integer, boolean[]> twoShelves() {
        Map<Integer, boolean[]> floors = new HashMap<>();
        floors.put(64, grid(0, 0, SIZE, 1));
        floors.put(70, grid(0, 4, SIZE, 1));
        return floors;
    }

    private static boolean[] grid(int x, int z, int width, int depth) {
        boolean[] open = new boolean[SIZE * SIZE];
        mark(open, x, z, width, depth);
        return open;
    }

    private static void mark(boolean[] open, int x, int z, int width, int depth) {
        for (int row = z; row < z + depth; row++) {
            for (int column = x; column < x + width; column++) {
                open[row * SIZE + column] = true;
            }
        }
    }
}
