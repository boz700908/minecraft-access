package org.mcaccess.minecraftaccess.utils.position;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterExitFinderTest {
    private static final int RADIUS = 16;

    @Test
    void takesTheAdjacentShore() {
        Grid grid = new Grid()
                .swim(0, 0, 0)
                .exit(1, 0, 0);

        List<WaterExitFinder.Step> route = WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS);

        assertEquals(List.of(Grid.step(1, 0, 0)), route);
    }

    @Test
    void prefersTheShorterSwimOverTheNearerShore() {
        // A wall of solid blocks separates the start from a shore two blocks away as the crow flies; the only way
        // there swims six. A shore three blocks straight out is the honest answer, so it is the one that comes back.
        Grid grid = new Grid()
                .swim(0, 0, 0)
                .swim(0, 0, 1).swim(0, 0, 2).swim(1, 0, 2).swim(2, 0, 2).swim(2, 0, 1)
                .swim(2, 0, 0).exit(2, 0, 0)
                .swim(0, 0, -1).swim(0, 0, -2)
                .exit(0, 0, -3);

        List<WaterExitFinder.Step> route = WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS);

        assertEquals(List.of(Grid.step(0, 0, -1), Grid.step(0, 0, -2), Grid.step(0, 0, -3)), route);
    }

    @Test
    void neverSwimsThroughSolidGround() {
        // Solid cells are simply not in the flooded region, so the shore past the wall is never reached even though
        // it sits well inside the radius.
        Grid grid = new Grid()
                .swim(0, 0, 0)
                .swim(1, 0, 0)
                .exit(3, 0, 0);

        assertTrue(WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS).isEmpty());
    }

    @Test
    void needsAWayToReachTheShore() {
        // A cell can be perfectly standable and still be no way out, because getting there means swimming through
        // whatever separates it from the player.
        Grid grid = new Grid()
                .swim(0, 0, 0)
                .stand(1, 0, 0);

        assertTrue(WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS).isEmpty());
    }

    @Test
    void countsSwimmingUpAndDownOpenWater() {
        Grid grid = new Grid()
                .swim(0, 0, 0)
                .swim(0, 1, 0)
                .swim(0, 2, 0)
                .exit(0, 3, 0);

        List<WaterExitFinder.Step> route = WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS);

        assertEquals(Grid.step(0, 3, 0), route.getLast());
        assertEquals(3, route.size());
    }

    @Test
    void staysInsideTheRadius() {
        Grid grid = new Grid().swim(0, 0, 0);
        for (int x = 1; x < 5; x++) {
            grid.swim(x, 0, 0);
        }
        grid.exit(5, 0, 0);

        assertTrue(WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), 4).isEmpty());
        assertEquals(Grid.step(5, 0, 0),
                WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), 5).getLast());
    }

    @Test
    void neverAnswersWithWhereThePlayerAlreadyIs() {
        Grid grid = new Grid().stand(0, 0, 0);

        assertTrue(WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS).isEmpty());
    }

    @Test
    void everyStepIsOneBlockAlongOneAxis() {
        Grid grid = new Grid()
                .swim(0, 0, 0)
                .swim(0, 0, 1).swim(0, 0, 2)
                .swim(1, 0, 2)
                .swim(1, 1, 2)
                .exit(1, 2, 2);

        List<WaterExitFinder.Step> route = WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), RADIUS);

        WaterExitFinder.Step previous = Grid.step(0, 0, 0);
        for (WaterExitFinder.Step step : route) {
            int moved = Math.abs(step.x() - previous.x()) + Math.abs(step.y() - previous.y())
                    + Math.abs(step.z() - previous.z());
            assertEquals(1, moved, "step " + step + " is not one block from " + previous);
            previous = step;
        }
    }

    @Test
    void ignoresNonsenseInput() {
        Grid grid = new Grid().swim(0, 0, 0).exit(1, 0, 0);

        assertTrue(WaterExitFinder.findRoute(grid, Grid.step(0, 0, 0), 0).isEmpty());
        assertTrue(WaterExitFinder.findRoute(null, Grid.step(0, 0, 0), RADIUS).isEmpty());
        assertTrue(WaterExitFinder.findRoute(grid, null, RADIUS).isEmpty());
    }

    /** A world described as two lists: the cells a body may swim through, and the cells it may climb out onto. */
    private static final class Grid implements WaterExitFinder.Cells {
        private final Set<WaterExitFinder.Step> swim = new HashSet<>();
        private final Set<WaterExitFinder.Step> stand = new HashSet<>();

        static WaterExitFinder.Step step(int x, int y, int z) {
            return new WaterExitFinder.Step(x, y, z);
        }

        Grid swim(int x, int y, int z) {
            swim.add(step(x, y, z));
            return this;
        }

        Grid stand(int x, int y, int z) {
            stand.add(step(x, y, z));
            return this;
        }

        /** A shore: open enough to swim into, and dry ground underneath - both halves of what the search looks for. */
        Grid exit(int x, int y, int z) {
            return swim(x, y, z).stand(x, y, z);
        }

        @Override
        public boolean swimmable(int x, int y, int z) {
            return swim.contains(step(x, y, z));
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return stand.contains(step(x, y, z));
        }
    }
}
