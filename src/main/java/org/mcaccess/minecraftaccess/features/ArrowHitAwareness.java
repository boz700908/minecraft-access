package org.mcaccess.minecraftaccess.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * A short cue for whether a shot actually landed. A shot at real range lands well beyond both sight and the
 * positional sound of an arrow striking something, so without this there is no way to tell a hit from a miss.
 *
 * <p>The client is never told directly that an arrow hit: striking a living target discards the arrow, which
 * looks just like it flying out of tracked range. So a hit is inferred. Every arrow the player fires is watched,
 * and one that vanishes while still moving is a candidate, since an arrow that embeds in a block stops first and
 * stays stopped. A candidate only counts when something living near where it was last seen was just hurt or has
 * just died, which the client does see for any damage including the killing blow. Vanishing alone is not enough:
 * that is exactly how a shot that merely flew past the entity-tracking range looks.
 *
 * <p>A hit that also kills gets a lower, heavier cue, so a kill can be told from an ordinary hit by ear. Both play
 * at the player's own position rather than the target's: a shot landing fifty blocks away would be inaudible if it
 * faded with distance, and the answer wanted here is a plain yes or no.
 */
public class ArrowHitAwareness implements BalmClientModule {
    // Comfortably past a fully drawn bow's range, so a shot the player just fired is never missed.
    private static final double DISCOVERY_RANGE = 128.0;
    private static final double STILL_EPSILON_SQ = 0.0025;
    private static final int STILL_TICKS_FOR_SETTLED = 2;
    private static final double VICTIM_SEARCH_RADIUS = 4.0;
    private static final int VERDICT_WAIT_TICKS = 3;
    private static final int RECENT_HURT_TICKS = 4;
    private static final int RECENT_DEATH_TICKS = 4;
    // A trident strikes something and bounces off rather than vanishing, so its impact is its speed collapsing:
    // from at least the first of these, down to under the fraction of its peak that the second is.
    private static final double TRIDENT_MIN_IMPACT_SPEED = 0.5;
    private static final double TRIDENT_IMPACT_SPEED_FRACTION = 0.5;

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "arrow_hit_awareness");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        State state = this.state.value;
        if (!Config.getInstance().arrowHitAwareness.enabled) return;

        AABB box = player.getBoundingBox().inflate(DISCOVERY_RANGE);
        for (Entity entity : level.getEntities(player, box,
                candidate -> candidate instanceof AbstractArrow arrow && arrow.getOwner() == player)) {
            state.watched.computeIfAbsent(entity.getId(),
                    id -> new Tracked(entity.getId(), entity instanceof ThrownTrident, entity.position()));
        }

        watch(state, level);
        resolve(client, player, level, state);
    }

    private void watch(State state, ClientLevel level) {
        Iterator<Map.Entry<Integer, Tracked>> iterator = state.watched.entrySet().iterator();
        while (iterator.hasNext()) {
            Tracked tracked = iterator.next().getValue();
            Entity entity = level.getEntity(tracked.id);
            if (entity == null) {
                // A trident that disappears was picked up or recalled without having struck anything; its
                // impacts are caught by the speed check below instead.
                if (!tracked.trident && tracked.stillTicks < STILL_TICKS_FOR_SETTLED) {
                    state.candidates.add(new Candidate(tracked.lastPos));
                }
                iterator.remove();
                continue;
            }

            Vec3 pos = entity.position();
            if (tracked.trident) {
                double speed = pos.distanceTo(tracked.lastPos);
                if (speed > tracked.peakSpeed) {
                    tracked.peakSpeed = speed;
                } else if (tracked.peakSpeed >= TRIDENT_MIN_IMPACT_SPEED
                        && speed < tracked.peakSpeed * TRIDENT_IMPACT_SPEED_FRACTION) {
                    state.candidates.add(new Candidate(pos));
                    // Starts over, so a trident that comes back and strikes again is judged afresh.
                    tracked.peakSpeed = 0.0;
                }
            }
            tracked.stillTicks = pos.distanceToSqr(tracked.lastPos) < STILL_EPSILON_SQ ? tracked.stillTicks + 1 : 0;
            tracked.lastPos = pos;
        }
    }

    private void resolve(Minecraft client, LocalPlayer player, ClientLevel level, State state) {
        Iterator<Candidate> iterator = state.candidates.iterator();
        while (iterator.hasNext()) {
            Candidate candidate = iterator.next();
            Outcome outcome = victimNear(player, level, candidate.lastPos);
            if (outcome == Outcome.KILL) {
                playKillCue(client, player);
                iterator.remove();
                continue;
            }
            if (outcome == Outcome.HURT) {
                if (candidate.sawHurt) {
                    playHitCue(client, player);
                    iterator.remove();
                    continue;
                }
                candidate.sawHurt = true;
            }
            if (--candidate.ticksLeft <= 0) {
                if (candidate.sawHurt) playHitCue(client, player);
                iterator.remove();
            }
        }
    }

    /**
     * The strongest thing that has just happened to something living near a position: it died, it was hurt, or
     * nothing at all.
     */
    private static Outcome victimNear(LocalPlayer player, ClientLevel level, Vec3 pos) {
        AABB box = new AABB(pos, pos).inflate(VICTIM_SEARCH_RADIUS);
        Outcome outcome = Outcome.NONE;
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, living -> living != player)) {
            if (living.isDeadOrDying() && living.deathTime <= RECENT_DEATH_TICKS) return Outcome.KILL;
            if (living.hurtTime > 0 && living.hurtTime > living.hurtDuration - RECENT_HURT_TICKS) {
                outcome = Outcome.HURT;
            }
        }
        return outcome;
    }

    private static void playKillCue(Minecraft client, LocalPlayer player) {
        RandomSource random = player.getRandom();
        float volume = Config.getInstance().arrowHitAwareness.volume / 100.0f;
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_BASEDRUM.value(),
                SoundSource.MASTER, volume, 0.8f, random, player.getX(), player.getEyeY(), player.getZ()));
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.ARROW_HIT_PLAYER, SoundSource.MASTER,
                volume, 1.6f, random, player.getX(), player.getEyeY(), player.getZ()));
    }

    private static void playHitCue(Minecraft client, LocalPlayer player) {
        RandomSource random = player.getRandom();
        float volume = Config.getInstance().arrowHitAwareness.volume / 100.0f;
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.ARROW_HIT_PLAYER, SoundSource.MASTER,
                volume, 1.0f, random, player.getX(), player.getEyeY(), player.getZ()));
    }

    /** What the shots fired so far look like, and what has yet to be decided. */
    private static final class State {
        private final Map<Integer, Tracked> watched = new HashMap<>();
        private final List<Candidate> candidates = new ArrayList<>();
    }

    /** One arrow or trident of the player's, as it was last seen. */
    private static final class Tracked {
        private final int id;
        private final boolean trident;
        private Vec3 lastPos;
        private int stillTicks;
        // The fastest per-tick movement since the last impact, which a trident's slowdown is measured against.
        private double peakSpeed;

        private Tracked(int id, boolean trident, Vec3 lastPos) {
            this.id = id;
            this.trident = trident;
            this.lastPos = lastPos;
        }
    }

    /** A shot that has vanished, waiting for the hurt or death that says it connected. */
    private static final class Candidate {
        private final Vec3 lastPos;
        private int ticksLeft = VERDICT_WAIT_TICKS;
        // A hurt was seen but not yet a death, held one more tick in case the death is still on its way, so a
        // killing blow is not announced as an ordinary hit.
        private boolean sawHurt;

        private Candidate(Vec3 lastPos) {
            this.lastPos = lastPos;
        }
    }

    /** What a shot turned out to be. */
    private enum Outcome {
        NONE,
        HURT,
        KILL
    }
}
