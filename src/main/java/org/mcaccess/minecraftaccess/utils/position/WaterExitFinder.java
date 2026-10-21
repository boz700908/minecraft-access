package org.mcaccess.minecraftaccess.utils.position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the shortest swim from somewhere in the water to somewhere a body can climb out, which is how "how do I get
 * out of here" gets an answer: the caller says what counts as swimmable and what counts as standable, and this walks
 * whatever world it is handed.
 *
 * <p>The search floods outward from the player through cells connected to where they are, so whichever exit it reaches
 * first is reachable by swimming straight along the route it returns - unlike a plain "nearest dry land" lookup, which
 * could just as easily point at a ledge behind a wall. Breadth-first order, not straight-line distance, decides which
 * exit wins: the closest exit in blocks can still be the longer swim around an obstacle.
 *
 * <p>Neighbours are face-connected only, because a body 0.6 blocks wide cannot pass the zero-width gap two diagonally
 * touching solid blocks leave - a diagonal step was never actually swimmable. That also keeps the worst case (open
 * water, where the reachable region really is the whole radius cube) to a brief hitch rather than a freeze, and leaves
 * the route axis-aligned, so swimming a straight line between consecutive waypoints cannot clip a corner.
 */
public final class WaterExitFinder {
    /** The six face neighbours, so every step of a route is one block along one axis. */
    private static final int[][] NEIGHBOURS = {
            {0, 1, 0},
            {0, -1, 0},
            {1, 0, 0},
            {-1, 0, 0},
            {0, 0, 1},
            {0, 0, -1},
    };

    private WaterExitFinder() {
    }

    /**
     * The waypoint chain from {@code start} to the first exit the flood reaches, that exit last, or an empty list when
     * nothing reachable within {@code radius} blocks on any axis qualifies. {@code start} itself is never an exit.
     */
    public static List<Step> findRoute(Cells cells, Step start, int radius) {
        if (cells == null || start == null || radius < 1) {
            return List.of();
        }

        Map<Step, Step> cameFrom = new HashMap<>();
        Deque<Step> queue = new ArrayDeque<>();
        queue.add(start);
        cameFrom.put(start, start);

        Step exit = null;
        while (!queue.isEmpty()) {
            Step current = queue.poll();
            if (!current.equals(start) && cells.standable(current.x(), current.y(), current.z())) {
                exit = current;
                break;
            }
            for (int[] offset : NEIGHBOURS) {
                Step neighbour = new Step(current.x() + offset[0], current.y() + offset[1], current.z() + offset[2]);
                if (cameFrom.containsKey(neighbour) || !withinRadius(start, neighbour, radius)) {
                    continue;
                }
                if (cells.swimmable(neighbour.x(), neighbour.y(), neighbour.z())) {
                    cameFrom.put(neighbour, current);
                    queue.add(neighbour);
                }
            }
        }
        if (exit == null) {
            return List.of();
        }

        List<Step> path = new ArrayList<>();
        Step step = exit;
        while (!step.equals(start)) {
            path.add(step);
            step = cameFrom.get(step);
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean withinRadius(Step start, Step pos, int radius) {
        return Math.abs(pos.x() - start.x()) <= radius
                && Math.abs(pos.y() - start.y()) <= radius
                && Math.abs(pos.z() - start.z()) <= radius;
    }

    /** A single block, as {@code (x, y, z)}. */
    public record Step(int x, int y, int z) {
    }

    /**
     * What the search needs to know about the world. Both questions are asked only about cells the flood actually
     * reaches, so a caller can answer them straight from the level instead of building anything up front.
     */
    public interface Cells {
        /** Whether a body can swim through this cell: water or open air, never lava. */
        boolean swimmable(int x, int y, int z);

        /** Whether this cell is a dry spot a body could climb out and stand on. */
        boolean standable(int x, int y, int z);
    }
}
