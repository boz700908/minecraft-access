package org.mcaccess.minecraftaccess.utils;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reaches the F3 overlay's private level and chunk lookups, which every debug entry needs as arguments and which
 * hold vanilla's own cache of the server chunk. Implemented by
 * {@link org.mcaccess.minecraftaccess.mixin.DebugScreenOverlayAccessorMixin}.
 */
public interface DebugScreenOverlayAccess {
    Level getOverlayLevel();

    LevelChunk getOverlayClientChunk();

    /**
     * May be null: vanilla fetches it asynchronously and the entries are written to cope.
     *
     * @return the server-side chunk the overlay is holding, or {@code null} if it has none yet
     */
    LevelChunk getOverlayServerChunk();
}
