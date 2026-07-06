package dev.iea.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import dev.iea.client.Hook;
import dev.iea.client.Mc;
import dev.iea.client.Theme;
import dev.iea.client.Translate;
import dev.iea.client.module.Module;
import dev.iea.client.module.Modules;

/**
 * Custom chat renderer, shared by two Chat features:
 *   - ChatOptimize: collapse consecutive duplicates into one "×N" line + keep a longer,
 *     scrollable history (past vanilla's 100-line cap), with its own scroll.
 *   - Translator: a small button beside each line; click it to translate that line into
 *     the chosen language and show the translation under the original.
 * Keeps the vanilla look (full-width dark bars, shadowed text, ~10s fade). It activates
 * whenever EITHER feature is on; each feature's own bits are gated by that feature.
 *
 * We keep our own message buffer fed by Mc.newChatLinesFormatted() (so we're not limited by
 * vanilla's trimming or its wrapped-line scroll index), word-wrap ourselves, and scroll via
 * the mouse wheel captured while a screen is open (Hook.filterEventWheel -> onWheel).
 */
public final class ChatOverlay {
    private static final int LINE_H = 9;
    private static final int BOTTOM_MARGIN = 40;
    private static final float BOX_W = 320f;
    private static final long FADE_MS = 10000;
    private static final long FADE_DUR = 1000;
    private static final float HEAD = 8f;
    private static final float BTN_W = 12f; // translate button width (beside the line)

    private static final class Group {
        String text; int count; long arrival;
        boolean wantTranslate;   // user pressed the translate button on this group
        String translated;       // cached translation (null until ready)
        boolean transFailed;     // last translate attempt failed
    }

    // our own history (oldest-first; newest at the end)
    private static final List<Group> groups = new ArrayList<Group>();
    private static int scrollRows = 0; // 0 = pinned to newest (bottom)

    // pointer state, fed each frame from Hook.renderChat (scaled GUI units, top-left origin)
    private static float pX, pY;
    private static boolean pChatOpen, pClick;

    /** Called each frame with the cursor in scaled GUI coords + a fresh left-click edge. */
    public static void setPointer(float x, float y, boolean chatOpen, boolean clickEdge) {
        pX = x; pY = y; pChatOpen = chatOpen; pClick = clickEdge;
    }

    /** Mouse-wheel scroll while a screen is open (fed from Hook.filterEventWheel). */
    public static void onWheel(int delta) {
        if (delta == 0) return;
        scrollRows += (delta > 0 ? 1 : -1) * 3; // ~3 lines per notch
        if (scrollRows < 0) scrollRows = 0;
    }

    /** Snap back to the newest line (called when the chat/screen closes). */
    public static void resetScroll() { scrollRows = 0; }

