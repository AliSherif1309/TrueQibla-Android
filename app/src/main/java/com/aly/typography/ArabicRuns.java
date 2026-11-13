package com.aly.typography;

import java.util.ArrayList;
import java.util.List;

/** UTF-16 ranges for Arabic script; Latin text and neutral punctuation keep their own font. */
public final class ArabicRuns {
    private ArabicRuns() {}
    public static List<int[]> of(CharSequence text) {
        List<int[]> ranges = new ArrayList<>();
        if (text == null) return ranges;
        int start = -1;
        for (int i = 0; i < text.length();) {
            int cp = Character.codePointAt(text, i);
            int kind = Character.getType(cp);
            // Explicit Arabic blocks also cover API 23 and newer Unicode additions that
            // older Android Character tables do not know. Arabic punctuation belongs here too.
            boolean arabic = (cp >= 0x0600 && cp <= 0x06FF)
                    || (cp >= 0x0750 && cp <= 0x077F)
                    || (cp >= 0x0870 && cp <= 0x08FF)
                    || (cp >= 0xFB50 && cp <= 0xFDFF)
                    || (cp >= 0xFE70 && cp <= 0xFEFC)
                    || (cp >= 0x10EC0 && cp <= 0x10EFF)
                    || (cp >= 0x1EE00 && cp <= 0x1EEFF);
            boolean continuation = start >= 0 && (cp == 0x200C || cp == 0x200D
                    || kind == Character.NON_SPACING_MARK || kind == Character.COMBINING_SPACING_MARK);
            if (arabic || continuation) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                ranges.add(new int[]{start, i}); start = -1;
            }
            i += Character.charCount(cp);
        }
        if (start >= 0) ranges.add(new int[]{start, text.length()});
        return ranges;
    }
}
