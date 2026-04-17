package com.gravitas.rendering.celestial_body;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.gravitas.entities.CelestialBody;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns GPU-side resources used by celestial rendering: shaders, meshes,
 * textures, and the shared sprite batch.
 */
final class CelestialAssetsRenderer {

    private static final String TAG = "CelestialAssetsRenderer";
    private static final String SHADER_2D = "celestial_body/2d";
    private static final String SHADER_3D = "celestial_body/3d";

    private final String textureBasePath;
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
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<String, Texture> ringTextures = new HashMap<>();
    private final Set<String> missingWarned = new HashSet<>();

    CelestialAssetsRenderer(String systemFolder) {
        this.textureBasePath = "textures/" + systemFolder + "/";
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

    Texture getTexture(CelestialBody body) {
        if (body.textureFile == null || body.textureFile.isEmpty()) {
            return null;
        }
        Texture tex = textures.get(body.name);
        if (tex != null) {
            return tex;
        }

        String path = textureBasePath + body.textureFile;
        if (!Gdx.files.internal(path).exists()) {
            if (missingWarned.add(body.name)) {
                Gdx.app.log(TAG, "Texture not found: " + path + " — falling back to colour for " + body.name);
            }
            return null;
        }

        tex = new Texture(Gdx.files.internal(path), true);
        tex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
        textures.put(body.name, tex);
        Gdx.app.log(TAG, "Loaded texture: " + path + " (" + tex.getWidth() + "×" + tex.getHeight() + ")");
        return tex;
    }

    Texture getRingTexture(CelestialBody body) {
        if (body.ring.texture == null || body.ring.texture.isEmpty()) {
            return null;
        }
        Texture tex = ringTextures.get(body.ring.texture);
        if (tex != null) {
            return tex;
        }

        String path = textureBasePath + body.ring.texture;
        if (!Gdx.files.internal(path).exists()) {
            Gdx.app.log(TAG, "Ring texture not found: " + path);
            return null;
        }

        tex = new Texture(Gdx.files.internal(path));
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        tex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        ringTextures.put(body.ring.texture, tex);
        Gdx.app.log(TAG, "Loaded ring texture: " + path);
        return tex;
    }

    Mesh getRingMesh(CelestialBody body) {
        Mesh mesh = ringMeshes.get(body.name);
        if (mesh != null) {
            return mesh;
        }
        float innerFrac = (float) (body.ring.innerRadius / body.ring.outerRadius);
        mesh = MeshFactory.createRingDisc(innerFrac, 128);
        ringMeshes.put(body.name, mesh);
        return mesh;
    }

    void dispose() {
        for (Texture tex : textures.values()) {
            tex.dispose();
        }
        textures.clear();

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