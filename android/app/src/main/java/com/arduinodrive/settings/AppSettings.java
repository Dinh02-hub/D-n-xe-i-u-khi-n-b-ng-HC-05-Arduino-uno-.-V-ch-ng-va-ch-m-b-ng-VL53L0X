package com.arduinodrive.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public final class AppSettings {
    private final SharedPreferences preferences;
    public AppSettings(Context context) {
        preferences = context.getSharedPreferences("drive_settings", Context.MODE_PRIVATE);
    }
    public String getTheme() { return preferences.getString("theme", "light"); }
    public void setTheme(String theme) { preferences.edit().putString("theme", theme).apply(); }
    public void applyTheme() {
        int mode = "light".equals(getTheme()) ? AppCompatDelegate.MODE_NIGHT_NO
                : "dark".equals(getTheme()) ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode);
    }
    public boolean isSimulationDefault() { return preferences.getBoolean("simulation", false); }
    public void setSimulationDefault(boolean enabled) { preferences.edit().putBoolean("simulation", enabled).apply(); }
}