    public static void render(int sw, int sh) {
        // Which features are active? (the overlay is only invoked when at least one is on)
        Module co = Modules.get("ChatOptimize");
        boolean coOn = co != null && co.enabled;
        Module tr = Modules.get("Translator");
        boolean trOn = tr != null && tr.enabled;
        if (!coOn && !trOn) return;

        boolean compress = coOn && co.bool("compress");
        boolean heads = coOn && co.bool("heads");
        int cap = coOn ? (int) co.num("lines", 200) : 100;
        float bgA = (coOn ? co.num("opacity", 60) : 60f) / 100f;
        String lang = trOn ? Hook.translateLang() : "ja";
        boolean screenOpen = Mc.isChatOpen(); // chat input open -> expanded + no fade
        long now = System.currentTimeMillis();

        // 1) ingest new messages into our own buffer (dedup consecutive on the way in)
        List<String> incoming = Mc.newChatLinesFormatted();
        for (int i = 0; i < incoming.size(); i++) {
            String msg = incoming.get(i);
            String key = Mc.stripFormatting(msg);
            if (compress && !groups.isEmpty()
                    && Mc.stripFormatting(groups.get(groups.size() - 1).text).equals(key)) {
                Group g = groups.get(groups.size() - 1);
                g.count++; g.arrival = now;
            } else {
                Group g = new Group();
                g.text = msg; g.count = 1; g.arrival = now;
                groups.add(g);
            }
        }
        while (groups.size() > cap) groups.remove(0);
        if (groups.isEmpty()) { pClick = false; return; }

        boolean expanded = screenOpen || scrollRows > 0; // browsing -> more lines, no fade
        int maxVisible = expanded ? 20 : 10;
        float boxW = Math.min(BOX_W, sw - 6f);
        Set<String> names = heads ? Mc.onlineNames() : null;
        boolean showButtons = trOn && screenOpen; // buttons are clickable only while chat is open

        // 2) build the visible display rows (bottom-up: index 0 = newest = bottom), wrapping.
        //    Each group emits its original wrapped lines, then (if requested) its translation.
        List<Row> rows = new ArrayList<Row>();
        boolean exhausted = true;
        for (int gi = groups.size() - 1; gi >= 0; gi--) {
            if (rows.size() >= scrollRows + maxVisible + 4) { exhausted = false; break; }
            Group g = groups.get(gi);

            // resolve translation lazily while the group is flagged; once ready, REPLACE the
            // whole message with the translation (original stays until it's ready / on failure).
            String displayText = g.text;
            if (trOn && g.wantTranslate) {
                if (g.translated == null && !g.transFailed) {
                    String r = Translate.get(Mc.stripFormatting(g.text), lang);
                    if (r != null) { if (r.isEmpty()) g.transFailed = true; else g.translated = r; }
                }
                if (g.translated != null) displayText = g.translated; // replaced with the translation
            }

            // build this group's lines top->bottom, then push them bottom-up
            List<Row> gr = new ArrayList<Row>();
            List<String> ow = new ArrayList<String>();
            wrap(displayText, (int) (boxW - 4f), ow);
            for (int r = 0; r < ow.size(); r++) {
                Row row = new Row();
                row.text = ow.get(r);
                row.arrival = g.arrival;
                row.count = (r == ow.size() - 1) ? g.count : 1;
                row.sender = (heads && names != null && r == 0)
                        ? detectSender(Mc.stripFormatting(g.text), names) : null;
                row.button = showButtons && (r == ow.size() - 1); // on the group's bottom line
                row.groupIdx = gi;
                gr.add(row);
            }
            for (int r = gr.size() - 1; r >= 0; r--) rows.add(gr.get(r));
        }
        // clamp scroll so you can't scroll past the oldest line
        if (exhausted) {
            int maxScroll = Math.max(0, rows.size() - maxVisible);
            if (scrollRows > maxScroll) scrollRows = maxScroll;
        }

        // 3) draw (bottom-up)
        float yBottom = sh - BOTTOM_MARGIN;
        for (int k = 0; k < maxVisible; k++) {
            int idx = scrollRows + k;
            if (idx >= rows.size()) break;
            Row row = rows.get(idx);
            float y = yBottom - (k + 1) * LINE_H;
            if (y < 1) break;

            float alpha = 1f;
            if (!expanded) {
                long age = now - row.arrival;
                if (age > FADE_MS + FADE_DUR) continue;
                if (age > FADE_MS) alpha = 1f - (age - FADE_MS) / (float) FADE_DUR;
            }
            if (alpha <= 0.02f) continue;
            int a8 = clampA(alpha);

            Gl.alpha = alpha * bgA;
            Gl.rect(0f, y - 1f, boxW, LINE_H, 0xFF000000);
            Gl.alpha = 1f;

            float tx = 2f;
            if (row.sender != null) {
                int[] skin = Mc.headSkin(row.sender);
                if (skin != null && skin[0] > 0) { drawHead(skin[0], 0f, y - 1f, HEAD, alpha); tx = HEAD + 3f; }
            }
            Hook.chatDrawText(row.text, tx, y, (a8 << 24) | 0xFFFFFF, true);
            if (row.count > 1) {
                float bx = tx + Hook.chatTextWidth(row.text) + 4f;
                Hook.chatDrawText("×" + row.count, bx, y, (a8 << 24) | (Theme.ACCENT & 0xFFFFFF), true);
            }
            if (row.button) drawButton(row, boxW, sw, y);
        }
        Gl.alpha = 1f;
        pClick = false; // consume this frame's click edge
    }

