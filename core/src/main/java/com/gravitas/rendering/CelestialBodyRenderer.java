package com.gravitas.rendering;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders celestial bodies with realistic equirectangular textures projected
 * onto 2D disks via fragment shaders. Features:
 * <ul>
 * <li>Spherical projection with limb darkening</li>
 * <li>Axial rotation (uses CelestialBody.rotationAngle)</li>
 * <li>Atmospheric glow ring for bodies with atmospheres</li>
 * <li>LOD: falls back to flat circles below a configurable pixel threshold</li>
 * </ul>
 *
 * The texture folder is configurable per stellar system (e.g. "solar_system").
 */
public class CelestialBodyRenderer {

    private static final String TAG = "CelestialBodyRenderer";

    /**
     * Below this screen-pixel diameter, we skip the texture pass (ShapeRenderer
     * handles it).
     */
    private static final float MIN_TEXTURE_DIAMETER_PX = 8f;

    /** Atmosphere glow quad extends this factor beyond the body radius. */
    private static final float ATMO_RING_SCALE = 1.3f;

    /** Star corona quad extends this factor beyond the star radius. */
    private static final float STAR_GLOW_SCALE = 3.0f;

    /**
     * Star screen diameter (pixels) at which glow is at full intensity.
     * Below this the star is "far away" and the corona dominates.
     */
    private static final float STAR_GLOW_FAR_PX = 30f;

    /**
     * Star screen diameter (pixels) at which glow is at minimum intensity.
     * Above this the texture detail is visible and glow fades out.
     */
    private static final float STAR_GLOW_NEAR_PX = 400f;

    /** Base path under assets/textures/ for the current stellar system. */
    private final String textureBasePath;

    private final SpriteBatch batch;
    private final WorldCamera camera;

    /** Planet surface shader. */
    private ShaderProgram planetShader;
    /** Atmosphere glow shader. */
    private ShaderProgram atmosphereShader;
    /** Stellar corona/glow shader. */
    private ShaderProgram starGlowShader;
    /** Planetary ring shader. */
    private ShaderProgram ringShader;
    /** Procedural cloud layer shader. */
    private ShaderProgram cloudShader;

    /** Loaded textures keyed by body name. */
    private final Map<String, Texture> textures = new HashMap<>();

    /** Ring textures keyed by texture filename. */
    private final Map<String, Texture> ringTextures = new HashMap<>();

    /** Raw quad mesh for atmosphere glow (bypasses SpriteBatch entirely). */
    private Mesh glowMesh;
    /** Vertex scratch buffer: 4 verts × (x, y, u, v) = 16 floats. */
    private final float[] glowVerts = new float[16];

    /**
     * Set of body names whose textures failed to load (avoid repeated warnings).
     */
    private final java.util.Set<String> missingWarned = new java.util.HashSet<>();

    /** Accumulated real-time for cloud animation (seconds). */
    private float cloudTime = 0f;
    private boolean celestialFx = true;

    public void setCelestialFx(boolean enabled) {
        this.celestialFx = enabled;
    }

    /** Cloud layer screen diameter thresholds (fade range). */
    private static final float CLOUD_FAR_PX = 40f;
    private static final float CLOUD_NEAR_PX = 2900f;

