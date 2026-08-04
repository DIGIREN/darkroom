package com.calypso.darkroom;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile that kills the green and blue channels for darkroom use.
 * Works by writing LiveDisplay's RGB color adjustment (LineageOS settings
 * provider), which SurfaceFlinger applies as a color matrix at composition, so
 * the green/blue subpixels genuinely emit nothing, unlike an overlay.
 * Needs lineageos.permission.WRITE_SETTINGS (normal, declared in the manifest).
 */
public class SafelightTile extends TileService {

    private static final Uri SYSTEM = Uri.parse("content://lineagesettings/system");
    private static final String KEY = "display_color_adjustment";
    private static final String RED_ONLY = "1.0 0.0 0.0";
    private static final String NORMAL = "1.0 1.0 1.0";

    // Brightness clamp while the safelight is on (0-255 scale).
    private static final int CLAMP = 25;
    private static final String PREFS = "safelight";

    private String getLineage(String name) {
        Cursor c = getContentResolver().query(SYSTEM, new String[] {"value"},
                "name=?", new String[] {name}, null);
        try {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } finally {
            if (c != null) c.close();
        }
    }

    private void putLineage(String name, String value) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("value", value);
        getContentResolver().insert(SYSTEM, cv);
    }

    private boolean isRedOnly() {
        Cursor c = getContentResolver().query(SYSTEM, new String[] {"value"},
                "name=?", new String[] {KEY}, null);
        try {
            return c != null && c.moveToFirst() && RED_ONLY.equals(c.getString(0));
        } finally {
            if (c != null) c.close();
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(isRedOnly() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override public void onStartListening() {
        // Re-assert the clamp and the matrix if something clobbered them.
        if (isRedOnly()) {
            clampBrightness();
            putLineage(KEY, RED_ONLY);
        }
        refresh();
    }

    private void clampBrightness() {
        android.content.ContentResolver r = getContentResolver();
        if (android.provider.Settings.System.getInt(r, "screen_brightness", 0) > CLAMP) {
            android.provider.Settings.System.putInt(r, "screen_brightness", CLAMP);
        }
        android.provider.Settings.System.putInt(r, "screen_brightness_mode", 0);
    }

    private void enterSafelight() {
        android.content.ContentResolver r = getContentResolver();
        // LiveDisplay's automatic modes (day/night temperature, auto outdoor)
        // recompute the color transform and clobber the red adjustment - park
        // them while the safelight is on, remembering what they were.
        String temp = getLineage("display_temperature_mode");
        String outdoor = getLineage("display_auto_outdoor_mode");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("brightness", android.provider.Settings.System.getInt(r, "screen_brightness", 128))
                .putInt("mode", android.provider.Settings.System.getInt(r, "screen_brightness_mode", 0))
                .putString("tempMode", temp == null ? "0" : temp)
                .putString("outdoor", outdoor == null ? "0" : outdoor)
                .apply();
        putLineage("display_temperature_mode", "0");
        putLineage("display_auto_outdoor_mode", "0");
        clampBrightness();
    }

    private void exitSafelight() {
        android.content.ContentResolver r = getContentResolver();
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.provider.Settings.System.putInt(r, "screen_brightness", p.getInt("brightness", 128));
        android.provider.Settings.System.putInt(r, "screen_brightness_mode", p.getInt("mode", 0));
        putLineage("display_temperature_mode", p.getString("tempMode", "0"));
        putLineage("display_auto_outdoor_mode", p.getString("outdoor", "0"));
    }

    @Override public void onClick() {
        if (!android.provider.Settings.System.canWrite(this)) {
            // WRITE_SETTINGS is an appop; send the user to the grant screen.
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
            return;
        }
        boolean enabling = !isRedOnly();
        ContentValues cv = new ContentValues();
        cv.put("name", KEY);
        cv.put("value", enabling ? RED_ONLY : NORMAL);
        // The provider upserts on insert, same as the settings CLI.
        getContentResolver().insert(SYSTEM, cv);
        if (enabling) enterSafelight(); else exitSafelight();
        refresh();
    }
}
