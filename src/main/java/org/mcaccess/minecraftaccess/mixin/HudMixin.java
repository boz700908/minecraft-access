package org.mcaccess.minecraftaccess.mixin;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.OverlayRepeatFilter;

/**
 * Narrates the selected hotbar item's name and the action bar. Titles are read by
 * {@link org.mcaccess.minecraftaccess.features.TitleReader} instead, once both a title and its subtitle have landed.
 */
@Mixin(Hud.class)
abstract class HudMixin {
    @Unique
    private final OverlayRepeatFilter overlayRepeatFilter = new OverlayRepeatFilter();

    @Unique
    private String previousActionBarContent = "";

    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"))
    private void narrateActionbar(Component string, boolean animate, CallbackInfo ci) {
        Config config = Config.getInstance();
        if (!config.features.actionBarEnabled) {
            return;
        }
        String msg = string.getString();
        if (!overlayRepeatFilter.shouldNarrate(msg, Util.getMillis())) {
            return;
        }
        if (previousActionBarContent.equals(msg)) {
            // The same text again, but the earlier copy has faded: read all of it rather than the nothing a
            // diff against itself would leave.
            previousActionBarContent = "";
        }
        if (config.features.onlyNarrateActionBarUpdates) {
            onlyNarrateChangedParts(msg);
        } else {
            MainClass.narrate(msg, true);
        }
        previousActionBarContent = msg;
    }

    @Unique
    private void onlyNarrateChangedParts(String msg) {
        List<String> parts = Arrays.asList(splitToParts(msg));
        List<String> previousParts = Arrays.asList(splitToParts(previousActionBarContent));
        parts.removeAll(previousParts);
        String narration = String.join(", ", parts);
        MainClass.narrate(narration, true);
    }

    @Unique
    private String[] splitToParts(String msg) {
        if (msg.contains(",")) {
            return msg.split(",");
        } else {
            return msg.split("\\s");
        }
    }
}
