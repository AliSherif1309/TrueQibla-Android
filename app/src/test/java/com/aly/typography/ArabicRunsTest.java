package com.aly.typography;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArabicRunsTest {
    @Test public void latinAndNeutralTextStayUnchanged() {
        assertTrue(ArabicRuns.of("NOOR 123 🙂").isEmpty());
        assertTrue(ArabicRuns.of(null).isEmpty());
    }
    @Test public void arabicDiacriticsAndJoinControlsStayTogether() {
        String text = "ABC سَلَام\u200Dع XYZ";
        int[] run = ArabicRuns.of(text).get(0);
        assertEquals("سَلَام\u200Dع", text.substring(run[0], run[1]));
        assertEquals(1, ArabicRuns.of(text).size());
    }
    @Test public void supplementaryCharactersUseUtf16Offsets() {
        String text = "🙂 \uD83B\uDE00 \uD803\uDEFD";
        assertArrayEquals(new int[]{3, 5}, ArabicRuns.of(text).get(0));
        assertArrayEquals(new int[]{6, 8}, ArabicRuns.of(text).get(1));
    }
    @Test public void latinAndArabicNumbersAreSeparate() {
        assertEquals(2, ArabicRuns.of("عربي 123 العربية١٢").size());
    }
}
