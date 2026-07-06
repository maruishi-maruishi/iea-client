package dev.iea.client.render;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import dev.iea.client.Mc;

/**
 * Clean-room "custom sky" (MCPatcher / OptiFine resource-pack sky format). Reads the PUBLIC
 * file format (assets/minecraft/mcpatcher/sky/world0/skyN.properties + a source image)
 * straight from the selected resource pack on disk.
 *
 * Day/night: we do NOT replace the vanilla sky — the renderSky hook injects at method EXIT,
 * so vanilla draws its time-of-day gradient + sun/moon first and our layers are OVERLAID on
 * top (like OptiFine). Each layer fades in/out by time of day (startFadeIn..endFadeOut) and
 * the dome rotates once per day with the celestial angle, so the sky visibly changes from
 * day to night. An earlier version replaced vanilla with one static opaque box, which is why
 * nothing changed with time.
 *
 * Source image: a 3x2 grid of six 90-degree views (tile N at u=(N%3)/3, v=(N/3)/2, each
 * 1/3 x 1/2): 0=below, 1=above, 2..5 the four horizontal directions.
 */
public final class Sky {
    private static final int BLEND_ADD = 0, BLEND_ALPHA = 1, BLEND_SUBTRACT = 2,
            BLEND_MULTIPLY = 3, BLEND_REPLACE = 4, BLEND_SCREEN = 5,
            BLEND_BURN = 6, BLEND_DODGE = 7;

    private static final class Layer {
        int tex;
        int blend = BLEND_ADD;
        boolean rotate = true;
        float speed = 1f;
        float ax = 1f, ay = 0f, az = 0f; // rotation axis (normalized); default east-west
        boolean hasFade;
        int sIn, eIn, sOut, eOut; // fade markers, ticks 0..24000
    }

    private static boolean tried = false;
    private static boolean ready = false;
    private static final List<Layer> layers = new ArrayList<Layer>();
    private static String loadedSig = null; // selected-pack signature the current sky came from
    private static int reloadTick = 0;

    /** Lazily load on the render thread (GL context present); cached after the first try. */
    public static boolean isReady() {
        if (!tried) {
            tried = true;
            GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
            try { load(); }
            catch (Throwable t) { System.out.println("[IEA] CustomSky load error: " + t); }
            finally { GL11.glPopAttrib(); }
        }
        return ready;
    }

    private static String pendingSig = null;

    /** Detect an in-game resource-pack change and rebuild the sky from the new selection.
     *  Called (throttled) from the render hook; must run on the GL thread (deletes textures).
     *  Never interferes with the initial load (returns until isReady() has loaded once), and
     *  only acts on a change confirmed across two checks so a mid-write options.txt read
     *  can't strand the sky. */
    public static void maybeReload() {
        if (reloadTick++ % 40 != 0) return; // ~1-2x/sec is plenty
        if (loadedSig == null) return;      // initial load not done yet -> leave it to isReady()
        String sig = packsSignature();
        if (sig.equals(loadedSig)) { pendingSig = null; return; } // unchanged
        if (!sig.equals(pendingSig)) { pendingSig = sig; return; } // wait for a stable 2nd read
        // confirmed pack change -> dispose and rebuild right here
        for (int i = 0; i < layers.size(); i++)
            if (layers.get(i).tex != 0) GL11.glDeleteTextures(layers.get(i).tex);
        layers.clear();
        ready = false;
        dbgSig = null;
        pendingSig = null;
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
        try { load(); }
        catch (Throwable t) { System.out.println("[IEA] CustomSky reload error: " + t); }
        finally { GL11.glPopAttrib(); }
        System.out.println("[IEA] CustomSky: resource pack changed -> reloaded, "
                + layers.size() + " layer(s)");
    }

    private static String packsSignature() {
        return selectedPacks(new File(System.getProperty("user.dir", "."))).toString();
    }