    public CelestialBodyRenderer(WorldCamera camera, String systemFolder) {
        this.camera = camera;
        this.textureBasePath = "textures/" + systemFolder + "/";
        this.batch = new SpriteBatch();

        compileShaders();
        createGlowMesh();
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    private void compileShaders() {
        ShaderProgram.pedantic = false;

        planetShader = new ShaderProgram(
                Gdx.files.internal("shaders/planet.vert"),
                Gdx.files.internal("shaders/planet.frag"));
        if (!planetShader.isCompiled()) {
            Gdx.app.error(TAG, "Planet shader compilation failed:\n" + planetShader.getLog());
        }

        atmosphereShader = new ShaderProgram(
                Gdx.files.internal("shaders/atmosphere.vert"),
                Gdx.files.internal("shaders/atmosphere.frag"));
        if (!atmosphereShader.isCompiled()) {
            Gdx.app.error(TAG, "Atmosphere shader compilation failed:\n" + atmosphereShader.getLog());
        }

        starGlowShader = new ShaderProgram(
                Gdx.files.internal("shaders/star_glow.vert"),
                Gdx.files.internal("shaders/star_glow.frag"));
        if (!starGlowShader.isCompiled()) {
            Gdx.app.error(TAG, "Star glow shader compilation failed:\n" + starGlowShader.getLog());
        }

        ringShader = new ShaderProgram(
                Gdx.files.internal("shaders/ring.vert"),
                Gdx.files.internal("shaders/ring.frag"));
        if (!ringShader.isCompiled()) {
            Gdx.app.error(TAG, "Ring shader compilation failed:\n" + ringShader.getLog());
        }

        cloudShader = new ShaderProgram(
                Gdx.files.internal("shaders/clouds.vert"),
                Gdx.files.internal("shaders/clouds.frag"));
        if (!cloudShader.isCompiled()) {
            Gdx.app.error(TAG, "Cloud shader compilation failed:\n" + cloudShader.getLog());
        }
    }

    private void createGlowMesh() {
        glowMesh = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        glowMesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
    }

    // -------------------------------------------------------------------------
    // Texture loading (lazy, per body)
    // -------------------------------------------------------------------------

    /**
     * Loads the texture for a body on first access. Bodies without a texture
     * file return null and render as flat-colour circles via ShapeRenderer.
     */
    private Texture getTexture(CelestialBody body) {
        if (body.textureFile == null || body.textureFile.isEmpty()) {
            return null;
        }
        Texture tex = textures.get(body.name);
        if (tex != null)
            return tex;

        String path = textureBasePath + body.textureFile;
        if (!Gdx.files.internal(path).exists()) {
            if (missingWarned.add(body.name)) {
                Gdx.app.log(TAG, "Texture not found: " + path + " — falling back to colour for " + body.name);
            }
            return null;
        }

        tex = new Texture(Gdx.files.internal(path), true); // generate mipmaps
        tex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
        textures.put(body.name, tex);
        Gdx.app.log(TAG, "Loaded texture: " + path + " (" + tex.getWidth() + "×" + tex.getHeight() + ")");
        return tex;
    }

    /**
     * Loads the ring texture for a body on first access. Returns null if the
     * body has no ring texture or the file is missing.
     */
    private Texture getRingTexture(CelestialBody body) {
        if (body.ringTexture == null || body.ringTexture.isEmpty()) {
            return null;
        }
        Texture tex = ringTextures.get(body.ringTexture);
        if (tex != null)
            return tex;

        String path = textureBasePath + body.ringTexture;
        if (!Gdx.files.internal(path).exists()) {
            Gdx.app.log(TAG, "Ring texture not found: " + path);
            return null;
        }

        tex = new Texture(Gdx.files.internal(path));
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        ringTextures.put(body.ringTexture, tex);
        Gdx.app.log(TAG, "Loaded ring texture: " + path);
        return tex;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Renders textured bodies and atmosphere glow. Should be called after
     * trails but before HUD. Bodies below the LOD threshold are skipped
     * (SimRenderer draws them as circles).
     *
     * @param objects all sim objects
     * @param screenX pre-computed screen X positions
     * @param screenY pre-computed screen Y positions
     * @param screenR pre-computed screen radii (after visual scale)
     * @return a boolean array: true at index i means this renderer handled that
     *         body
     */
    public boolean[] render(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR) {
        int n = objects.size();
        boolean[] handled = new boolean[n];

        // Advance cloud animation timer
        cloudTime += Gdx.graphics.getDeltaTime();

        // --- Pass 1: Planet textures (standard alpha blending) ---
        batch.setProjectionMatrix(camera.getCamera().combined);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setShader(planetShader);
        batch.begin();

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;

            float diameter = screenR[i] * 2f;
            if (diameter < MIN_TEXTURE_DIAMETER_PX)
                continue;

            Texture tex = getTexture(cb);
            if (tex == null)
                continue;

            handled[i] = true;

            planetShader.setUniformf("u_rotation", (float) cb.rotationAngle);
            planetShader.setUniformf("u_isStar",
                    cb.bodyType == CelestialBody.BodyType.STAR ? 1.0f : 0.0f);

            // Base colour for partial-texture fill
            float br = ((cb.displayColor >> 24) & 0xFF) / 255f;
            float bg = ((cb.displayColor >> 16) & 0xFF) / 255f;
            float bb = ((cb.displayColor >> 8) & 0xFF) / 255f;
            planetShader.setUniformf("u_baseColor", br, bg, bb);

            // Draw a quad centred on the body's screen position
            batch.draw(tex,
                    screenX[i] - screenR[i], screenY[i] - screenR[i],
                    diameter, diameter);
            batch.flush(); // flush immediately so this body's uniforms are used
        }

        batch.end();

        // --- Pass 1b: Planetary rings (raw Mesh, standard alpha blending) ---
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        ringShader.bind();
        ringShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!cb.hasRings())
                continue;

            float diameter = screenR[i] * 2f;
            if (diameter < MIN_TEXTURE_DIAMETER_PX)
                continue;

            // Scale ring radii proportionally to visual radius
            float ringOuterScreen = screenR[i] * (float) (cb.ringOuterRadius / cb.radius);
            float ringInnerScreen = screenR[i] * (float) (cb.ringInnerRadius / cb.radius);

            // The quad must cover the outer ring
            float quadRadius = ringOuterScreen * 1.05f; // slight margin for AA
            float innerFrac = ringInnerScreen / quadRadius;
            float outerFrac = ringOuterScreen / quadRadius;

            Texture ringTex = getRingTexture(cb);
            if (ringTex != null) {
                ringTex.bind(0);
                ringShader.setUniformi("u_texture", 0);
                ringShader.setUniformf("u_hasTexture", 1.0f);
                ringShader.setUniformf("u_ringColor", 1.0f, 1.0f, 1.0f);
            } else {
                // Procedural ring: use ringColor or displayColor
                float rr, rg, rb;
                int color = cb.ringColor != 0 ? cb.ringColor : cb.displayColor;
                rr = ((color >> 24) & 0xFF) / 255f;
                rg = ((color >> 16) & 0xFF) / 255f;
                rb = ((color >> 8) & 0xFF) / 255f;
                ringShader.setUniformf("u_hasTexture", 0.0f);
                ringShader.setUniformf("u_ringColor", rr, rg, rb);
            }

            ringShader.setUniformf("u_innerRadius", innerFrac);
            ringShader.setUniformf("u_outerRadius", outerFrac);
            ringShader.setUniformf("u_opacity", cb.ringOpacity);

            float x1 = screenX[i] - quadRadius, y1 = screenY[i] - quadRadius;
            float x2 = screenX[i] + quadRadius, y2 = screenY[i] + quadRadius;
            glowVerts[0] = x1;
            glowVerts[1] = y1;
            glowVerts[2] = 0f;
            glowVerts[3] = 0f;
            glowVerts[4] = x2;
            glowVerts[5] = y1;
            glowVerts[6] = 1f;
            glowVerts[7] = 0f;
            glowVerts[8] = x2;
            glowVerts[9] = y2;
            glowVerts[10] = 1f;
            glowVerts[11] = 1f;
            glowVerts[12] = x1;
            glowVerts[13] = y2;
            glowVerts[14] = 0f;
            glowVerts[15] = 1f;

            glowMesh.setVertices(glowVerts);
            glowMesh.render(ringShader, GL20.GL_TRIANGLES);
        }

