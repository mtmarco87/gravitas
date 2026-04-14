package com.gravitas.rendering;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import java.util.Random;

/**
 * Procedurally-generated starfield with faint nebula clouds.
 * Drawn as a full-screen background each frame (fixed, no parallax —
 * stars are "at infinity").
 */
public class StarfieldBackground {

    private static final int TEX_SIZE = 2048;
    private final Texture texture;

    public StarfieldBackground() {
        texture = new Texture(generatePixmap());
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Draw the starfield stretched to fill the entire screen.
     * Call BEFORE any world-space rendering.
     */
    public void render(SpriteBatch batch, int screenWidth, int screenHeight) {
        batch.begin();
        batch.setColor(1f, 1f, 1f, 0.75f);
        batch.draw(texture, 0, 0, screenWidth, screenHeight,
                0, 0, TEX_SIZE, TEX_SIZE, false, false);
        batch.setColor(Color.WHITE);
        batch.end();
    }

    public void dispose() {
        texture.dispose();
    }

    // -------------------------------------------------------------------------
    // Procedural generation
    // -------------------------------------------------------------------------

    private Pixmap generatePixmap() {
        Pixmap pm = new Pixmap(TEX_SIZE, TEX_SIZE, Pixmap.Format.RGBA8888);
        pm.setColor(0.01f, 0.01f, 0.02f, 1f); // near-black base
        pm.fill();
        pm.setBlending(Pixmap.Blending.SourceOver);

        Random rng = new Random(42); // deterministic

        // --- Layer 1: faint nebula blobs ---
        paintNebulae(pm, rng);

        // --- Layer 2: stars ---
        paintStars(pm, rng);

        return pm;
    }

    /**
     * Soft coloured Gaussian blobs at very low opacity to suggest nebulae.
     */
    private void paintNebulae(Pixmap pm, Random rng) {
        int count = 8 + rng.nextInt(6); // 8-13 nebulae
        for (int n = 0; n < count; n++) {
            int cx = rng.nextInt(TEX_SIZE);
            int cy = rng.nextInt(TEX_SIZE);
            int radius = 120 + rng.nextInt(300); // 120-420 px
            // Pick a colour palette: deep purple, blue, teal, or warm red.
            float hr = 0, hg = 0, hb = 0;
            switch (rng.nextInt(5)) {
                case 0 -> {
                    hr = 0.20f;
                    hg = 0.05f;
                    hb = 0.35f;
                } // purple
                case 1 -> {
                    hr = 0.05f;
                    hg = 0.10f;
                    hb = 0.40f;
                } // blue
                case 2 -> {
                    hr = 0.05f;
                    hg = 0.25f;
                    hb = 0.30f;
                } // teal
                case 3 -> {
                    hr = 0.35f;
                    hg = 0.08f;
                    hb = 0.10f;
                } // warm red
                case 4 -> {
                    hr = 0.10f;
                    hg = 0.05f;
                    hb = 0.25f;
                } // dark indigo
            }
            float peakAlpha = 0.04f + rng.nextFloat() * 0.06f; // 4-10%

            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    float distSq = dx * dx + dy * dy;
                    float rSq = (float) radius * radius;
                    if (distSq > rSq)
                        continue;
                    // Gaussian-ish fall-off.
                    float t = distSq / rSq;
                    float alpha = peakAlpha * (1f - t) * (1f - t);
                    if (alpha < 0.002f)
                        continue;
                    int px = (cx + dx + TEX_SIZE) % TEX_SIZE;
                    int py = (cy + dy + TEX_SIZE) % TEX_SIZE;
                    // Blend manually (Pixmap blending is limited).
                    int existing = pm.getPixel(px, py);
                    float er = ((existing >>> 24) & 0xFF) / 255f;
                    float eg = ((existing >>> 16) & 0xFF) / 255f;
                    float eb = ((existing >>> 8) & 0xFF) / 255f;
                    float nr = er + hr * alpha;
                    float ng = eg + hg * alpha;
                    float nb = eb + hb * alpha;
                    pm.setColor(Math.min(nr, 1f), Math.min(ng, 1f), Math.min(nb, 1f), 1f);
                    pm.drawPixel(px, py);
                }
            }
        }
    }

    /**
     * Scatter stars of varying brightness and subtle colour.
     */
    private void paintStars(Pixmap pm, Random rng) {
        // Faint background stars.
        for (int i = 0; i < 3000; i++) {
            int x = rng.nextInt(TEX_SIZE);
            int y = rng.nextInt(TEX_SIZE);
            float bright = 0.15f + rng.nextFloat() * 0.35f; // 15-50% brightness
            tintStar(pm, x, y, bright, rng);
        }
        // Medium stars.
        for (int i = 0; i < 600; i++) {
            int x = rng.nextInt(TEX_SIZE);
            int y = rng.nextInt(TEX_SIZE);
            float bright = 0.50f + rng.nextFloat() * 0.35f;
            tintStar(pm, x, y, bright, rng);
            // Slight glow halo (1 pixel ring at lower alpha).
            float halo = bright * 0.25f;
            blendPixel(pm, x - 1, y, halo, halo, halo);
            blendPixel(pm, x + 1, y, halo, halo, halo);
            blendPixel(pm, x, y - 1, halo, halo, halo);
            blendPixel(pm, x, y + 1, halo, halo, halo);
        }
        // Bright stars (rare).
        for (int i = 0; i < 80; i++) {
            int x = rng.nextInt(TEX_SIZE);
            int y = rng.nextInt(TEX_SIZE);
            float bright = 0.85f + rng.nextFloat() * 0.15f;
            tintStar(pm, x, y, bright, rng);
            // 2-pixel cross glow.
            float glow1 = bright * 0.35f;
            float glow2 = bright * 0.12f;
            for (int d = -1; d <= 1; d += 2) {
                blendPixel(pm, x + d, y, glow1, glow1, glow1);
                blendPixel(pm, x, y + d, glow1, glow1, glow1);
                blendPixel(pm, x + 2 * d, y, glow2, glow2, glow2);
                blendPixel(pm, x, y + 2 * d, glow2, glow2, glow2);
            }
        }
    }

    /** Draw a single star pixel with a subtle random colour tint. */
    private void tintStar(Pixmap pm, int x, int y, float bright, Random rng) {
        // Slight colour: warm white, blue-white, or yellow.
        float r = bright, g = bright, b = bright;
        float tint = rng.nextFloat();
        if (tint < 0.3f) {
            r *= 0.85f;
            g *= 0.90f;
            b *= 1.0f; // blue-ish
        } else if (tint < 0.5f) {
            r *= 1.0f;
            g *= 0.95f;
            b *= 0.80f; // warm yellow
        } else if (tint < 0.6f) {
            r *= 1.0f;
            g *= 0.80f;
            b *= 0.75f; // orange
        }
        blendPixel(pm, x, y, r, g, b);
    }

    /** Additive-blend a colour onto an existing pixel. */
    private void blendPixel(Pixmap pm, int x, int y, float r, float g, float b) {
        x = ((x % TEX_SIZE) + TEX_SIZE) % TEX_SIZE;
        y = ((y % TEX_SIZE) + TEX_SIZE) % TEX_SIZE;
        int existing = pm.getPixel(x, y);
        float er = ((existing >>> 24) & 0xFF) / 255f;
        float eg = ((existing >>> 16) & 0xFF) / 255f;
        float eb = ((existing >>> 8) & 0xFF) / 255f;
        pm.setColor(Math.min(er + r, 1f), Math.min(eg + g, 1f), Math.min(eb + b, 1f), 1f);
        pm.drawPixel(x, y);
    }
}