    private static void load() {
        File gameDir = new File(System.getProperty("user.dir", "."));
        File packsDir = new File(gameDir, "resourcepacks");
        List<String> packs = selectedPacks(gameDir);
        loadedSig = packs.toString(); // remember what selection this sky was built from
        // highest-priority pack is last in the options list; check those first, stop at the
        // first pack that provides a sky (a lower pack shouldn't override a higher one)
        for (int i = packs.size() - 1; i >= 0 && layers.isEmpty(); i--) {
            File pack = new File(packsDir, packs.get(i));
            // sky layers are numbered sky1, sky2, ...; scan a generous range, allow gaps
            for (int n = 1; n <= 32; n++) {
                byte[] props = readPack(pack, "assets/minecraft/mcpatcher/sky/world0/sky" + n + ".properties");
                if (props == null) continue;
                Layer L = parseLayer(pack, props);
                if (L != null) layers.add(L);
            }
            if (!layers.isEmpty()) {
                ready = true;
                System.out.println("[IEA] CustomSky loaded " + layers.size()
                        + " layer(s) from pack: " + packs.get(i));
                return;
            }
        }
        System.out.println("[IEA] CustomSky: no mcpatcher sky found in selected resource packs");
    }

    private static Layer parseLayer(File pack, byte[] props) {
        try {
            Properties pr = new Properties();
            pr.load(new ByteArrayInputStream(props));
            String src = pr.getProperty("source", "./sky1.png").trim();
            byte[] img = readPack(pack, resolveSource(src));
            if (img == null) return null;
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img));
            if (bi == null) return null;
            int tex = upload(bi);
            if (tex == 0) return null;

