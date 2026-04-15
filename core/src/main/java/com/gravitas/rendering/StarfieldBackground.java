package com.gravitas.rendering;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

import java.util.Random;

/**
 * Procedurally-generated starfield with faint nebula clouds, star twinkling,
 * and nebula glow pulsation — all GPU-driven via a fullscreen shader.
 *
 * Two texture layers:
 * base — near-black background + soft nebula blobs (RGBA, A=1)
 * stars — star pixels on transparent background (RGB=colour, A=random phase
 * 0..1)
 *
 * The fragment shader composites both layers, using elapsed time to:
 * - modulate each star's brightness with multi-frequency sine (twinkle)
 * - gently pulse nebula luminosity (breathing glow)
 */
public class StarfieldBackground {

    private static final int TEX_SIZE = 2048;
    private static final float OPACITY = 0.4f;

    private final Texture baseTexture; // nebulae + dark sky
    private final Texture starsTexture; // stars with phase in alpha
    private final ShaderProgram shader;
    private float elapsedTime;

    public StarfieldBackground() {
        // Generate the two layers — unique each run.
        Random rng = new Random(System.nanoTime());
        baseTexture = createTexture(generateBasePixmap(rng));
        starsTexture = createTexture(generateStarsPixmap(rng));

        // Compile starfield shader.
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(
                Gdx.files.internal("shaders/starfield.vert"),
                Gdx.files.internal("shaders/starfield.frag"));
        if (!shader.isCompiled()) {
            Gdx.app.error("StarfieldBG", "Shader compilation failed:\n" + shader.getLog());
        }
    }

