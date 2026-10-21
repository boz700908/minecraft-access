package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Says at the start of a dig when the held item cannot harvest the block, with the tool level the block needs,
 * so a wrong pickaxe or bare hands are not discovered after the block has already been broken for nothing.
 *
 * <p>The warning fires once per block and held item: it comes from the edge of starting a dig, or of swapping
 * items mid-dig, not from every tick the attack key is down.
 */
public class ToolHarvestAwareness implements BalmClientModule {
    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "tool_harvest_awareness");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        State state = this.state.value;
        if (!Config.getInstance().toolHarvestAwareness.enabled || !client.options.keyAttack.isDown()
                || player.isSpectator() || player.isCreative()) {
            state.reset();
            return;
        }

        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            state.reset();
            return;
        }
        check(player, level, blockHit.getBlockPos(), state);
    }

    private void check(LocalPlayer player, Level level, BlockPos pos, State state) {
        ItemStack held = player.getMainHandItem();
        if (state.mining && pos.equals(state.lastPos) && ItemStack.matches(held, state.lastHeld)) return;

        state.mining = true;
        state.lastPos = pos;
        state.lastHeld = held.copy();

        BlockState blockState = level.getBlockState(pos);
        if (!blockState.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(blockState)) return;

        String blockName = blockState.getBlock().getName().getString();
        MainClass.narrate(I18n.get(requirementKey(blockState), blockName), true);
    }

    private static String requirementKey(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "minecraft_access.tool_harvest.diamond";
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return "minecraft_access.tool_harvest.iron";
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return "minecraft_access.tool_harvest.stone";
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "minecraft_access.tool_harvest.pickaxe";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "minecraft_access.tool_harvest.axe";
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "minecraft_access.tool_harvest.shovel";
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return "minecraft_access.tool_harvest.hoe";
        return "minecraft_access.tool_harvest.generic";
    }

    /** What the last warning was about, so a dig that goes on says it once. */
    private static final class State {
        private BlockPos lastPos;
        private ItemStack lastHeld = ItemStack.EMPTY;
        private boolean mining;

        private void reset() {
            mining = false;
            lastPos = null;
        }
    }
}
