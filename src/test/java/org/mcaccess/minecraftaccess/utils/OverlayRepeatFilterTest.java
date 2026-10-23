package org.mcaccess.minecraftaccess.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayRepeatFilterTest {
    @Test
    void narratesTheFirstMessage() {
        assertTrue(new OverlayRepeatFilter().shouldNarrate("Hello", 0));
    }

    @Test
    void skipsTheSameTextWhileItIsStillOnScreen() {
        OverlayRepeatFilter filter = new OverlayRepeatFilter();
        filter.shouldNarrate("Mana 40", 1000);

        assertFalse(filter.shouldNarrate("Mana 40", 1500));
        assertFalse(filter.shouldNarrate("Mana 40", 3999));
    }

    @Test
    void narratesTheSameTextAgainOnceItWouldHaveFaded() {
        OverlayRepeatFilter filter = new OverlayRepeatFilter();
        filter.shouldNarrate("You may not rest now", 1000);

        assertTrue(filter.shouldNarrate("You may not rest now", 4000));
    }

    @Test
    void narratesChangedTextEvenWhileTheOldOneIsShowing() {
        OverlayRepeatFilter filter = new OverlayRepeatFilter();
        filter.shouldNarrate("Mana 40", 1000);

        assertTrue(filter.shouldNarrate("Mana 30", 1100));
    }

    @Test
    void returningToEarlierTextIsAnUpdate() {
        OverlayRepeatFilter filter = new OverlayRepeatFilter();
        filter.shouldNarrate("A", 0);
        filter.shouldNarrate("B", 100);

        assertTrue(filter.shouldNarrate("A", 200));
    }

    @Test
    void aResendKeepsTheMessageOnScreen() {
        // Every resend restarts vanilla's timer, so a server refreshing an unchanged HUD every second never
        // reaches the fade-out - and keeps being skipped while the player can still see it.
        OverlayRepeatFilter filter = new OverlayRepeatFilter();
        filter.shouldNarrate("HUD", 0);

        assertFalse(filter.shouldNarrate("HUD", 2000));
        assertFalse(filter.shouldNarrate("HUD", 4000));
        assertFalse(filter.shouldNarrate("HUD", 6000));
    }

    @Test
    void changedTextStartsTheTimerOver() {
        OverlayRepeatFilter filter = new OverlayRepeatFilter();
        filter.shouldNarrate("A", 0);
        filter.shouldNarrate("B", 2900);

        // B was last on screen from 2900, so it has faded by 5900 even though A arrived far earlier; a timer left
        // over from A would have called this a repeat long before.
        assertTrue(filter.shouldNarrate("B", 5900));
    }
}
