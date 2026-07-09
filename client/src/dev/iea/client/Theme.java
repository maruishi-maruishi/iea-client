package dev.iea.client;

/**
 * Colors (0xAARRGGBB) mirroring the launcher's lime-on-dark theme.
 */
public final class Theme {
    // Exact launcher tokens (renderer/src/index.css + App.tsx): deep #06070a base,
    // #16181f chrome, #0e0f14 content/inset, #1c1f29 hover, #262a36 border, #8a8f9c muted,
    // lime-400 #a3e635 accent (lime-600 #65a30d shade).
    public static final int DEEP     = 0xFF06070A; // deepest backdrop (behind the window)
    public static final int GRID     = 0x73262A36; // faint geometric-grid line (#262a36 @ .45)
    public static final int BACKDROP = 0xCC06070A; // dim behind the panel
    public static final int PANEL    = 0xFF16181F; // sidebar / titlebar chrome
    public static final int PANEL2   = 0xFF1C1F29; // hover / active fill
    public static final int CONTENT  = 0xFF0E0F14; // main content / inset boxes
    public static final int ROW      = 0xFF0E0F14; // inset rows (matches launcher insets)
    public static final int BORDER   = 0xFF262A36; // launcher border
    public static final int TEXT     = 0xFFE7E9EE;
    public static final int MUTED    = 0xFF8A8F9C; // launcher muted text
    // Accent is USER-CHANGEABLE (Theme module), so these are NOT final — a final constant
    // would be inlined into other classes at compile time and never update at runtime.
    public static int ACCENT   = 0xFFA3E635; // theme accent (launcher lime-400)
    public static int ACCENT2  = 0xFF65A30D; // darker accent (launcher lime-600)
    public static final int DARK     = 0xFF0E0F14; // text on lime header
    public static final int TRACK    = 0xFF2A2E3A; // toggle track (off)

    public static final int DEFAULT_ACCENT = 0xA3E635; // the stock lime (RGB, no alpha)

    /** Set the accent from an RGB value (no alpha). ACCENT2 is a darker shade of it,
     *  used for slider fills / toggle tracks / pressed keys. */
    public static void setAccent(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        ACCENT = 0xFF000000 | (rgb & 0xFFFFFF);
        ACCENT2 = 0xFF000000 | ((int) (r * 0.63f) << 16) | ((int) (g * 0.63f) << 8) | (int) (b * 0.63f);
    }

    /** The accent colour with a custom alpha byte — for subtle borders / washes. */
    public static int accentA(int alpha) {
        return ((alpha & 0xFF) << 24) | (ACCENT & 0xFFFFFF);
    }
}
