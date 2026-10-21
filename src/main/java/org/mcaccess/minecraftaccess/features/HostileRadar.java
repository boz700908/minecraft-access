package org.mcaccess.minecraftaccess.features;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.NarrationUtils;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Warns about a hostile mob that has come close and can see the player, which on the client is the closest
 * stand-in for "something is after you": a mob's AI target is never synced to clients, but a hostile in plain
 * sight of the player is either already attacking or about to.
 *
 * <p>Each mob is announced at most once per cooldown, so a mob that stays nearby is an occasional reminder
 * rather than a stream of them, and a line of sight that flickers, through a door or a passing leaf, cannot
 * turn into one either. While something hostile is within actual reach a plain pulse, with no words, repeats
 * on its own interval: at that distance the player knows where the threat is and needs a fast cue, not another
 * sentence competing with the fight.
 */
public class HostileRadar implements BalmClientModule {
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int ALERT_INTERVAL_TICKS = 5;
    private static final int RE_ALERT_COOLDOWN_TICKS = 100;
    private static final int MELEE_ALERT_INTERVAL_TICKS = 10;
    private static final float ALERT_VOLUME = 0.7f;
    private static final float MELEE_VOLUME = 0.6f;
    private static final float MELEE_PITCH = 1.4f;
    /** Per session, so the cooldowns of a previous world say nothing about the one being played now. */
    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "hostile_radar");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.HostileRadar config = Config.getInstance().hostileRadar;
        State state = this.state.value;
        if (!config.enabled) {
            state.pending.clear();
            state.meleeThreatPresent = false;
            return;
        }

        state.ticks++;
        if (state.ticksUntilScan <= 0) {
            state.ticksUntilScan = SCAN_INTERVAL_TICKS;
            scan(player, state, config);
        } else {
            state.ticksUntilScan--;
        }

        if (!state.pending.isEmpty() && state.ticksUntilNextAlert <= 0) {
            state.ticksUntilNextAlert = ALERT_INTERVAL_TICKS;
            alert(client, player, state.pending.poll(), config);
        } else if (state.ticksUntilNextAlert > 0) {
            state.ticksUntilNextAlert--;
        }

        if (config.meleeRangeAlertEnabled && state.meleeThreatPresent) {
            if (state.ticksUntilMeleeAlert <= 0) {
                state.ticksUntilMeleeAlert = MELEE_ALERT_INTERVAL_TICKS;
                playMeleeAlert(client, player);
            } else {
                state.ticksUntilMeleeAlert--;
            }
        } else {
            state.ticksUntilMeleeAlert = 0;
        }
    }

    private void scan(LocalPlayer player, State state, Config.HostileRadar config) {
        // Without this the map would keep one entry per hostile ever met, and an entry whose cooldown has run
        // out is treated as eligible to alert again anyway, so it is dead weight.
        state.lastAlertTick.entrySet().removeIf(entry -> state.ticks - entry.getValue() >= RE_ALERT_COOLDOWN_TICKS);

        double range = config.range;
        double meleeRange = player.entityInteractionRange();
        Vec3 eye = player.getEyePosition();
        AABB box = player.getBoundingBox().inflate(range);
        state.meleeThreatPresent = false;
        for (Entity entity : player.level().getEntities(player, box, e -> e.isAlive() && e instanceof Enemy)) {
            if (!(entity instanceof LivingEntity living)) continue;

            double distSq = eye.distanceToSqr(living.getEyePosition());
            boolean visible = hasLineOfSight(player.level(), eye, living.getEyePosition());
            if (visible && distSq <= meleeRange * meleeRange) {
                state.meleeThreatPresent = true;
            }

            Integer alertedAt = state.lastAlertTick.get(living.getId());
            if (alertedAt != null && state.ticks - alertedAt < RE_ALERT_COOLDOWN_TICKS) continue;
            if (distSq > range * range || state.pending.contains(living)) continue;
            if (visible) {
                state.pending.add(living);
            }
        }
    }

    private void alert(Minecraft client, LocalPlayer player, Entity entity, Config.HostileRadar config) {
        State state = this.state.value;
        state.lastAlertTick.put(entity.getId(), state.ticks);
        if (!entity.isAlive() || entity.level() != player.level()) return;

        Vec3 pos = entity.position();
        RandomSource random = player.getRandom();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER,
                config.volume / 100.0f * ALERT_VOLUME, 1.0f, random, pos.x(), pos.y(), pos.z()));

        int distance = (int) Math.round(player.getEyePosition().distanceTo(entity.position()));
        String direction = NarrationUtils.narrateRelativeDirection(pos);
        MainClass.narrate(I18n.get("minecraft_access.hostile_radar.alert",
                entity.getDisplayName().getString(), distance, direction), true);
    }

    private void playMeleeAlert(Minecraft client, LocalPlayer player) {
        State state = this.state.value;
        RandomSource random = player.getRandom();
        Vec3 pos = player.position();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.MASTER,
                Config.getInstance().hostileRadar.volume / 100.0f * MELEE_VOLUME, MELEE_PITCH, random,
                pos.x(), pos.y(), pos.z()));
        state.ticksUntilMeleeAlert = MELEE_ALERT_INTERVAL_TICKS;
    }

    private static boolean hasLineOfSight(Level level, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Everything the radar remembers between ticks of one session. */
    private static final class State {
        private final Map<Integer, Integer> lastAlertTick = new HashMap<>();
        private final Deque<Entity> pending = new ArrayDeque<>();
        private int ticksUntilScan;
        private int ticksUntilNextAlert;
        private int ticksUntilMeleeAlert;
        private int ticks;
        private boolean meleeThreatPresent;
    }
}
