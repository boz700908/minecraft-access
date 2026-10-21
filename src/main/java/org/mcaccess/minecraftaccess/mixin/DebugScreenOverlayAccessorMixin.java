package org.mcaccess.minecraftaccess.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import org.mcaccess.minecraftaccess.utils.DebugScreenOverlayAccess;

/**
 * Exposes the F3 overlay's private level/chunk lookups, so the overlay can be rebuilt line by line with its
 * sections kept apart. See {@link DebugScreenOverlayAccess}.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayAccessorMixin implements DebugScreenOverlayAccess {
    @Invoker("getLevel")
    @Override
    public abstract Level getOverlayLevel();

    @Invoker("getClientChunk")
    @Override
    public abstract LevelChunk getOverlayClientChunk();

    @Invoker("getServerChunk")
    @Override
    public abstract LevelChunk getOverlayServerChunk();
}
