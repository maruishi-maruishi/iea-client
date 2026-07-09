package dev.iea.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import dev.iea.client.Config;
import dev.iea.client.Lang;
import dev.iea.client.Theme;
import dev.iea.client.hud.HudManager;
import dev.iea.client.module.Module;
import dev.iea.client.module.Modules;
import dev.iea.client.module.Setting;
import dev.iea.client.render.Font;
import dev.iea.client.render.Gl;
import dev.iea.client.render.Logo;

/**
 * Launcher-style in-game window: a left sidebar (Modules / Theme / Settings / Edit HUD)
 * over a dimmed, grid-backed game. The Modules section is a category-filtered grid of
 * tiles; each tile's gear opens a per-module page (settings + a toggle keybind + an
 * optional custom accent colour). Theme edits the global accent; Settings holds the
 * client-wide preferences (font, menu skin, dynamic FPS, language).
 */
public final class ClickGui {
    public enum State { CLOSED, OPEN, MODULE_CFG, HUD_EDIT }
    private enum Nav { MODS, THEME, SETTINGS }

    private State state = State.CLOSED;
    private Nav nav = Nav.MODS;
    private int tab = 0;
    private String search = ""; // module search filter (typed in the Modules grid)
    private Module selected;
    private boolean prevDown = false;
    private Setting draggingSlider;
    private int draggingCh = -1;   // 0/1/2 = R/G/B of selected.color while dragging its swatch
    private Setting listening;      // a KEY setting waiting for a key press
    private boolean listeningToggle; // selected's module toggle-key waiting for a key press
    private final HudManager hud;

    public boolean isBinding() { return listening != null || listeningToggle; }

    private final String[] tabCats = { null, "HUD", "Input", "Render", "Player", "Chat" };

    private String tabLabel(int i) {
        String c = tabCats[i];
        if (c == null) return Lang.t("tab_all");
        if (c.equals("Input")) return Lang.t("cat_input");
        if (c.equals("Render")) return Lang.t("cat_render");
        if (c.equals("Player")) return Lang.t("cat_player");
        if (c.equals("Chat")) return Lang.t("cat_chat");
        return Lang.t("cat_hud");
    }

    public ClickGui(HudManager hud) { this.hud = hud; }

    public boolean isOpen() { return state != State.CLOSED; }

    // latest HUD data, so the layout editor can show the real elements (not just outlines)
    private Font hudFontRef;
    private Logo logoRef;
    private int fpsRef, cpsLRef, cpsRRef;
    public void setHudData(Font hudFont, Logo logo, int fps, int cpsL, int cpsR) {
        this.hudFontRef = hudFont; this.logoRef = logo;
        this.fpsRef = fps; this.cpsLRef = cpsL; this.cpsRRef = cpsR;
    }

    public void onToggleKey() {
        if (state == State.CLOSED) {
            state = State.OPEN;
            Anim.set("open", 0f);   // animate in from 0
            Anim.resetClock();      // so the first frame's dt is one frame, not the idle gap
        } else { close(); }
    }

    private void close() {
        state = State.CLOSED; search = ""; listening = null; listeningToggle = false;
        draggingSlider = null; draggingCh = -1; Config.save(hud);
    }

    public void onEscape() {
        if (isBinding()) { listening = null; listeningToggle = false; return; }
        if (state == State.MODULE_CFG) state = State.OPEN;
        else if (state == State.HUD_EDIT) state = State.OPEN;
        else if (state != State.CLOSED) close();
    }

    // typed-character input, fed from the menu's key-event drain (Hook.onFrame). Drives the
    // module search box — only in the Modules grid, and never while binding a key.
    public void onCharTyped(char c, int key) {
        if (state != State.OPEN || nav != Nav.MODS || isBinding()) return;
        if (key == Keyboard.KEY_BACK) {
            if (search.length() > 0) search = search.substring(0, search.length() - 1);
        } else if (c >= 32 && c != 127 && search.length() < 24) {
            search += c;
        }
    }

    private float gridScroll = 0;
    private int wheel = 0;