            Layer L = new Layer();
            L.tex = tex;
            L.blend = parseBlend(pr.getProperty("blend", "add"));
            L.rotate = !"false".equalsIgnoreCase(pr.getProperty("rotate", "true").trim());
            try { L.speed = Float.parseFloat(pr.getProperty("speed", "1").trim()); } catch (Exception e) { }
            parseAxis(L, pr.getProperty("axis"));
            // Fade markers. OptiFine needs the ramp-in pair + endFadeOut; startFadeOut may be
            // omitted (e.g. a star layer), in which case mirror the ramp-in duration.
            String sIn = pr.getProperty("startFadeIn"), eIn = pr.getProperty("endFadeIn");
            String sOut = pr.getProperty("startFadeOut"), eOut = pr.getProperty("endFadeOut");
            if (sIn != null && eIn != null && eOut != null) {
                L.sIn = parseTime(sIn); L.eIn = parseTime(eIn); L.eOut = parseTime(eOut);
                if (sOut != null) {
                    L.sOut = parseTime(sOut);
                } else {
                    int rampIn = mod24000(L.eIn - L.sIn);
                    L.sOut = mod24000(L.eOut - rampIn);
                }
                L.hasFade = true;
            }
            return L;
        } catch (Throwable t) {
            System.out.println("[IEA] CustomSky parse error: " + t);
            return null;
        }
    }

    private static int parseBlend(String s) {
        s = s == null ? "" : s.trim().toLowerCase();
        if (s.equals("alpha")) return BLEND_ALPHA;
        if (s.equals("subtract")) return BLEND_SUBTRACT;
        if (s.equals("multiply")) return BLEND_MULTIPLY;
        if (s.equals("replace")) return BLEND_REPLACE;
        if (s.equals("screen")) return BLEND_SCREEN;
        if (s.equals("burn")) return BLEND_BURN;
        if (s.equals("dodge")) return BLEND_DODGE;
        return BLEND_ADD; // add / unknown
    }

    // "axis = x y z" -> normalized rotation axis (the sky spins around it over the day)
    private static void parseAxis(Layer L, String s) {
        if (s == null) return;
        String[] p = s.trim().split("\\s+");
        if (p.length != 3) return;
        try {
            float x = Float.parseFloat(p[0]), y = Float.parseFloat(p[1]), z = Float.parseFloat(p[2]);
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len > 1e-5f) { L.ax = x / len; L.ay = y / len; L.az = z / len; }
        } catch (Exception ignored) { }
    }

    // fade markers are Minecraft-time ticks (0..24000, 0 = 06:00). Some packs write the
    // MCPatcher "HH:mm" clock form instead; accept both.
    private static int parseTime(String s) {
        s = s.trim();
        int c = s.indexOf(':');
        if (c >= 0) {
            try {
                int h = Integer.parseInt(s.substring(0, c).trim());
                int m = Integer.parseInt(s.substring(c + 1).trim());
                int ticks = (int) ((h - 6) * 1000 + m * 1000 / 60); // 06:00 -> tick 0
                return mod24000(ticks);
            } catch (Exception e) { return 0; }
        }
        try { return mod24000(Integer.parseInt(s)); } catch (Exception e) { return 0; }
    }

    private static int mod24000(int v) { return ((v % 24000) + 24000) % 24000; }

    // map a properties "source" to a pack-relative resource path
    private static String resolveSource(String s) {
        if (s.startsWith("./")) return "assets/minecraft/mcpatcher/sky/world0/" + s.substring(2);
        if (s.startsWith("assets/")) return s;
        int c = s.indexOf(':');
        if (c >= 0) return "assets/" + s.substring(0, c) + "/" + s.substring(c + 1);
        return "assets/minecraft/" + s;
    }

    // resourcePacks:["a.zip","b"] line from options.txt (1.8.9 format)
    private static List<String> selectedPacks(File gameDir) {
        List<String> out = new ArrayList<String>();
        File opt = new File(gameDir, "options.txt");
        if (!opt.isFile()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(opt));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("resourcePacks:")) continue;
                int a = line.indexOf('['), b = line.lastIndexOf(']');
                if (a < 0 || b <= a) break;
                for (String tok : line.substring(a + 1, b).split(",")) {
                    String s = tok.trim();
                    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
                    if (s.startsWith("file/")) s = s.substring(5); // newer format prefix (harmless)
                    if (!s.isEmpty()) out.add(s);
                }
                break;
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception e) { }
        }
        return out;
    }

    // read one entry from a pack that is either a folder or a .zip
    private static byte[] readPack(File pack, String entry) {
        try {
            if (pack.isDirectory()) {
                File f = new File(pack, entry);
                return f.isFile() ? readAll(new FileInputStream(f)) : null;
            }
            if (pack.isFile() && pack.getName().toLowerCase().endsWith(".zip")) {
                ZipFile zf = new ZipFile(pack);
                try {
                    ZipEntry e = zf.getEntry(entry);
                    return e != null ? readAll(zf.getInputStream(e)) : null;
                } finally {
                    zf.close();
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { in.close(); } catch (Exception e) { }
        }
    }

    private static int upload(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < px.length; i++) {
            int argb = px[i];
            buf.put((byte) ((argb >> 16) & 0xFF));
            buf.put((byte) ((argb >> 8) & 0xFF));
            buf.put((byte) (argb & 0xFF));
            buf.put((byte) ((argb >>> 24) & 0xFF));
        }
        buf.flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    private static String dbgSig = null;

    /** Draw the custom sky layers over the vanilla sky (called from the renderSky exit hook). */
    public static void render(float partial) {
        if (!ready || layers.isEmpty()) return;
        int time = Mc.worldTimeOfDay();       // 0..24000, or -1 if unknown
        float ca = Mc.celestialAngle(partial); // 0..1, or -1 if unknown
        boolean haveTime = time >= 0;

        // diagnostic: log only when the layer composition changes (not every frame)
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) {
            float b = haveTime ? fade(layers.get(i), time) : 1f;
            sig.append((int) (b * 20)).append(',');
        }
        String s = sig.toString();
        if (!s.equals(dbgSig)) {
            dbgSig = s;
            System.out.println("[IEA] sky comp: time=" + time + " haveTime=" + haveTime
                    + " brightness=" + s);
        }

        // Self-contained overlay: save the exact GL state vanilla left (enables, blend func +
        // equation, depth mask, current colour) and the bound texture, then restore both. That
        // keeps GlStateManager's cache in sync (we hand GL back byte-for-byte), avoiding the
        // blend/texture desync that turns leaves transparent / garbles the HUD.
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);

            // Base pass: paint the sky box solid black so that 'add'/'screen' layers composite
            // against black (add/screen over black == the texture itself) and reveal their true
            // colours, instead of brightening the vanilla blue sky all the way to white. These
            // packs assume the custom sky REPLACES the sky; day/night still comes from the layer
            // fades below (day-cloud layer vs night-star layers).
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(0f, 0f, 0f, 1f);
            drawBox();

            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);

            for (int i = 0; i < layers.size(); i++) {
                Layer L = layers.get(i);
                float b = haveTime ? fade(L, time) : 1f; // no time -> show fully (fallback)
                if (b <= 0.004f) continue;
                applyBlend(L.blend, b);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, L.tex);
                GL11.glPushMatrix();
                if (L.rotate && ca >= 0f) GL11.glRotatef(ca * 360f * L.speed, L.ax, L.ay, L.az);
                drawBox();
                GL11.glPopMatrix();
            }
            GL14.glBlendEquation(GL14.GL_FUNC_ADD); // leave equation at the GL default
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    // OptiFine-style brightness ramp over the day, wrap-aware:
    // 0 before startFadeIn, up to 1 by endFadeIn, hold 1 until startFadeOut, down to 0 by endFadeOut.
    private static float fade(Layer L, int time) {
        if (!L.hasFade) return 1f;
        int x = mod24000(time - L.sIn);
        int in = mod24000(L.eIn - L.sIn);
        int full = mod24000(L.sOut - L.sIn);
        int out = mod24000(L.eOut - L.sIn);
        if (x < in) return in == 0 ? 1f : (float) x / in;
        if (x < full) return 1f;
        if (x < out) return out == full ? 0f : 1f - (float) (x - full) / (out - full);
        return 0f;
    }

    private static void applyBlend(int blend, float b) {
        switch (blend) {
            case BLEND_REPLACE: // opaque cross-fade: the texture replaces the sky by 'b'
                              // (alpha-weighted), so day/night full-sky layers dissolve into
                              // each other instead of adding up to white.
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(1f, 1f, 1f, b);
                break;
            case BLEND_SCREEN: // screen: brightens toward (not past) white; good for stars
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR);
                GL11.glColor4f(b, b, b, 1f);
                break;
            case BLEND_BURN: // burn: darkens the background by the source
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR);
                GL11.glColor4f(b, b, b, 1f);
                break;
            case BLEND_DODGE: // dodge: pure additive
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                GL11.glColor4f(b, b, b, 1f);
                break;
            case BLEND_ALPHA:
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(1f, 1f, 1f, b);
                break;
            case BLEND_SUBTRACT:
                GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                GL11.glColor4f(b, b, b, 1f);
                break;
            case BLEND_MULTIPLY:
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(b, b, b, b);
                break;
            default: // add (OptiFine default): additive WEIGHTED BY SRC ALPHA, so a texture's
                     // transparent regions (alpha 0) contribute nothing. Using GL_ONE,GL_ONE
                     // instead added those regions' RGB and washed the whole sky white.
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                GL11.glColor4f(b, b, b, b);
        }
    }

    // Skybox from the 3x2 source grid. Every tile is drawn as the same local floor quad
    // (the y=-100 plane) and oriented by the format's canonical rotation sequence, so a
    // pack's seams line up: tile 4 straight ahead, tiles 1/0 above/below, tiles 5/2/3 the
    // remaining three horizontal directions.
    private static void drawBox() {
        GL11.glPushMatrix();
        GL11.glRotatef(90f, 1f, 0f, 0f);
        GL11.glRotatef(-90f, 0f, 0f, 1f);
        tile(4);
        GL11.glPushMatrix();
        GL11.glRotatef(90f, 1f, 0f, 0f);
        tile(1);
        GL11.glPopMatrix();
        GL11.glPushMatrix();
        GL11.glRotatef(-90f, 1f, 0f, 0f);
        tile(0);
        GL11.glPopMatrix();
        GL11.glRotatef(90f, 0f, 0f, 1f);
        tile(5);
        GL11.glRotatef(90f, 0f, 0f, 1f);
        tile(2);
        GL11.glRotatef(90f, 0f, 0f, 1f);
        tile(3);
        GL11.glPopMatrix();
    }

    // one 1/3 x 1/2 tile of the source image on the local y=-100 plane
    private static void tile(int i) {
        float s = 100f;
        float u0 = (i % 3) / 3f, v0 = (i / 3) / 2f;
        float u1 = u0 + 1f / 3f, v1 = v0 + 0.5f;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex3f(-s, -s, -s);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex3f(-s, -s,  s);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex3f( s, -s,  s);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex3f( s, -s, -s);
        GL11.glEnd();
    }
}
