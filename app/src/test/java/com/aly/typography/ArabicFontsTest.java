package com.aly.typography;

import android.app.Application;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = ArabicApplication.class)
public class ArabicFontsTest {
    @Test public void mixedTextPreservesCharactersLatinTypefaceAndOtherSpans() {
        Application app = RuntimeEnvironment.getApplication();
        TextView view = new TextView(app);
        view.setTypeface(Typeface.MONOSPACE);
        SpannableString value = new SpannableString("Hello سلام 123");
        ForegroundColorSpan color = new ForegroundColorSpan(0xff123456);
        value.setSpan(color, 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(value);
        ArabicFonts.watchRoot(view);
        Spanned actual = (Spanned) view.getText();
        assertEquals("Hello سلام 123", actual.toString());
        assertEquals(Typeface.MONOSPACE, view.getTypeface());
        assertEquals(1, actual.getSpans(0, actual.length(), ForegroundColorSpan.class).length);
        ArabicFonts.ArabicSpan[] spans = actual.getSpans(0, actual.length(), ArabicFonts.ArabicSpan.class);
        assertEquals(1, spans.length);
        assertEquals(6, actual.getSpanStart(spans[0]));
        assertEquals(10, actual.getSpanEnd(spans[0]));
        TextPaint paint = new TextPaint(); paint.setTypeface(Typeface.DEFAULT_BOLD);
        spans[0].updateMeasureState(paint);
        assertNotEquals(Typeface.DEFAULT_BOLD, paint.getTypeface());
    }

    @Test public void editsAndHintsAreStyledWithoutChangingSelectionOrAccumulatingSpans() {
        EditText edit = new EditText(RuntimeEnvironment.getApplication());
        edit.setText("سلام"); edit.setSelection(2); edit.setHint("ابحث");
        ArabicFonts.watchRoot(edit);
        assertEquals(2, edit.getSelectionStart());
        assertEquals(1, ((Spanned) edit.getHint()).getSpans(0, 4, ArabicFonts.ArabicSpan.class).length);
        edit.append(" عالم");
        assertEquals(2, edit.getText().getSpans(0, edit.length(), ArabicFonts.ArabicSpan.class).length);
        ArabicFonts.watchRoot(edit);
        assertEquals(2, edit.getText().getSpans(0, edit.length(), ArabicFonts.ArabicSpan.class).length);
        edit.setText("English 123");
        assertEquals(0, edit.getText().getSpans(0, edit.length(), ArabicFonts.ArabicSpan.class).length);
    }

    @Test public void corruptPrivateCacheFallsBackWithoutCrashing() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        java.io.File file = new java.io.File(app.getNoBackupFilesDir(), "thmanyah-build-48/r");
        java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3});
        try {
            ArabicFonts.install(app);
            TextView text = new TextView(app); text.setText("سلام");
            ArabicFonts.watchRoot(text);
            assertEquals(Typeface.DEFAULT, text.getTypeface());
        } finally { file.delete(); ArabicFonts.install(app); }
    }
}
