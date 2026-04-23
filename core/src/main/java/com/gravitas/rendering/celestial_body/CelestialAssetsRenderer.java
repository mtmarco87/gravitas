package com.gravitas.rendering.celestial_body;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.regions.StellarSystem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns GPU-side resources used by celestial rendering: shaders, meshes,
 * textures, and the shared sprite batch.
 */
final class CelestialAssetsRenderer {

    static final class SurfaceTextures {
        Texture base;
        Texture night;

        boolean hasNightTexture() {
            return night != null;
        }
    }

    private static final String TAG = "CelestialAssetsRenderer";
    private static final String SHADER_2D = "celestial_body/2d";
    private static final String SHADER_3D = "celestial_body/3d";

    private final SpriteBatch batch;

    private ShaderProgram planetShader;
    private ShaderProgram atmosphereShader;
    private ShaderProgram starGlowShader;
    private ShaderProgram ringShader;
    private ShaderProgram cloudShader;

    private ShaderProgram planet3dShader;
    private ShaderProgram ring3dShader;
    private ShaderProgram atmosphere3dShader;
    private ShaderProgram cloud3dShader;
    private ShaderProgram dot3dShader;
    private ShaderProgram starGlow3dShader;
    private ShaderProgram overlayMask3dShader;

    private Mesh sphereMesh;
    private Mesh dotMesh;
    private Mesh glowMesh;

    private final Map<String, Mesh> ringMeshes = new HashMap<>();
    private final Map<String, SurfaceTextures> surfaceTextures = new HashMap<>();
    private final Map<String, Texture> surfaceTextureCache = new HashMap<>();
    private final Map<String, Texture> ringTextures = new HashMap<>();
    private final Set<String> missingWarned = new HashSet<>();

    CelestialAssetsRenderer() {
        this.batch = new SpriteBatch();

        compileShaders();
        createGlowMesh();
        createSphereMesh();
        createDotMesh();
    }

    SpriteBatch batch() {
        return batch;
    }

    ShaderProgram planetShader() {
        return planetShader;
    }

    ShaderProgram atmosphereShader() {
        return atmosphereShader;
    }

    ShaderProgram starGlowShader() {
        return starGlowShader;
    }

    ShaderProgram ringShader() {
        return ringShader;
    }

    ShaderProgram cloudShader() {
        return cloudShader;
    }

    ShaderProgram planet3dShader() {
        return planet3dShader;
    }

    ShaderProgram ring3dShader() {
        return ring3dShader;
    }

    ShaderProgram atmosphere3dShader() {
        return atmosphere3dShader;
    }

    ShaderProgram cloud3dShader() {
        return cloud3dShader;
    }

    Texture getCloudTexture(CelestialBody body) {
        return loadSurfaceTexture(body, body.name + " cloud", trimToNull(body.clouds.texture));
    }

    ShaderProgram dot3dShader() {
        return dot3dShader;
    }

    ShaderProgram starGlow3dShader() {
        return starGlow3dShader;
    }

    ShaderProgram overlayMask3dShader() {
        return overlayMask3dShader;
    }

    Mesh sphereMesh() {
        return sphereMesh;
    }

    Mesh dotMesh() {
        return dotMesh;
    }

    Mesh glowMesh() {
        return glowMesh;
    }

    SurfaceTextures getSurfaceTextures(CelestialBody body) {
        if (!hasSurfaceTexture(body)) {
            return null;
        }

        SurfaceTextures textures = surfaceTextures.get(body.id);
        if (textures != null) {
            return textures.base != null ? textures : null;
        }

        textures = loadSurfaceTextures(body);
        surfaceTextures.put(body.id, textures);
        return textures.base != null ? textures : null;
    }

    private boolean hasSurfaceTexture(CelestialBody body) {
        return hasText(body.texture.base) || hasText(body.texture.night);
    }

    private SurfaceTextures loadSurfaceTextures(CelestialBody body) {
        SurfaceTextures textures = new SurfaceTextures();

        String baseFile = trimToNull(body.texture.base);
        String nightFile = trimToNull(body.texture.night);

        if (nightFile == null && baseFile != null) {
            nightFile = inferNightTextureFilename(body, baseFile);
        }

        textures.base = loadSurfaceTexture(body, body.name, baseFile);
        textures.night = loadSurfaceTexture(body, body.name + " night", nightFile);

        if (textures.base == null) {
            textures.base = textures.night;
        }
        return textures;
    }

    private Texture loadSurfaceTexture(CelestialBody body, String warnKey, String fileName) {
        if (!hasText(fileName)) {
            return null;
        }

        String path = textureBasePath(body) + fileName;
        Texture tex = surfaceTextureCache.get(path);
        if (tex != null) {
            return tex;
        }

        if (!Gdx.files.internal(path).exists()) {
            if (missingWarned.add("surface:" + warnKey + ":" + path)) {
                Gdx.app.log(TAG, "Texture not found: " + path + " — falling back to colour for " + warnKey);
            }
            return null;
        }

        tex = new Texture(Gdx.files.internal(path), true);
        tex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
        surfaceTextureCache.put(path, tex);
        Gdx.app.log(TAG, "Loaded texture: " + path + " (" + tex.getWidth() + "×" + tex.getHeight() + ")");
        return tex;
    }

