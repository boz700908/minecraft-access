package org.mcaccess.minecraftaccess.utils;

/**
 * Decides whether an action bar message is worth narrating again.
 *
 * <p>Plenty of servers keep a HUD (health, mana, cooldowns) on screen by resending the very same
 * overlay packet every tick or two. Reading each resend turns a readout that has not changed into a
 * message repeated several times a second, so an identical text is passed over while the previous
 * copy is still visible. Once that copy would have faded the player has lost it, and the same text
 * arriving again is a genuine repeat - a second "You may not rest now" at a bed - so it is read
 * again.
 */
public final class OverlayRepeatFilter {
    /** Vanilla's {@code Hud#setOverlayMessage} keeps an overlay up for 60 ticks. */
    static final long OVERLAY_VISIBLE_MILLIS = 3000;

    private String lastText;
    /** When any overlay was last received - each resend restarts vanilla's display timer. */
    private long lastReceivedMillis = Long.MIN_VALUE / 2;

    /** Records that {@code text} was just received at {@code nowMillis}, and returns whether to narrate it. */
    public boolean shouldNarrate(String text, long nowMillis) {
        boolean stillShowing = nowMillis - lastReceivedMillis < OVERLAY_VISIBLE_MILLIS;
        lastReceivedMillis = nowMillis;
        if (stillShowing && text.equals(lastText)) {
            return false;
        }
        lastText = text;
        return true;
    }
}