    private static final class Row {
        String text; long arrival; String sender; int count;
        boolean button; int groupIdx;
    }

    // Draw the translate button beside a line and handle a click on it.
    private static void drawButton(Row row, float boxW, int sw, float y) {
        float bx = Math.min(boxW + 2f, sw - BTN_W - 1f);
        float by = y - 1f, bh = LINE_H;
        boolean hot = pChatOpen && pX >= bx && pX <= bx + BTN_W && pY >= by && pY <= by + bh;

        Group g = (row.groupIdx >= 0 && row.groupIdx < groups.size()) ? groups.get(row.groupIdx) : null;
        int base;
        if (g != null && g.wantTranslate) base = g.transFailed ? 0xFFAA3030 : (0xFF000000 | (Theme.ACCENT & 0xFFFFFF));
        else base = 0xFF202020;
        Gl.alpha = hot ? 0.95f : 0.7f;
        Gl.rect(bx, by, BTN_W, bh, hot ? Gl.lighten(base, 0.25f) : base);
        Gl.alpha = 1f;
        // a small "訳" glyph, centred
        String lbl = "訳";
        float lw = Hook.chatTextWidth(lbl);
        Hook.chatDrawText(lbl, bx + (BTN_W - lw) / 2f, y, 0xFFFFFFFF, true);

        if (hot && pClick && g != null) {
            g.wantTranslate = !g.wantTranslate;
            if (!g.wantTranslate) { g.translated = null; g.transFailed = false; }
            pClick = false; // one click -> one toggle
        }
    }

    // Word-wrap `s` to `maxW` px (vanilla-font metric), carrying the active § colour forward.
    private static void wrap(String s, int maxW, List<String> out) {
        if (s == null) { out.add(""); return; }
        String prefix = "";
        for (int guard = 0; guard < 64; guard++) {
            if (Hook.chatTextWidth(prefix + s) <= maxW || s.length() <= 1) { out.add(prefix + s); return; }
            int lo = 1, hi = s.length(), fit = 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (Hook.chatTextWidth(prefix + s.substring(0, mid)) <= maxW) { fit = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            if (fit >= 2 && s.charAt(fit - 1) == '§') fit--;
            int sp = s.lastIndexOf(' ', fit - 1);
            int cut = (sp > 0 && sp >= fit - 24) ? sp + 1 : fit;
            if (cut < 1) cut = fit;
            String head = s.substring(0, cut);
            out.add(prefix + head);
            prefix = lastColor(prefix + head);
            s = s.substring(cut);
            if (s.isEmpty()) return;
        }
        out.add(prefix + s);
    }

    private static String lastColor(String s) {
        String c = "";
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '§') {
                char x = Character.toLowerCase(s.charAt(i + 1));
                if ((x >= '0' && x <= '9') || (x >= 'a' && x <= 'f')) c = "§" + x;
                else if (x == 'r') c = "";
            }
        }
        return c;
    }

    private static String detectSender(String stripped, Set<String> names) {
        if (stripped == null || names.isEmpty()) return null;
        int n = stripped.length(), i = 0;
        while (i < n) {
            char c = stripped.charAt(i);
            if (isNameChar(c)) {
                int j = i;
                while (j < n && isNameChar(stripped.charAt(j))) j++;
                if (j - i >= 3 && j - i <= 16) {
                    String tok = stripped.substring(i, j);
                    if (names.contains(tok.toLowerCase())) return tok;
                }
                i = j;
            } else i++;
        }
        return null;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static void drawHead(int texId, float x, float y, float size, float alpha) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        face(x, y, size, 8f, 8f);
        face(x, y, size, 40f, 8f);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    private static void face(float x, float y, float size, float u, float v) {
        float s = 1f / 64f;
        float u0 = u * s, v0 = v * s, u1 = (u + 8f) * s, v1 = (v + 8f) * s;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x, y + size);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x + size, y + size);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x + size, y);
        GL11.glEnd();
    }

    private static int clampA(float alpha) {
        int a = (int) (alpha * 255f);
        if (a < 4) a = 4;
        if (a > 255) a = 255;
        return a;
    }
}