    private Texture createTexture(Pixmap pm) {
        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        pm.dispose();
        return tex;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Draw the starfield stretched to fill the entire screen.
     * Call BEFORE any world-space rendering.
     *
     * @param dt real-time delta (seconds) — NOT sim-time, so twinkle
     *           speed is independent of time-warp.
     */
    public void render(SpriteBatch batch, int screenWidth, int screenHeight, float dt) {
        elapsedTime += dt;

        // Bind stars texture to unit 1.
        starsTexture.bind(1);
        // Re-bind base to unit 0 (SpriteBatch always uses unit 0).
        baseTexture.bind(0);

        ShaderProgram prev = batch.getShader();
        batch.setShader(shader);
        batch.begin();

        shader.setUniformi("u_baseTexture", 0);
        shader.setUniformi("u_starsTexture", 1);
        shader.setUniformf("u_time", elapsedTime);

        batch.setColor(1f, 1f, 1f, OPACITY);
        batch.draw(baseTexture, 0, 0, screenWidth, screenHeight,
                0, 0, TEX_SIZE, TEX_SIZE, false, false);
        batch.setColor(Color.WHITE);

        batch.end();
        batch.setShader(prev);

        // Restore active texture unit to 0.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    /** @deprecated Use {@link #render(SpriteBatch, int, int, float)} instead. */
    @Deprecated
    public void render(SpriteBatch batch, int screenWidth, int screenHeight) {
        render(batch, screenWidth, screenHeight, Gdx.graphics.getDeltaTime());
    }

    public void dispose() {
        baseTexture.dispose();
        starsTexture.dispose();
        shader.dispose();
    }

    // -------------------------------------------------------------------------
    // Procedural generation — base layer (background + nebulae)
    // -------------------------------------------------------------------------

    private Pixmap generateBasePixmap(Random rng) {
        Pixmap pm = new Pixmap(TEX_SIZE, TEX_SIZE, Pixmap.Format.RGBA8888);
        pm.setColor(0.01f, 0.01f, 0.02f, 1f);
        pm.fill();
        pm.setBlending(Pixmap.Blending.SourceOver);
        paintNebulae(pm, rng);
        return pm;
    }

    // -------------------------------------------------------------------------
    // Procedural generation — stars layer (transparent bg, phase in alpha)
    // -------------------------------------------------------------------------

    private Pixmap generateStarsPixmap(Random rng) {
        Pixmap pm = new Pixmap(TEX_SIZE, TEX_SIZE, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0); // fully transparent
        pm.fill();
        pm.setBlending(Pixmap.Blending.None); // direct write, no blending
        paintStars(pm, rng);
        return pm;
    }

    /**
     * Fractal nebulae using layered Perlin-style noise (fBm).
     * Creates wispy, filamentary structures instead of uniform blobs.
     */
    private void paintNebulae(Pixmap pm, Random rng) {
        // Pre-generate a permutation table for value noise.
        int[] perm = new int[512];
        {
            int[] base = new int[256];
            for (int i = 0; i < 256; i++)
                base[i] = i;
            for (int i = 255; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = base[i];
                base[i] = base[j];
                base[j] = tmp;
            }
            for (int i = 0; i < 512; i++)
                perm[i] = base[i & 255];
        }

        // Gradient vectors for 2D Perlin noise.
        float[][] grad = {
                { 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }
        };

        // 3-5 nebula regions, each with its own colour, position, and noise seed
        // offset.
        int nebulaCount = 3 + rng.nextInt(3);

        // Colour palettes for nebulae (richer, more Hubble-like).
        float[][] palettes = {
                { 0.35f, 0.08f, 0.12f }, // warm rose/hydrogen-alpha
                { 0.10f, 0.15f, 0.45f }, // deep blue/reflection
                { 0.08f, 0.30f, 0.28f }, // teal/oxygen III
                { 0.30f, 0.10f, 0.40f }, // purple/emission
                { 0.15f, 0.20f, 0.35f }, // blue-violet
                { 0.40f, 0.15f, 0.08f }, // orange/dust
        };

        // For each nebula, compute fBm density and paint.
        float[][] nebulaCenters = new float[nebulaCount][2];
        float[] nebulaRadii = new float[nebulaCount];
        float[][] nebulaColors = new float[nebulaCount][3];
        float[] nebulaOffsets = new float[nebulaCount]; // noise seed offset
        float[] nebulaPeaks = new float[nebulaCount];

        for (int n = 0; n < nebulaCount; n++) {
            nebulaCenters[n][0] = rng.nextFloat() * TEX_SIZE;
            nebulaCenters[n][1] = rng.nextFloat() * TEX_SIZE;
            nebulaRadii[n] = 160 + rng.nextFloat() * 280; // 160-440 px
            nebulaColors[n] = palettes[rng.nextInt(palettes.length)].clone();
            nebulaOffsets[n] = rng.nextFloat() * 1000f;
            nebulaPeaks[n] = 0.25f + rng.nextFloat() * 0.25f; // 25-50% peak opacity — visible at low master opacity
        }

        // Noise helper: 2D Perlin noise at (x, y).
        // Using a lambda-like approach via local method reference.

        // Paint pixel by pixel (only within nebula bounding boxes for speed).
        for (int n = 0; n < nebulaCount; n++) {
            float cx = nebulaCenters[n][0];
            float cy = nebulaCenters[n][1];
            float radius = nebulaRadii[n];
            float peakAlpha = nebulaPeaks[n];
            float cr = nebulaColors[n][0];
            float cg = nebulaColors[n][1];
            float cb = nebulaColors[n][2];
            float offset = nebulaOffsets[n];

            int r = (int) (radius * 1.3f); // slightly overscan for noise tendrils

            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    float distSq = dx * dx + dy * dy;
                    float rSq = radius * radius;
                    // Soft radial falloff — allows tendrils to poke out.
                    float radial = 1f - distSq / (rSq * 1.6f);
                    if (radial < 0.01f)
                        continue;
                    radial = Math.min(radial, 1f);
                    radial = (float) Math.pow(radial, 1.3); // gentle falloff

                    // Normalized coords for noise sampling.
                    float nx = (cx + dx + offset) / 120f;
                    float ny = (cy + dy + offset * 0.7f) / 120f;

                    // fBm — 6 octaves of Perlin noise.
                    float density = 0f;
                    float amp = 1f;
                    float freq = 1f;
                    for (int oct = 0; oct < 6; oct++) {
                        density += amp * perlinNoise(nx * freq, ny * freq, perm, grad);
                        amp *= 0.5f;
                        freq *= 2.1f;
                    }

                    // Remap from [-1..1] to [0..1], with contrast boost.
                    density = density * 0.5f + 0.5f;
                    // Sharpen: push low values down, keep high values → filaments.
                    // Lower exponent = more visible structure; higher = sparser filaments.
                    density = (float) Math.pow(Math.max(density, 0), 1.4);

                    // Another noise pass at different scale for fine dust.
                    float dust = 0f;
                    amp = 1f;
                    freq = 3f;
                    for (int oct = 0; oct < 4; oct++) {
                        dust += amp * perlinNoise(nx * freq + 50f, ny * freq + 50f, perm, grad);
                        amp *= 0.5f;
                        freq *= 2.3f;
                    }
                    dust = dust * 0.5f + 0.5f;
                    dust = Math.max(dust, 0f);

                    // Combine: density shapes the nebula, dust adds fine texture.
                    float alpha = peakAlpha * radial * (density * 0.7f + dust * 0.3f);
                    if (alpha < 0.001f)
                        continue;

                    // Slight colour variation based on density.
                    float colorShift = density * 0.3f;
                    float pr = cr + colorShift * 0.1f;
                    float pg = cg + (1f - density) * 0.05f;
                    float pb = cb + dust * 0.08f;

                    int px = (((int) (cx + dx)) % TEX_SIZE + TEX_SIZE) % TEX_SIZE;
                    int py = (((int) (cy + dy)) % TEX_SIZE + TEX_SIZE) % TEX_SIZE;

                    // Additive blend.
                    int existing = pm.getPixel(px, py);
                    float er = ((existing >>> 24) & 0xFF) / 255f;
                    float eg = ((existing >>> 16) & 0xFF) / 255f;
                    float eb = ((existing >>> 8) & 0xFF) / 255f;
                    float nr = Math.min(er + pr * alpha, 1f);
                    float ng = Math.min(eg + pg * alpha, 1f);
                    float nb = Math.min(eb + pb * alpha, 1f);
                    pm.setColor(nr, ng, nb, 1f);
                    pm.drawPixel(px, py);
                }
            }
        }
    }

