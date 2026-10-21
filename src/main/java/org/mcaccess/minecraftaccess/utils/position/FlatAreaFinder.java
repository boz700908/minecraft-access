package org.mcaccess.minecraftaccess.utils.position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Finds level, open rectangles in a grid of standable cells, which is how "where can I put this down" gets an
 * answer without reading a single block: the caller builds the grid from the world and this works on plain arrays.
 *
 * <p>A cell is standable at height {@code y} when there is solid ground under {@code y} and room for a body above
 * it. One column can be standable at several heights at once - a cave floor beneath a hillside - so every height is
 * searched on its own, and a flat area is always a rectangle of cells standable at one and the same height, never a
 * slope or a step.
 *
 * <p>Within a height the biggest qualifying rectangle is taken first and its cells are cleared before looking again,
 * so one large field comes back as one result rather than dozens of overlapping pieces, while a second field of the
 * same height is still found. A rectangle qualifies when it measures at least {@code a} by {@code b}, in either
 * orientation.
 */
public final class FlatAreaFinder {
    /** The largest side worth asking for; a wider search would cover more ground than anyone builds on. */
    public static final int MAX_SIDE = 64;

    private FlatAreaFinder() {
    }

    /** A rectangle of {@code width} (x) by {@code depth} (z) cells whose near corner is cell ({@code x}, {@code z}), all standable at {@code y}. */
    public record Area(int x, int z, int width, int depth, int y) {
    }

    /**
     * {@code floors} maps a standing height to a row-major mask ({@code gridWidth} cells per row) of the cells
     * standable at that height. The masks are consumed: the cells of a found area are cleared from them. Stops after
     * {@code maxResults} areas, which come back unordered - the caller sorts them by whatever it cares about.
     */
    public static List<Area> find(Map<Integer, boolean[]> floors, int gridWidth, int gridDepth, int a, int b, int maxResults) {
        List<Area> results = new ArrayList<>();
        if (a < 1 || b < 1 || gridWidth < 1 || gridDepth < 1 || maxResults < 1) {
            return results;
        }
        for (Map.Entry<Integer, boolean[]> floor : floors.entrySet()) {
            boolean[] open = floor.getValue();
            while (results.size() < maxResults) {
                Area best = largest(open, gridWidth, gridDepth, a, b, floor.getKey());
                if (best == null) {
                    break;
                }
                results.add(best);
                for (int z = best.z(); z < best.z() + best.depth(); z++) {
                    Arrays.fill(open, z * gridWidth + best.x(), z * gridWidth + best.x() + best.width(), false);
                }
            }
        }
        return results;
    }

    /**
     * The largest-area open rectangle that is at least a by b (or b by a), or null if there is none. Row by row it
     * keeps a histogram of how many open cells sit directly above each column and walks the stack of bars, which
     * visits every maximal rectangle: any qualifying rectangle lies inside one of those, and growing one cannot make
     * it stop qualifying, so none is missed.
     */
    private static Area largest(boolean[] open, int w, int d, int a, int b, int y) {
        Area best = null;
        int[] bars = new int[w];
        int[] stack = new int[w + 1];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                bars[x] = open[z * w + x] ? bars[x] + 1 : 0;
            }
            int top = 0;
            for (int x = 0; x <= w; x++) {
                int bar = x == w ? 0 : bars[x];
                while (top > 0 && bars[stack[top - 1]] >= bar) {
                    int height = bars[stack[--top]];
                    int left = top == 0 ? 0 : stack[top - 1] + 1;
                    int width = x - left;
                    if (qualifies(width, height, a, b)
                            && (best == null || width * height > best.width() * best.depth())) {
                        best = new Area(left, z - height + 1, width, height, y);
                    }
                }
                stack[top++] = x;
            }
        }
        return best;
    }

    private static boolean qualifies(int width, int depth, int a, int b) {
        boolean asAsked = width >= a && depth >= b;
        boolean turned = width >= b && depth >= a;
        return asAsked || turned;
    }
}