    public void render(Font row, Font title, Font big, Logo logo, int mx, int my, boolean down, int scroll) {
        if (state == State.CLOSED) return;
        Anim.tick();
        int w = Display.getWidth(), h = Display.getHeight();
        boolean clicked = down && !prevDown;
        prevDown = down;
        this.wheel = scroll;

        // capture a key for keybind settings (module setting KEY, or the module toggle key)
        if (listening != null) {
            for (int k = 1; k < 256; k++) {
                if (Keyboard.isKeyDown(k)) {
                    if (k != Keyboard.KEY_ESCAPE) listening.keyCode = k;
                    listening = null;
                    break;
                }
            }
        } else if (listeningToggle && selected != null) {
            for (int k = 1; k < 256; k++) {
                if (Keyboard.isKeyDown(k)) {
                    selected.toggleKey = (k == Keyboard.KEY_ESCAPE) ? 0 : k; // ESC = unbind
                    listeningToggle = false;
                    break;
                }
            }
        }

        if (state == State.HUD_EDIT) {
            renderEdit(row, title, w, h, mx, my, down, clicked);
            return;
        }

        // open transition: fade + an upward slide (frame-rate independent). Cubic ease-out
        // and a bigger travel make it clearly read as an animation.
        float open = Anim.to("open", 1f, 13f);
        float ease = 1f - (float) Math.pow(1f - open, 3); // cubic ease-out
        Gl.alpha = ease;

        // dim the game with the launcher's deep base + faint geometric grid
        Gl.rect(0, 0, w, h, Theme.BACKDROP);
        Gl.grid(0, 0, w, h, 24, Theme.GRID);

        float pw = Math.min(1040, w - 32), ph = Math.min(680, h - 32);
        float px = (w - pw) / 2f, py = (h - ph) / 2f + (1f - ease) * 30f;

        // window = launcher content surface (#0e0f14) on a hairline border
        card(px, py, pw, ph, 14, Theme.CONTENT, Theme.BORDER);

        // ---- left sidebar (launcher chrome) ----
        float m = 12, sbw = 170;
        float sbx = px + m, sby = py + m, sbh = ph - m * 2;
        renderSidebar(row, title, logo, sbx, sby, sbw, sbh, mx, my, clicked);

        // ---- content area ----
        float cox = sbx + sbw + m, coy = py + m;
        float cow = (px + pw - m) - cox, coh = ph - m * 2;

        if (state == State.MODULE_CFG) {
            renderModuleCfg(row, title, big, cox, coy, cow, coh, mx, my, down, clicked);
        } else if (nav == Nav.MODS) {
            renderMods(row, title, cox, coy, cow, coh, mx, my, clicked);
        } else if (nav == Nav.THEME) {
            renderTheme(row, title, big, cox, coy, cow, coh, mx, my, down, clicked);
        } else {
            renderSettingsTab(row, title, big, cox, coy, cow, coh, mx, my, clicked);
        }

        if (!down) { draggingSlider = null; draggingCh = -1; }
        Gl.alpha = 1f; // restore master alpha after the open-fade
    }

