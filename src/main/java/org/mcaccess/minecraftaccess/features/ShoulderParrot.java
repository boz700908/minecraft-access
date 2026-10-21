package org.mcaccess.minecraftaccess.features;

import java.util.Optional;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.parrot.Parrot;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Says when a parrot lands on a shoulder or leaves it, the local player's own being the one that is otherwise
 * never mentioned at all.
 *
 * <p>Landing covers taming a wild parrot and one that already follows you climbing back up after being shaken
 * off; leaving covers sneaking the shoulder empty, taking damage, and a ride that simply ran its course. A
 * parrot on a shoulder is no longer an entity of its own - only the colour variant the game keeps for each
 * shoulder is left - so there is nothing to look at or scan, and this change of that variant is the one place
 * it can be noticed.
 *
 * <p>Each shoulder is followed and announced separately, since one can change while the other does not:
 * shaking off a single side is possible, and taming a second parrot for the free shoulder is common.
 */
public class ShoulderParrot implements BalmClientModule {
    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "shoulder_parrot");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        State state = this.state.value;

        // An absent answer of null means the shoulder has not been looked at yet, so the parrot already
        // sitting there when a session starts is not announced as having just landed.
        Optional<Parrot.Variant> left = player.getShoulderParrotLeft();
        if (state.left != null && !left.equals(state.left)) narrate(left, true);
        state.left = left;

        Optional<Parrot.Variant> right = player.getShoulderParrotRight();
        if (state.right != null && !right.equals(state.right)) narrate(right, false);
        state.right = right;
    }

    private static void narrate(Optional<Parrot.Variant> variant, boolean left) {
        if (variant.isPresent()) {
            MainClass.narrate(I18n.get("minecraft_access.shoulder_parrot.landed_" + (left ? "left" : "right"),
                    I18n.get("minecraft_access.parrot_variant." + variant.get().getSerializedName())), true);
        } else {
            MainClass.narrate(I18n.get("minecraft_access.shoulder_parrot.flew_off_" + (left ? "left" : "right")), true);
        }
    }

    /** Which parrot was on each shoulder when last looked at. */
    private static final class State {
        private Optional<Parrot.Variant> left;
        private Optional<Parrot.Variant> right;
    }
}