        // --- Pass 1c: Procedural cloud layers (standard alpha blending) ---
        if (celestialFx) {
            // Cloud layers
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

            cloudShader.bind();
            cloudShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);
            cloudShader.setUniformf("u_cloudTime", cloudTime);

            for (int i = 0; i < n; i++) {
                SimObject obj = objects.get(i);
                if (!obj.active || !(obj instanceof CelestialBody cb))
                    continue;
                if (!cb.cloudLayer)
                    continue;

                float diameter = screenR[i] * 2f;
                if (diameter < MIN_TEXTURE_DIAMETER_PX)
                    continue;

                // Zoom-dependent fade: full at medium distance, fade out close-up
                float zoomFade = 1.0f - Math.min(1.0f,
                        Math.max(0.0f, (diameter - CLOUD_FAR_PX)
                                / (CLOUD_NEAR_PX - CLOUD_FAR_PX)));
                // Smooth power curve: clouds linger at medium zoom, then drop fast
                zoomFade = zoomFade * zoomFade;

                cloudShader.setUniformf("u_rotation", (float) cb.rotationAngle);
                cloudShader.setUniformf("u_zoomFade", zoomFade);

                // Cloud colour
                float cr = ((cb.cloudColor >> 24) & 0xFF) / 255f;
                float cg = ((cb.cloudColor >> 16) & 0xFF) / 255f;
                float cbb = ((cb.cloudColor >> 8) & 0xFF) / 255f;
                cloudShader.setUniformf("u_cloudColor", cr, cg, cbb);

                float x1 = screenX[i] - screenR[i], y1 = screenY[i] - screenR[i];
                float x2 = screenX[i] + screenR[i], y2 = screenY[i] + screenR[i];
                glowVerts[0] = x1;
                glowVerts[1] = y1;
                glowVerts[2] = 0f;
                glowVerts[3] = 0f;
                glowVerts[4] = x2;
                glowVerts[5] = y1;
                glowVerts[6] = 1f;
                glowVerts[7] = 0f;
                glowVerts[8] = x2;
                glowVerts[9] = y2;
                glowVerts[10] = 1f;
                glowVerts[11] = 1f;
                glowVerts[12] = x1;
                glowVerts[13] = y2;
                glowVerts[14] = 0f;
                glowVerts[15] = 1f;

                glowMesh.setVertices(glowVerts);
                glowMesh.render(cloudShader, GL20.GL_TRIANGLES);
            }
        }

        // --- Pass 2: Atmosphere glow (raw Mesh, fully additive blending) ---
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);

        atmosphereShader.bind();
        atmosphereShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!cb.hasAtmosphere())
                continue;
            if (cb.bodyType == CelestialBody.BodyType.STAR)
                continue;

            float diameter = screenR[i] * 2f;
            if (diameter < MIN_TEXTURE_DIAMETER_PX)
                continue;

            float glowRadius = screenR[i] * ATMO_RING_SCALE;

            // Atmosphere glow colour: use dedicated colour or derive from displayColor
            float ar, ag, ab;
            if (cb.atmosphereGlowColor != 0) {
                ar = ((cb.atmosphereGlowColor >> 24) & 0xFF) / 255f;
                ag = ((cb.atmosphereGlowColor >> 16) & 0xFF) / 255f;
                ab = ((cb.atmosphereGlowColor >> 8) & 0xFF) / 255f;
            } else {
                ar = ((cb.displayColor >> 24) & 0xFF) / 255f;
                ag = ((cb.displayColor >> 16) & 0xFF) / 255f;
                ab = ((cb.displayColor >> 8) & 0xFF) / 255f;
            }

            // Inner radius as fraction of the glow quad
            float innerFrac = screenR[i] / glowRadius;

            // Intensity: logarithmic scale so dense atmospheres don't blow out.
            // Earth(1.225)→0.34, Venus(65)→0.50, Mars(0.02)→0.16, Titan(5.3)→0.50
            float density = (float) cb.atmosphereDensitySeaLevel;
            float intensity = (float) (0.15 + 0.15 * Math.log1p(density * 2.0));
            intensity = Math.min(0.50f, Math.max(0.15f, intensity));

            atmosphereShader.setUniformf("u_glowColor", ar, ag, ab);
            atmosphereShader.setUniformf("u_intensity", intensity);
            atmosphereShader.setUniformf("u_innerRadius", innerFrac);

            // Build quad vertices: bottom-left, bottom-right, top-right, top-left
            float x1 = screenX[i] - glowRadius, y1 = screenY[i] - glowRadius;
            float x2 = screenX[i] + glowRadius, y2 = screenY[i] + glowRadius;
            // x, y, u, v
            glowVerts[0] = x1;
            glowVerts[1] = y1;
            glowVerts[2] = 0f;
            glowVerts[3] = 0f;
            glowVerts[4] = x2;
            glowVerts[5] = y1;
            glowVerts[6] = 1f;
            glowVerts[7] = 0f;
            glowVerts[8] = x2;
            glowVerts[9] = y2;
            glowVerts[10] = 1f;
            glowVerts[11] = 1f;
            glowVerts[12] = x1;
            glowVerts[13] = y2;
            glowVerts[14] = 0f;
            glowVerts[15] = 1f;

            glowMesh.setVertices(glowVerts);
            glowMesh.render(atmosphereShader, GL20.GL_TRIANGLES);
        }

        // Restore standard blending for subsequent renderers
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // --- Pass 3: Star corona glow (raw Mesh, additive blending) ---
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);

        starGlowShader.bind();
        starGlowShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (cb.bodyType != CelestialBody.BodyType.STAR)
                continue;

            float diameter = screenR[i] * 2f;
            if (diameter < MIN_TEXTURE_DIAMETER_PX)
                continue;

            float glowRadius = screenR[i] * STAR_GLOW_SCALE;
            float innerFrac = screenR[i] / glowRadius;

            // Zoom-dependent intensity: full glow when far, reduced close-up
            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - STAR_GLOW_FAR_PX)
                            / (STAR_GLOW_NEAR_PX - STAR_GLOW_FAR_PX)));

            // Core: hot white-yellow; Edge: warm orange-gold
            starGlowShader.setUniformf("u_coreColor", 1.0f, 0.97f, 0.85f);
            starGlowShader.setUniformf("u_edgeColor", 1.0f, 0.65f, 0.20f);
            starGlowShader.setUniformf("u_innerRadius", innerFrac);
            starGlowShader.setUniformf("u_zoomFade", zoomFade);

            float x1 = screenX[i] - glowRadius, y1 = screenY[i] - glowRadius;
            float x2 = screenX[i] + glowRadius, y2 = screenY[i] + glowRadius;
            glowVerts[0] = x1;
            glowVerts[1] = y1;
            glowVerts[2] = 0f;
            glowVerts[3] = 0f;
            glowVerts[4] = x2;
            glowVerts[5] = y1;
            glowVerts[6] = 1f;
            glowVerts[7] = 0f;
            glowVerts[8] = x2;
            glowVerts[9] = y2;
            glowVerts[10] = 1f;
            glowVerts[11] = 1f;
            glowVerts[12] = x1;
            glowVerts[13] = y2;
            glowVerts[14] = 0f;
            glowVerts[15] = 1f;

            glowMesh.setVertices(glowVerts);
            glowMesh.render(starGlowShader, GL20.GL_TRIANGLES);
        }

        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        return handled;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void dispose() {
        for (Texture tex : textures.values()) {
            tex.dispose();
        }
        textures.clear();
        for (Texture tex : ringTextures.values()) {
            tex.dispose();
        }
        ringTextures.clear();
        if (glowMesh != null)
            glowMesh.dispose();
        planetShader.dispose();
        atmosphereShader.dispose();
        starGlowShader.dispose();
        ringShader.dispose();
        cloudShader.dispose();
        batch.dispose();
    }

    /** Minimum screen diameter below which texture rendering is skipped. */
    public float getMinTextureDiameter() {
        return MIN_TEXTURE_DIAMETER_PX;
    }
}
