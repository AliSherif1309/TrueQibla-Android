package com.aly.typography;

public final class ArabicApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        ArabicFonts.install(this);
    }
}
