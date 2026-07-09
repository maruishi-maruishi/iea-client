package dev.iea.client.module;

import java.util.ArrayList;
import java.util.List;

import dev.iea.client.Theme;

/** A toggleable feature with optional per-module settings. */
public final class Module {
    public final String name;
    public final String category;
    public boolean enabled;
    public String descKey; // Lang key for a short description (shown on the settings page)
    public final List<Setting> settings = new ArrayList<Setting>();

    // Optional key that toggles this module on/off in-game (0 = unbound).
    public int toggleKey = 0;
    // Per-module accent: when customColor is on, `color` (RGB, no alpha) is used instead of
    // the global Theme accent for this module's UI/HUD highlights.
    public boolean customColor = false;
    public int color = Theme.DEFAULT_ACCENT;
    // Hidden from the module grid — controlled from a dedicated tab (Theme / Settings) instead.
    public boolean hidden = false;

    public Module(String name, String category, boolean enabledByDefault) {
        this.name = name;
        this.category = category;
        this.enabled = enabledByDefault;
    }

    public Module add(Setting s) {
        settings.add(s);
        return this;
    }

    public Module desc(String key) {
        this.descKey = key;
        return this;
    }

    public Module hide() { this.hidden = true; return this; }

    /** This module's accent (ARGB): its custom colour when set, else the global theme accent. */
    public int accent() {
        return customColor ? (0xFF000000 | (color & 0xFFFFFF)) : Theme.ACCENT;
    }

    /** A darker shade of {@link #accent()} (for slider fills / active key tiles). */
    public int accent2() {
        if (!customColor) return Theme.ACCENT2;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return 0xFF000000 | ((int) (r * 0.63f) << 16) | ((int) (g * 0.63f) << 8) | (int) (b * 0.63f);
    }

    public Setting get(String key) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).key.equals(key)) return settings.get(i);
        }
        return null;
    }

    public boolean bool(String key) {
        Setting s = get(key);
        return s != null && s.type == Setting.BOOL && s.bool;
    }

    public float num(String key, float def) {
        Setting s = get(key);
        // MODE stores its selected index in `num`, so it reads through here too.
        return (s != null && (s.type == Setting.NUMBER || s.type == Setting.MODE)) ? s.num : def;
    }

    public int keyCode(String key, int def) {
        Setting s = get(key);
        return (s != null && s.type == Setting.KEY) ? s.keyCode : def;
    }
}