    // ---------------- sidebar ----------------
    private void renderSidebar(Font row, Font title, Logo logo, float sx, float sy, float sw, float sh,
                               int mx, int my, boolean clicked) {
        card(sx, sy, sw, sh, 12, Theme.PANEL, Theme.BORDER);
        float pad = 14;
        // badge: lime IEA square + wordmark
        float badge = 30;
        logo.draw(sx + pad, sy + pad, badge);
        title.drawVMid("IEA CLIENT", sx + pad + badge + 10, sy + pad + badge / 2f, Theme.TEXT);
        float divY = sy + pad + badge + pad;
        Gl.rect(sx + pad, divY, sw - pad * 2, 1, Theme.BORDER);

        // nav items
        float ny = divY + pad, nh = 40, ngap = 6, nw = sw - pad * 2;
        boolean onTab = state == State.OPEN; // MODULE_CFG / HUD_EDIT are not a nav section
        if (navItem(sx + pad, ny, nw, nh, Lang.t("nav_mods"), onTab && nav == Nav.MODS,
                mx, my, title) && clicked) { nav = Nav.MODS; state = State.OPEN; }
        ny += nh + ngap;
        if (navItem(sx + pad, ny, nw, nh, Lang.t("nav_theme"), onTab && nav == Nav.THEME,
                mx, my, title) && clicked) { nav = Nav.THEME; search = ""; state = State.OPEN; }
        ny += nh + ngap;
        if (navItem(sx + pad, ny, nw, nh, Lang.t("nav_settings"), onTab && nav == Nav.SETTINGS,
                mx, my, title) && clicked) { nav = Nav.SETTINGS; search = ""; state = State.OPEN; }
        ny += nh + ngap;
        if (navItem(sx + pad, ny, nw, nh, Lang.t("edit_hud"), state == State.HUD_EDIT, mx, my, title) && clicked) {
            state = State.HUD_EDIT;
        }

        // footer: enabled-module count
        int on = 0;
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module mm = Modules.ALL.get(i);
            if (mm.enabled && !mm.hidden) on++;
        }
        float fcy = sy + sh - pad - row.getHeight() / 2f;
        Gl.roundedRect(sx + pad, fcy - 2.5f, 5, 5, 2.5f, Theme.ACCENT);
        row.drawVMid(Lang.t("nav_mods") + ": " + on, sx + pad + 10, fcy, Theme.MUTED);
    }

    private boolean navItem(float x, float y, float w, float h, String label, boolean active,
                            int mx, int my, Font f) {
        boolean hover = inside(mx, my, x, y, w, h);
        float hv = Anim.to("nav" + label, (hover || active) ? 1f : 0f, 22f);
        float av = Anim.to("navc" + label, active ? 1f : 0f, 18f);
        if (hv > 0.01f)
            card(x, y, w, h, 9, Gl.lerp(Theme.PANEL, Theme.PANEL2, hv),
                    Gl.lerp(Theme.BORDER, Theme.accentA(0x44), av));
        // snap the text colour (no green<->grey fade — that transition read as a colour bug)
        f.drawVMid(label, x + 14, y + h / 2f, active ? Theme.ACCENT : Theme.MUTED);
        return hover;
    }

    // ---------------- Modules grid ----------------
    private void renderMods(Font row, Font title, float cx, float cy, float cw, float ch,
                            int mx, int my, boolean clicked) {
        // category tabs
        float tx = cx, ty = cy;
        for (int i = 0; i < tabCats.length; i++) {
            String label = tabLabel(i);
            float tw = title.getWidth(label) + 28;
            boolean hover = inside(mx, my, tx, ty, tw, 34);
            boolean sel = tab == i;
            float sv = Anim.to("tabsel" + i, sel ? 1f : 0f, 26f);
            float hv = Anim.to("tabhov" + i, hover ? 1f : 0f, 28f);
            int fill = Gl.lerp(Theme.ROW, Theme.PANEL2, Math.max(hv, sv));
            card(tx, ty, tw, 34, 9, fill, Gl.lerp(Theme.BORDER, Theme.accentA(0x55), sv));
            // snap the label colour (green when selected, muted otherwise — no near-white fade)
            title.drawCentered(label, tx + tw / 2f, ty + 17, sel ? Theme.ACCENT : Theme.MUTED);
            if (sv > 0.01f) {
                float uw = tw * 0.62f * sv;
                Gl.roundedRect(tx + tw / 2f - uw / 2f, ty + 34 - 3f, uw, 2.5f, 1.25f, Theme.ACCENT);
            }
            if (hover && clicked) { tab = i; search = ""; }
            tx += tw + 8;
        }

        // search box (own row under the tabs, right-aligned)
        float shW = Math.min(220, cw), shH = 32, shX = cx + cw - shW, shY = ty + 42;
        boolean shHover = inside(mx, my, shX, shY, shW, shH);
        card(shX, shY, shW, shH, 9, shHover ? Theme.PANEL2 : Theme.ROW,
                search.isEmpty() ? Theme.BORDER : Theme.ACCENT);
        float stx = shX + 12, stcy = shY + shH / 2f;
        row.drawVMid(search.isEmpty() ? Lang.t("search") : search, stx, stcy,
                search.isEmpty() ? Theme.MUTED : Theme.TEXT);
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            float cxr = stx + (search.isEmpty() ? 0 : row.getWidth(search)) + 1;
            Gl.rect(cxr, shY + 7, 1.5f, shH - 14, Theme.ACCENT);
        }
        if (shHover && clicked && !search.isEmpty()) search = "";

        // grid of tiles (scrollable)
        List<Module> list = filtered();
        float gx = cx, gy = shY + shH + 12, gw = cw;
        int cols = 3;
        float gap = 12, tileW = (gw - (cols - 1) * gap) / cols, tileH = 96;

        float pad0 = 4f;
        int rows = (list.size() + cols - 1) / cols;
        float visibleH = (cy + ch) - gy;
        float totalH = rows * (tileH + gap) + pad0;
        float maxScroll = Math.max(0, totalH - visibleH);
        gridScroll = clampF(gridScroll - wheel * 0.4f, 0, maxScroll);

        int scH = Display.getHeight();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int) gx, (int) (scH - (gy + visibleH)), (int) gw, (int) visibleH);

        for (int i = 0; i < list.size(); i++) {
            Module mo = list.get(i);
            float tcx = gx + (i % cols) * (tileW + gap);
            float cyBase = gy + pad0 + (i / cols) * (tileH + gap) - gridScroll;
            boolean hover = inside(mx, my, tcx, cyBase, tileW, tileH) && my >= gy && my <= gy + visibleH;
            float hv = Anim.to("tile" + mo.name, hover ? 1f : 0f, 16f);
            float ea = Anim.to("en" + mo.name, mo.enabled ? 1f : 0f, 14f);
            int acc = mo.accent();
            int fill = Gl.lerp(Theme.ROW, Theme.PANEL2, Math.max(hv, ea * 0.5f));
            int border = Gl.lerp(Theme.BORDER, (0x66 << 24) | (acc & 0xFFFFFF), ea);
            card(tcx, cyBase, tileW, tileH, 12, fill, border);

            title.drawCentered(mo.name, tcx + tileW / 2f, cyBase + 40, Gl.lerp(Theme.TEXT, acc, ea));
            String st = mo.enabled ? Lang.t("on") : Lang.t("off");
            float scx = tcx + tileW / 2f, stY = cyBase + tileH - 26;
            float dotX = scx - row.getWidth(st) / 2f - 11;
            Gl.roundedRect(dotX, stY + row.getHeight() / 2f - 2.5f, 5, 5, 2.5f, Gl.lerp(Theme.MUTED, acc, ea));
            row.drawCentered(st, scx + 4, stY, Gl.lerp(Theme.MUTED, acc, ea));

            boolean gearHover = inside(mx, my, tcx + tileW - 42, cyBase + 6, 32, 24);
            kebab(tcx + tileW - 26, cyBase + 18, gearHover ? acc : Theme.MUTED);
            if (clicked && hover) {
                if (gearHover) { selected = mo; state = State.MODULE_CFG; setScroll = 0; }
                else mo.enabled = !mo.enabled;
            }
        }
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        if (maxScroll > 0) {
            float trackH = visibleH * (visibleH / totalH);
            float trackY = gy + (visibleH - trackH) * (gridScroll / maxScroll);
            Gl.roundedRect(cx + cw + 4, trackY, 3, trackH, 1.5f, Theme.ACCENT);
        }
    }

    // ---------------- Theme tab ----------------
    private void renderTheme(Font row, Font title, Font big, float cx, float cy, float cw, float ch,
                             int mx, int my, boolean down, boolean clicked) {
        Module tm = Modules.get("Theme");
        if (tm == null) return;
        Setting sr = tm.get("r"), sg = tm.get("g"), sb = tm.get("b");
        big.draw(Lang.t("nav_theme"), cx, cy, Theme.TEXT);
        float y = cy + big.getHeight() + 6;
        y = drawWrapped(Lang.t("d.theme"), cx, y, cw, row, Theme.MUTED) + 10;

        float rowH = 44, gap = 8;

        // enable the custom theme (off = default lime); onFrame reads Theme.enabled
        settingRow(cx, y, cw, rowH, Lang.t("set_theme_on"), title);
        toggle(cx + cw - 70, y + (rowH - 28) / 2f, tm.enabled, "themeOn");
        if (clicked && inside(mx, my, cx, y, cw, rowH)) tm.enabled = !tm.enabled;
        y += rowH + gap;

        // live swatch preview
        int col = 0xFF000000 | (((int) sr.num & 0xFF) << 16) | (((int) sg.num & 0xFF) << 8) | ((int) sb.num & 0xFF);
        card(cx, y, cw, 44, 10, col, Theme.BORDER);
        y += 44 + 14;

        // R / G / B sliders
        Setting[] chans = { sr, sg, sb };
        for (int i = 0; i < 3; i++) {
            settingRow(cx, y, cw, rowH, Lang.t(chans[i].name), title);
            slider(chans[i], cx + cw - 176, y + rowH / 2f, 110, mx, my, down, row);
            y += rowH + gap;
        }

        // preset chips (quick colours + default lime)
        int[] presets = { 0xA3E635, 0x4FC3F7, 0xE05070, 0xB07CFF, 0xFFC24B, 0xE7E9EE };
        float chip = (cw - 5 * 8) / 6f, chH = 30;
        for (int i = 0; i < presets.length; i++) {
            float chx = cx + i * (chip + 8);
            boolean hov = inside(mx, my, chx, y, chip, chH);
            card(chx, y, chip, chH, 8, 0xFF000000 | presets[i], hov ? Theme.ACCENT : Theme.BORDER);
            if (hov && clicked) {
                sr.num = (presets[i] >> 16) & 0xFF; sg.num = (presets[i] >> 8) & 0xFF; sb.num = presets[i] & 0xFF;
            }
        }
    }

    // ---------------- Settings tab ----------------
    private void renderSettingsTab(Font row, Font title, Font big, float cx, float cy, float cw, float ch,
                                   int mx, int my, boolean clicked) {
        big.draw(Lang.t("nav_settings"), cx, cy, Theme.TEXT);
        float y = cy + big.getHeight() + 10, rowH = 46, gap = 8;

        String[][] toggles = {
            { "IEAFont", "set_ieafont" }, { "IEAGui", "set_ieagui" }, { "DynamicFps", "set_dynfps" } };
        for (int i = 0; i < toggles.length; i++) {
            Module mo = Modules.get(toggles[i][0]);
            if (mo == null) continue;
            settingRow(cx, y, cw, rowH, Lang.t(toggles[i][1]), title);
            toggle(cx + cw - 70, y + (rowH - 28) / 2f, mo.enabled, "st" + mo.name);
            if (clicked && inside(mx, my, cx, y, cw, rowH)) mo.enabled = !mo.enabled;
            y += rowH + gap;
        }

        // language switch (< current >)
        settingRow(cx, y, cw, rowH, Lang.t("set_language"), title);
        float kw = 150, kx = cx + cw - kw - 16, kyy = y + (rowH - 28) / 2f;
        boolean leftHalf = inside(mx, my, kx, kyy, kw / 2f, 28);
        boolean rightHalf = inside(mx, my, kx + kw / 2f, kyy, kw / 2f, 28);
        card(kx, kyy, kw, 28, 8, (leftHalf || rightHalf) ? Theme.PANEL2 : Theme.ROW, Theme.BORDER);
        row.drawCentered("<", kx + 12, kyy + 14, leftHalf ? Theme.ACCENT : Theme.MUTED);
        row.drawCentered(">", kx + kw - 12, kyy + 14, rightHalf ? Theme.ACCENT : Theme.MUTED);
        row.drawCentered("ja".equals(Lang.current) ? "日本語" : "English", kx + kw / 2f, kyy + 14, Theme.TEXT);
        if (clicked && (leftHalf || rightHalf)) Lang.toggle();
    }

    // ---------------- per-module page ----------------
    private float setScroll = 0, setContentH = 0;

    private void renderModuleCfg(Font row, Font title, Font big, float cx, float cy, float cw, float ch,
                                 int mx, int my, boolean down, boolean clicked) {
        float bx = cx;
        if (button(bx, cy, 90, 32, "← " + Lang.t("back"), title, false, mx, my) && clicked) {
            state = State.OPEN;
            return;
        }
        if (selected == null) return;
        float rw = cw;

        float top = cy + 44, bottom = cy + ch, visibleH = bottom - top;
        float maxScroll = Math.max(0, setContentH - visibleH);
        setScroll = clampF(setScroll - wheel * 0.4f, 0, maxScroll);
        boolean canClick = clicked && my >= top && my <= bottom;

        int scH = Display.getHeight();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int) cx, (int) (scH - bottom), (int) cw, (int) visibleH);

        float yo = top - setScroll;
        big.draw(selected.name, bx, yo, Theme.TEXT);
        float cyy = yo + big.getHeight() + 4;
        row.draw(Lang.t("category") + ": " + selected.category, bx + 2, cyy, Theme.MUTED);
        float dy = cyy + row.getHeight() + 6;
        if (selected.descKey != null)
            dy = drawWrapped(Lang.t(selected.descKey), bx + 2, dy, rw - 4, row, Theme.MUTED) + 6;

        float rowH = 44, gap = 6, ry = dy + 2;

        // enable
        settingRow(bx, ry, rw, rowH, Lang.t("enabled"), title);
        toggle(bx + rw - 70, ry + (rowH - 28) / 2f, selected.enabled, "tgEn" + selected.name);
        if (canClick && inside(mx, my, bx, ry, rw, rowH)) selected.enabled = !selected.enabled;
        ry += rowH + gap;

        // per-module settings
        for (int i = 0; i < selected.settings.size(); i++) {
            Setting s = selected.settings.get(i);
            settingRow(bx, ry, rw, rowH, Lang.t(s.name), title);
            if (s.type == Setting.BOOL) {
                toggle(bx + rw - 70, ry + (rowH - 28) / 2f, s.bool, s);
                if (canClick && inside(mx, my, bx, ry, rw, rowH)) s.bool = !s.bool;
            } else if (s.type == Setting.KEY) {
                float kw = 110, kx = bx + rw - kw - 16, kyy = ry + (rowH - 28) / 2f;
                boolean kh = inside(mx, my, kx, kyy, kw, 28);
                String label = (listening == s) ? Lang.t("press_key") : keyName(s.keyCode);
                card(kx, kyy, kw, 28, 8, kh ? Theme.PANEL2 : Theme.ROW,
                        listening == s ? Theme.ACCENT : Theme.BORDER);
                row.drawCentered(label, kx + kw / 2f, kyy + 14, listening == s ? Theme.ACCENT : Theme.TEXT);
                if (kh && canClick) { listening = s; listeningToggle = false; }
            } else if (s.type == Setting.MODE) {
                float kw = 150, kx = bx + rw - kw - 16, kyy = ry + (rowH - 28) / 2f;
                int n = s.options.length, idx = Math.max(0, Math.min(n - 1, (int) s.num));
                boolean leftHalf = inside(mx, my, kx, kyy, kw / 2f, 28);
                boolean rightHalf = inside(mx, my, kx + kw / 2f, kyy, kw / 2f, 28);
                card(kx, kyy, kw, 28, 8, (leftHalf || rightHalf) ? Theme.PANEL2 : Theme.ROW, Theme.BORDER);
                row.drawCentered("<", kx + 12, kyy + 14, leftHalf ? Theme.ACCENT : Theme.MUTED);
                row.drawCentered(">", kx + kw - 12, kyy + 14, rightHalf ? Theme.ACCENT : Theme.MUTED);
                row.drawCentered(Lang.t(s.options[idx]), kx + kw / 2f, kyy + 14, Theme.TEXT);
                if (canClick && leftHalf) s.num = (idx - 1 + n) % n;
                else if (canClick && rightHalf) s.num = (idx + 1) % n;
            } else {
                slider(s, bx + rw - 196, ry + rowH / 2f, 110, mx, my, down, row);
            }
            ry += rowH + gap;
        }

        // toggle keybind
        settingRow(bx, ry, rw, rowH, Lang.t("cfg_bind"), title);
        {
            float kw = 110, kx = bx + rw - kw - 16, kyy = ry + (rowH - 28) / 2f;
            boolean kh = inside(mx, my, kx, kyy, kw, 28);
            String label = listeningToggle ? Lang.t("press_key")
                    : (selected.toggleKey > 0 ? keyName(selected.toggleKey) : Lang.t("cfg_none"));
            card(kx, kyy, kw, 28, 8, kh ? Theme.PANEL2 : Theme.ROW,
                    listeningToggle ? Theme.ACCENT : Theme.BORDER);
            row.drawCentered(label, kx + kw / 2f, kyy + 14, listeningToggle ? Theme.ACCENT : Theme.TEXT);
            if (kh && canClick) { listeningToggle = true; listening = null; }
        }
        ry += rowH + gap;

        // custom colour toggle + channels
        settingRow(bx, ry, rw, rowH, Lang.t("cfg_customcolor"), title);
        toggle(bx + rw - 70, ry + (rowH - 28) / 2f, selected.customColor, "cc" + selected.name);
        if (canClick && inside(mx, my, bx, ry, rw, rowH)) selected.customColor = !selected.customColor;
        ry += rowH + gap;
        if (selected.customColor) {
            card(bx, ry, rw, 30, 8, 0xFF000000 | (selected.color & 0xFFFFFF), Theme.BORDER); // swatch
            ry += 30 + gap;
            for (int ch3 = 0; ch3 < 3; ch3++) {
                int shift = ch3 == 0 ? 16 : ch3 == 1 ? 8 : 0;
                String lbl = ch3 == 0 ? Lang.t("s.red") : ch3 == 1 ? Lang.t("s.green") : Lang.t("s.blue");
                settingRow(bx, ry, rw, rowH, lbl, title);
                colorSlider(ch3, bx + rw - 176, ry + rowH / 2f, 110, mx, my, down, row);
                ry += rowH + gap;
            }
        }

        // reset position (only for HUD elements)
        if (hud.has(selected.name)) {
            if (button(bx, ry, rw, 44, Lang.t("reset_pos"), title, false, mx, my) && canClick)
                hud.resetOne(selected.name);
            row.draw(Lang.t("note_pos"), bx + 2, ry + 58, Theme.MUTED);
            ry += 58 + row.getHeight();
        }
        setContentH = (ry + 8) - yo;

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        if (maxScroll > 0) {
            float trackH = visibleH * (visibleH / setContentH);
            float trackY = top + (visibleH - trackH) * (setScroll / maxScroll);
            Gl.roundedRect(cx + cw + 4, trackY, 3, trackH, 1.5f, Theme.ACCENT);
        }
    }

    private static float clampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // word/char-wrapped paragraph; returns the y just below the last line
    private float drawWrapped(String text, float x, float y, float maxW, Font f, int color) {
        float lh = f.getHeight() + 3;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { f.draw(line.toString(), x, y, color); y += lh; line.setLength(0); continue; }
            line.append(c);
            if (f.getWidth(line.toString()) > maxW && line.length() > 1) {
                line.deleteCharAt(line.length() - 1);
                f.draw(line.toString(), x, y, color); y += lh;
                line.setLength(0); line.append(c);
            }
        }
        if (line.length() > 0) { f.draw(line.toString(), x, y, color); y += lh; }
        return y;
    }

    private void settingRow(float x, float y, float w, float h, String label, Font title) {
        card(x, y, w, h, 10, Theme.ROW, Theme.BORDER);
        title.drawVMid(label, x + 18, y + h / 2f, Theme.TEXT);
    }

    private void slider(Setting s, float x, float cy, float w, int mx, int my, boolean down, Font font) {
        float h = 6, y = cy - h / 2f;
        if (down && draggingSlider == null && draggingCh < 0 && inside(mx, my, x - 4, cy - 12, w + 8, 24))
            draggingSlider = s;
        if (draggingSlider == s) {
            float t = Math.max(0, Math.min(1, (mx - x) / w));
            float v = s.min + t * (s.max - s.min);
            v = Math.round(v / s.step) * s.step;
            s.num = Math.max(s.min, Math.min(s.max, v));
        }
        float frac = (s.num - s.min) / (s.max - s.min);
        Gl.roundedRect(x, y, w, h, h / 2f, Theme.TRACK);
        Gl.roundedRect(x, y, w * frac, h, h / 2f, Theme.ACCENT2);
        float kx = x + w * frac;
        Gl.roundedRect(kx - 7, cy - 7, 14, 14, 7, Theme.ACCENT);
        String val = (s.step >= 1) ? String.valueOf((int) s.num) : String.format("%.2f", s.num);
        font.drawCentered(val, x + w + 30, cy, Theme.TEXT);
    }

    // R/G/B channel slider for the selected module's custom colour
    private void colorSlider(int ch, float x, float cy, float w, int mx, int my, boolean down, Font font) {
        int shift = ch == 0 ? 16 : ch == 1 ? 8 : 0;
        int val = (selected.color >> shift) & 0xFF;
        float h = 6, y = cy - h / 2f;
        if (down && draggingSlider == null && draggingCh < 0 && inside(mx, my, x - 4, cy - 12, w + 8, 24))
            draggingCh = ch;
        if (draggingCh == ch) {
            float t = Math.max(0, Math.min(1, (mx - x) / w));
            val = Math.round(t * 255);
            selected.color = (selected.color & ~(0xFF << shift)) | (val << shift);
        }
        float frac = val / 255f;
        int chCol = ch == 0 ? 0xFFE05050 : ch == 1 ? 0xFF5FD07A : 0xFF5A8BE0;
        Gl.roundedRect(x, y, w, h, h / 2f, Theme.TRACK);
        Gl.roundedRect(x, y, w * frac, h, h / 2f, chCol);
        Gl.roundedRect(x + w * frac - 7, cy - 7, 14, 14, 7, chCol);
        font.drawCentered(String.valueOf(val), x + w + 26, cy, Theme.TEXT);
    }

    private List<Module> filtered() {
        List<Module> out = new ArrayList<Module>();
        String q = search.toLowerCase();
        if (!q.isEmpty()) {
            for (int i = 0; i < Modules.ALL.size(); i++) {
                Module m = Modules.ALL.get(i);
                if (!m.hidden && m.name.toLowerCase().contains(q)) out.add(m);
            }
            return out;
        }
        String cat = tabCats[tab];
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module m = Modules.ALL.get(i);
            if (m.hidden) continue;
            if (cat == null || m.category.equals(cat)) out.add(m);
        }
        return out;
    }

    // ---------------- HUD layout editor ----------------
    private void renderEdit(Font row, Font title, int w, int h, int mx, int my, boolean down, boolean clicked) {
        // same deep + geometric-grid backdrop as the window, so the two screens match
        Gl.rect(0, 0, w, h, 0x99000000);
        Gl.grid(0, 0, w, h, 24, Theme.GRID);
        hud.editDrag(mx, my, down, clicked);
        // preview the HUD with obvious sample data (not your real stats), so layout is
        // clear regardless of whether you're in a world / on a server
        if (hudFontRef != null) {
            HudManager.demo = true;
            try { hud.render(hudFontRef, logoRef, 240, 8, 6); }
            finally { HudManager.demo = false; }
        }
        hud.drawOutlines(row);
        String hint = Lang.t("edit_hud") + "   " + Lang.t("hud_hint") + "   ·   " + Lang.t("hud_demo");
        float tw = title.getWidth(hint), bw = tw + 44, bx = (w - bw) / 2f;
        card(bx, 16, bw, 36, 12, 0xF214161D, Theme.accentA(0x66));
        title.draw(hint, bx + 22, 16 + (36 - title.getHeight()) / 2f, Theme.ACCENT);
    }

    // ---------------- helpers ----------------
    private void card(float x, float y, float w, float h, float r, int fill, int border) {
        Gl.roundedRect(x, y, w, h, r, fill);
        Gl.roundedOutline(x, y, w, h, r, 1.2f, border);
    }

    private boolean button(float x, float y, float w, float h, String label, Font f, boolean primary, int mx, int my) {
        boolean hover = inside(mx, my, x, y, w, h);
        float hv = Anim.to("btn" + label, hover ? 1f : 0f, 16f);
        int fill = primary ? Gl.lerp(Theme.accentA(0x33), Theme.ACCENT2, hv) : Gl.lerp(Theme.ROW, Theme.PANEL2, hv);
        card(x, y, w, h, 10, fill, primary ? Theme.accentA(0x66) : Theme.BORDER);
        int col = primary ? Gl.lerp(Theme.ACCENT, Theme.DARK, hv) : Theme.TEXT;
        f.drawCentered(label, x + w / 2f, y + h / 2f, col);
        return hover;
    }

    private void toggle(float x, float y, boolean on, Object key) {
        float tw = 50, th = 28;
        float p = Anim.to(key, on ? 1f : 0f, 16f);
        Gl.roundedRect(x, y, tw, th, th / 2f, Gl.lerp(Theme.TRACK, Theme.ACCENT2, p));
        float kn = th - 8;
        float kx = (x + 4) + (tw - kn - 8) * p;
        Gl.roundedRect(kx, y + 4, kn, kn, kn / 2f, Gl.lerp(Theme.MUTED, Theme.ACCENT, p));
    }

    private void kebab(float cx, float cy, int color) {
        for (int i = -1; i <= 1; i++) Gl.roundedRect(cx - 2 + i * 7, cy - 2, 4, 4, 2, color);
    }

    private static String keyName(int code) {
        if (code <= 0) return "NONE";
        String n = Keyboard.getKeyName(code);
        return n != null ? n : ("KEY" + code);
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
