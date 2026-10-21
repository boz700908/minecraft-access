package org.mcaccess.minecraftaccess.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the title and subtitle the {@link Hud} is currently showing, so they can be read a tick after they arrive.
 * See {@link org.mcaccess.minecraftaccess.features.TitleReader}.
 */
@Mixin(Hud.class)
public interface HudTitleAccessor {
    @Accessor("title")
    Component getHudTitle();

    @Accessor("subtitle")
    Component getHudSubtitle();
}