    private String inferNightTextureFilename(CelestialBody body, String baseFile) {
        int dot = baseFile.lastIndexOf('.');
        String stem = dot >= 0 ? baseFile.substring(0, dot) : baseFile;
        if (stem.endsWith("-nightmap")) {
            return null;
        }

        String ext = dot >= 0 ? baseFile.substring(dot) : "";
        String rootStem = stem.endsWith("-daymap")
                ? stem.substring(0, stem.length() - "-daymap".length())
                : stem;
        String variant = rootStem + "-nightmap" + ext;
        if (variant.equals(baseFile)) {
            return null;
        }
        return Gdx.files.internal(textureBasePath(body) + variant).exists() ? variant : null;
    }

    private String textureBasePath(CelestialBody body) {
        StellarSystem system = body.getSourceSystem();
        String namespace = system != null ? trimToNull(system.getTextureNamespace()) : null;
        return namespace != null ? "textures/" + namespace + "/" : "textures/";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    Texture getRingTexture(CelestialBody body) {
        if (body.ring.texture == null || body.ring.texture.isEmpty()) {
            return null;
        }
        String path = textureBasePath(body) + body.ring.texture;
        Texture tex = ringTextures.get(path);
        if (tex != null) {
            return tex;
        }

        if (!Gdx.files.internal(path).exists()) {
            Gdx.app.log(TAG, "Ring texture not found: " + path);
            return null;
        }

        tex = new Texture(Gdx.files.internal(path));
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        ringTextures.put(path, tex);
        Gdx.app.log(TAG, "Loaded ring texture: " + path);
        return tex;
    }

    Mesh getRingMesh(CelestialBody body) {
        Mesh mesh = ringMeshes.get(body.id);
        if (mesh != null) {
            return mesh;
        }
        float innerFrac = (float) (body.ring.innerRadius / body.ring.outerRadius);
        mesh = MeshFactory.createRingDisc(innerFrac, 128);
        ringMeshes.put(body.id, mesh);
        return mesh;
    }

    void dispose() {
        for (Texture tex : surfaceTextureCache.values()) {
            tex.dispose();
        }
        surfaceTextureCache.clear();
        surfaceTextures.clear();

        for (Texture tex : ringTextures.values()) {
            tex.dispose();
        }
        ringTextures.clear();

        for (Mesh mesh : ringMeshes.values()) {
            mesh.dispose();
        }
        ringMeshes.clear();

        if (sphereMesh != null)
            sphereMesh.dispose();
        if (dotMesh != null)
            dotMesh.dispose();
        if (glowMesh != null)
            glowMesh.dispose();

        if (planetShader != null)
            planetShader.dispose();
        if (atmosphereShader != null)
            atmosphereShader.dispose();
        if (starGlowShader != null)
            starGlowShader.dispose();
        if (ringShader != null)
            ringShader.dispose();
        if (cloudShader != null)
            cloudShader.dispose();
        if (planet3dShader != null)
            planet3dShader.dispose();
        if (ring3dShader != null)
            ring3dShader.dispose();
        if (atmosphere3dShader != null)
            atmosphere3dShader.dispose();
        if (cloud3dShader != null)
            cloud3dShader.dispose();
        if (dot3dShader != null)
            dot3dShader.dispose();
        if (starGlow3dShader != null)
            starGlow3dShader.dispose();
        if (overlayMask3dShader != null)
            overlayMask3dShader.dispose();

        batch.dispose();
    }

    private void compileShaders() {
        ShaderProgram.pedantic = false;

        planetShader = loadShader(SHADER_2D, "planet");
        atmosphereShader = loadShader(SHADER_2D, "atmosphere");
        starGlowShader = loadShader(SHADER_2D, "starglow");
        ringShader = loadShader(SHADER_2D, "ring");
        cloudShader = loadShader(SHADER_2D, "clouds");

        planet3dShader = loadShader(SHADER_3D, "planet");
        atmosphere3dShader = loadShader(SHADER_3D, "atmosphere");
        starGlow3dShader = loadShader(SHADER_3D, "starglow");
        ring3dShader = loadShader(SHADER_3D, "ring");
        cloud3dShader = loadShader(SHADER_3D, "clouds");
        dot3dShader = loadShader(SHADER_3D, "dot");
        overlayMask3dShader = loadShader(SHADER_3D, "overlay_mask");
    }

    private ShaderProgram loadShader(String subpath, String name) {
        String path = "shaders/" + subpath + "/" + name;
        ShaderProgram shader = new ShaderProgram(
                Gdx.files.internal(path + ".vert"),
                Gdx.files.internal(path + ".frag"));
        if (!shader.isCompiled()) {
            Gdx.app.error(TAG, path + " shader compilation FAILED:\n" + shader.getLog());
        } else {
            Gdx.app.log(TAG, path + " shader compiled OK");
        }
        return shader;
    }

    private void createGlowMesh() {
        glowMesh = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        glowMesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
    }

    private void createSphereMesh() {
        sphereMesh = MeshFactory.createSphere(64, 128);
    }

    private void createDotMesh() {
        dotMesh = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        dotMesh.setVertices(new float[] {
                -0.5f, -0.5f, 0f, 0f,
                0.5f, -0.5f, 1f, 0f,
                0.5f, 0.5f, 1f, 1f,
                -0.5f, 0.5f, 0f, 1f
        });
        dotMesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
    }
}