    /**
     * Classic 2D Perlin noise.
     */
    private static float perlinNoise(float x, float y, int[] perm, float[][] grad) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        float xf = x - (float) Math.floor(x);
        float yf = y - (float) Math.floor(y);

        // Fade curves.
        float u = xf * xf * xf * (xf * (xf * 6f - 15f) + 10f);
        float v = yf * yf * yf * (yf * (yf * 6f - 15f) + 10f);

        // Hash corners.
        int aa = perm[perm[xi] + yi] & 7;
        int ab = perm[perm[xi] + yi + 1] & 7;
        int ba = perm[perm[xi + 1] + yi] & 7;
        int bb = perm[perm[xi + 1] + yi + 1] & 7;

        // Dot products.
        float d00 = grad[aa][0] * xf + grad[aa][1] * yf;
        float d10 = grad[ba][0] * (xf - 1f) + grad[ba][1] * yf;
        float d01 = grad[ab][0] * xf + grad[ab][1] * (yf - 1f);
        float d11 = grad[bb][0] * (xf - 1f) + grad[bb][1] * (yf - 1f);

        // Bilinear interpolation.
        float x1 = d00 + u * (d10 - d00);
        float x2 = d01 + u * (d11 - d01);
        return x1 + v * (x2 - x1);
    }

    /**
     * Scatter stars of varying brightness and subtle colour.
     * On the stars pixmap, alpha encodes a random twinkle phase (0..1).
     */
    private void paintStars(Pixmap pm, Random rng) {
        // Faint background stars — tiny, subtle.
        for (int i = 0; i < 3000; i++) {
            int x = rng.nextInt(TEX_SIZE);
            int y = rng.nextInt(TEX_SIZE);
            float bright = 0.15f + rng.nextFloat() * 0.35f;
            float phase = rng.nextFloat();
            writeStarPixel(pm, x, y, bright, phase, rng);
        }
        // Medium stars — visible glow halo (3x3 cross).
        for (int i = 0; i < 800; i++) {
            int x = rng.nextInt(TEX_SIZE);
            int y = rng.nextInt(TEX_SIZE);
            float bright = 0.55f + rng.nextFloat() * 0.35f;
            float phase = rng.nextFloat();
            writeStarPixel(pm, x, y, bright, phase, rng);
            float g1 = bright * 0.40f;
            float g2 = bright * 0.15f;
            for (int d = -1; d <= 1; d += 2) {
                writeStarPixelRaw(pm, x + d, y, g1, g1, g1, phase);
                writeStarPixelRaw(pm, x, y + d, g1, g1, g1, phase);
                writeStarPixelRaw(pm, x + 2 * d, y, g2, g2, g2, phase);
                writeStarPixelRaw(pm, x, y + 2 * d, g2, g2, g2, phase);
            }
            // Diagonal glow for rounder shape.
            float gd = bright * 0.18f;
            writeStarPixelRaw(pm, x - 1, y - 1, gd, gd, gd, phase);
            writeStarPixelRaw(pm, x + 1, y - 1, gd, gd, gd, phase);
            writeStarPixelRaw(pm, x - 1, y + 1, gd, gd, gd, phase);
            writeStarPixelRaw(pm, x + 1, y + 1, gd, gd, gd, phase);
        }
        // Bright stars (rare) — prominent glow with 4-pixel cross + diagonals.
        for (int i = 0; i < 120; i++) {
            int x = rng.nextInt(TEX_SIZE);
            int y = rng.nextInt(TEX_SIZE);
            float bright = 0.85f + rng.nextFloat() * 0.15f;
            float phase = rng.nextFloat();
            writeStarPixel(pm, x, y, bright, phase, rng);
            float g1 = bright * 0.55f;
            float g2 = bright * 0.30f;
            float g3 = bright * 0.12f;
            for (int d = -1; d <= 1; d += 2) {
                writeStarPixelRaw(pm, x + d, y, g1, g1, g1, phase);
                writeStarPixelRaw(pm, x, y + d, g1, g1, g1, phase);
                writeStarPixelRaw(pm, x + 2 * d, y, g2, g2, g2, phase);
                writeStarPixelRaw(pm, x, y + 2 * d, g2, g2, g2, phase);
                writeStarPixelRaw(pm, x + 3 * d, y, g3, g3, g3, phase);
                writeStarPixelRaw(pm, x, y + 3 * d, g3, g3, g3, phase);
            }
            // Diagonal glow.
            float gd1 = bright * 0.30f;
            float gd2 = bright * 0.10f;
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dy = -1; dy <= 1; dy += 2) {
                    writeStarPixelRaw(pm, x + dx, y + dy, gd1, gd1, gd1, phase);
                    writeStarPixelRaw(pm, x + 2 * dx, y + 2 * dy, gd2, gd2, gd2, phase);
                }
            }
        }
    }

    /** Write a tinted star pixel with a random phase in the alpha channel. */
    private void writeStarPixel(Pixmap pm, int x, int y, float bright, float phase, Random rng) {
        float r = bright, g = bright, b = bright;
        float tint = rng.nextFloat();
        if (tint < 0.3f) {
            r *= 0.85f;
            g *= 0.90f;
            b *= 1.0f;
        } else if (tint < 0.5f) {
            r *= 1.0f;
            g *= 0.95f;
            b *= 0.80f;
        } else if (tint < 0.6f) {
            r *= 1.0f;
            g *= 0.80f;
            b *= 0.75f;
        }
        writeStarPixelRaw(pm, x, y, r, g, b, phase);
    }

    /** Write a single star pixel (direct, no blending). Alpha = twinkle phase. */
    private void writeStarPixelRaw(Pixmap pm, int x, int y, float r, float g, float b, float phase) {
        x = ((x % TEX_SIZE) + TEX_SIZE) % TEX_SIZE;
        y = ((y % TEX_SIZE) + TEX_SIZE) % TEX_SIZE;
        // Keep brightest value if pixel already occupied (glow overlap).
        int existing = pm.getPixel(x, y);
        float er = ((existing >>> 24) & 0xFF) / 255f;
        float eg = ((existing >>> 16) & 0xFF) / 255f;
        float eb = ((existing >>> 8) & 0xFF) / 255f;
        pm.setColor(Math.max(er, r), Math.max(eg, g), Math.max(eb, b), phase);
        pm.drawPixel(x, y);
    }
}
