package com.aly.typography;

/** Registers dialog windows without replacing callers' click/show/dismiss listeners. */
public final class ArabicDialogBuilder extends android.app.AlertDialog.Builder {
    public ArabicDialogBuilder(android.content.Context context) { super(context); }
    public ArabicDialogBuilder(android.content.Context context, int theme) { super(context, theme); }
    @Override public android.app.AlertDialog create() {
        android.app.AlertDialog dialog = super.create();
        ArabicFonts.watch(dialog.getWindow());
        return dialog;
    }
}
