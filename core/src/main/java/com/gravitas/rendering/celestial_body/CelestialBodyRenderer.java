package com.gravitas.rendering.celestial_body;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.util.RenderUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders celestial bodies with realistic textures and effects.
 *
 * Supports two rendering paths:
 * <ul>
 * <li><b>TOP_VIEW</b> (2D): Equirectangular textures projected onto
 * screen-aligned
 * quads via fragment shaders. Original rendering path.</li>
 * <li><b>FREE_CAM</b> (3D): True UV-sphere meshes with model-view-projection
 * transforms, tilted ring discs, and spherical atmosphere/cloud shells.</li>
 * </ul>
 */
public class CelestialBodyRenderer {

    private static final float MIN_TEXTURE_DIAMETER_PX = 8f;

    /**
     * Logarithmic depth constant: 2.0 / log2(farClip + 1.0).
     * Matches the formula in the 3D vertex shaders and FAR_CLIP in WorldCamera.
     */
    private static final float LOG_DEPTH_C = (float) (2.0 / (Math.log(1e16 + 1.0) / Math.log(2.0)));

    private final ShapeRenderer shapeRenderer;
    private final WorldCamera camera;
    private final CelestialAssetsRenderer assets;
    private final CelestialOverlayRenderer overlayRenderer;

    private final Matrix4 modelMatrix = new Matrix4();
    private final Matrix4 mvpMatrix = new Matrix4();
    private final Matrix4 bodyRotationMatrix = new Matrix4();

    private final float[] glowVerts = new float[16];

    private float cloudTime = 0f;
    private boolean celestialFx = true;

    public void setCelestialFx(boolean enabled) {
        this.celestialFx = enabled;
    }

    public CelestialBodyRenderer(WorldCamera camera, ShapeRenderer shapeRenderer, String systemFolder) {
        this.camera = camera;
        this.shapeRenderer = shapeRenderer;
        this.assets = new CelestialAssetsRenderer(systemFolder);
        this.overlayRenderer = new CelestialOverlayRenderer(camera, assets, MIN_TEXTURE_DIAMETER_PX, LOG_DEPTH_C);
    }

    // -------------------------------------------------------------------------
    // Rendering — mode router
    // -------------------------------------------------------------------------

    public void render(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR) {
        cloudTime += Gdx.graphics.getDeltaTime();

        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM) {
            render3D(objects, screenX, screenY, screenR);
        } else {
            render2D(objects, screenX, screenY, screenR);
        }
    }

    /**
     * Compute an inflated world-radius so the 3D mesh matches the visual-scale
     * screen size. Uses the camera's inverse projection to convert the desired
     * screen-pixel radius directly into a world radius — no ratio needed.
     */
    private double inflatedRadius(CelestialBody cb, double baseRadius, float screenR) {
        // World radius that would project to screenR pixels at this body's position.
        double desired = camera.screenToWorldSphereRadius(screenR, cb.x, cb.y, cb.z);
        // Scale proportionally (e.g. rings, atmosphere use a different baseRadius than
        // cb.radius).
        double ratio = (cb.radius > 0) ? baseRadius / cb.radius : 1.0;
        return Math.max(baseRadius, desired * ratio);
    }

    // =========================================================================
    // 3D rendering path (FREE_CAM)
    // =========================================================================

    private void render3D(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR) {
        int n = objects.size();
        boolean[] handled = new boolean[n];

        Matrix4 vp = new Matrix4(camera.getPerspectiveProjection()).mul(camera.getViewMatrix());

        // Compute view direction (camera forward in world space).
        float vdx = (float) (camera.getFocusX() - camera.getCamPosX());
        float vdy = (float) (camera.getFocusY() - camera.getCamPosY());
        float vdz = (float) (camera.getFocusZ() - camera.getCamPosZ());
        float vdLen = (float) Math.sqrt(vdx * vdx + vdy * vdy + vdz * vdz);
        if (vdLen > 0) {
            vdx /= vdLen;
            vdy /= vdLen;
            vdz /= vdLen;
        }

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        renderTexturedBodies3D(objects, screenR, vp, handled);
        renderSmallBodies3D(objects, screenX, screenY, screenR, vp, vdx, vdy, vdz, handled);
        renderRings3D(objects, screenR, vp);
        overlayRenderer.renderClouds3D(objects, screenR, vp, cloudTime, celestialFx);
        overlayRenderer.renderAtmosphereGlow3D(objects, screenR, vp);

        // Star glow (3D billboard with log depth — depth buffer occludes behind
        // planets)
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        overlayRenderer.renderStarGlow3D(objects, screenR, vp);
        Gdx.gl.glDepthMask(true);

        // Restore state
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Pass 1: Textured planet/star surfaces as UV-sphere meshes (≥8px). */
    private void renderTexturedBodies3D(List<SimObject> objects, float[] screenR,
            Matrix4 vp, boolean[] handled) {
        int n = objects.size();
        ShaderProgram planet3dShader = assets.planet3dShader();
        planet3dShader.bind();
        planet3dShader.setUniformf("u_logDepthC", LOG_DEPTH_C);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (screenR[i] * 2f < MIN_TEXTURE_DIAMETER_PX)
                continue;

            Texture tex = assets.getTexture(cb);
            if (tex == null)
                continue;
            handled[i] = true;

            double effectiveRadius = inflatedRadius(cb, cb.radius, screenR[i]);
            camera.buildModelMatrix(modelMatrix,
                    cb.x, cb.y, cb.z,
                    effectiveRadius,
                    cb,
                    0);
            mvpMatrix.set(vp).mul(modelMatrix);

            tex.bind(0);
            planet3dShader.setUniformi("u_texture", 0);
            planet3dShader.setUniformMatrix("u_mvp", mvpMatrix);
            planet3dShader.setUniformMatrix("u_model", modelMatrix);
            planet3dShader.setUniformf("u_isStar",
                    cb.bodyType == CelestialBody.BodyType.STAR ? 1.0f : 0.0f);
            float br = ((cb.color.base >> 24) & 0xFF) / 255f;
            float bg = ((cb.color.base >> 16) & 0xFF) / 255f;
            float bb = ((cb.color.base >> 8) & 0xFF) / 255f;
            planet3dShader.setUniformf("u_baseColor", br, bg, bb);
            setViewDirUniform(planet3dShader, cb);
            planet3dShader.setUniformf("u_rotation", (float) cb.rotationAngle);

            assets.sphereMesh().render(planet3dShader, GL20.GL_TRIANGLES);
        }
    }

    /**
     * Pass 1a: Small/untextured bodies as camera-facing billboard dot quads (<8px).
     */
    private void renderSmallBodies3D(List<SimObject> objects,
            float[] screenX, float[] screenY, float[] screenR,
            Matrix4 vp, float vdx, float vdy, float vdz, boolean[] handled) {
        int n = objects.size();
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        ShaderProgram dot3dShader = assets.dot3dShader();
        dot3dShader.bind();
        dot3dShader.setUniformf("u_logDepthC", LOG_DEPTH_C);
        dot3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        Map<SimObject, Integer> parentIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            parentIndex.put(objects.get(i), i);
        }

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (handled[i])
                continue;

            float cx = (float) (cb.x - camera.getCamPosX());
            float cy = (float) (cb.y - camera.getCamPosY());
            float cz = (float) (cb.z - camera.getCamPosZ());

            float behindDot = cx * vdx + cy * vdy + cz * vdz;
            if (behindDot < 0) {
                handled[i] = true;
                continue;
            }

            if (RenderUtils.shouldSkipOverlappingMoon(obj, i, screenX, screenY, screenR, parentIndex)) {
                handled[i] = true;
                continue;
            }

            float br = ((cb.color.base >> 24) & 0xFF) / 255f;
            float bg = ((cb.color.base >> 16) & 0xFF) / 255f;
            float bb = ((cb.color.base >> 8) & 0xFF) / 255f;

            dot3dShader.setUniformMatrix("u_viewProj", vp);
            dot3dShader.setUniformf("u_center", cx, cy, cz);
            dot3dShader.setUniformf("u_color", br, bg, bb);
            dot3dShader.setUniformf("u_size", Math.max(2f, screenR[i] * 2f));

            assets.dotMesh().render(dot3dShader, GL20.GL_TRIANGLES);
            handled[i] = true;
        }
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
    }

    /** Pass 1b: Planetary rings as tilted disc meshes. */
    private void renderRings3D(List<SimObject> objects, float[] screenR, Matrix4 vp) {
        int n = objects.size();
        ShaderProgram ring3dShader = assets.ring3dShader();
        ring3dShader.bind();
        ring3dShader.setUniformf("u_logDepthC", LOG_DEPTH_C);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!cb.hasRings())
                continue;
            if (screenR[i] * 2f < MIN_TEXTURE_DIAMETER_PX)
                continue;

            double effectiveRadius = inflatedRadius(cb, cb.ring.outerRadius, screenR[i]);
            camera.buildModelMatrix(modelMatrix,
                    cb.x, cb.y, cb.z,
                    effectiveRadius,
                    cb,
                    0);
            mvpMatrix.set(vp).mul(modelMatrix);

            Texture ringTex = assets.getRingTexture(cb);
            if (ringTex != null) {
                ringTex.bind(0);
                ring3dShader.setUniformi("u_texture", 0);
                ring3dShader.setUniformf("u_hasTexture", 1.0f);
                ring3dShader.setUniformf("u_ringColor", 1.0f, 1.0f, 1.0f);
            } else {
                int color = cb.ring.color != 0 ? cb.ring.color : cb.color.base;
                ring3dShader.setUniformf("u_hasTexture", 0.0f);
                ring3dShader.setUniformf("u_ringColor",
                        ((color >> 24) & 0xFF) / 255f,
                        ((color >> 16) & 0xFF) / 255f,
                        ((color >> 8) & 0xFF) / 255f);
            }
            ring3dShader.setUniformf("u_opacity", cb.ring.opacity);
            ring3dShader.setUniformMatrix("u_mvp", mvpMatrix);

            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            assets.getRingMesh(cb).render(ring3dShader, GL20.GL_TRIANGLES);
            Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        }
    }

    // =========================================================================
    // 2D rendering path (TOP_VIEW)
    // =========================================================================

    private void render2D(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR) {
        renderTexturedBodies2D(objects, screenX, screenY, screenR);
        renderRings2D(objects, screenX, screenY, screenR);
        overlayRenderer.renderClouds2D(objects, screenX, screenY, screenR, cloudTime, celestialFx);
        overlayRenderer.renderAtmosphereGlow2D(objects, screenX, screenY, screenR);
        renderSmallBodies2D(objects, screenX, screenY, screenR);
        overlayRenderer.renderStarGlow2D(objects, screenX, screenY, screenR);
    }

    /**
     * (≥8px).
     */
    private void renderTexturedBodies2D(List<SimObject> objects,
            float[] screenX, float[] screenY, float[] screenR) {
        int n = objects.size();
        SpriteBatch batch = assets.batch();
        ShaderProgram planetShader = assets.planetShader();
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

            Texture tex = assets.getTexture(cb);
            if (tex == null)
                continue;

            planetShader.setUniformf("u_rotation", (float) cb.rotationAngle);
            planetShader.setUniformf("u_isStar",
                    cb.bodyType == CelestialBody.BodyType.STAR ? 1.0f : 0.0f);
            setTopViewSurfaceFrame(planetShader, cb, false);
            float br = ((cb.color.base >> 24) & 0xFF) / 255f;
            float bg = ((cb.color.base >> 16) & 0xFF) / 255f;
            float bb = ((cb.color.base >> 8) & 0xFF) / 255f;
            planetShader.setUniformf("u_baseColor", br, bg, bb);

            batch.draw(tex,
                    screenX[i] - screenR[i], screenY[i] - screenR[i],
                    diameter, diameter);
            batch.flush();
        }

        batch.end();
    }

    /** Pass 1b: Planetary rings as screen-aligned annulus quads. */
    private void renderRings2D(List<SimObject> objects,
            float[] screenX, float[] screenY, float[] screenR) {
        int n = objects.size();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        ShaderProgram ringShader = assets.ringShader();
        ringShader.bind();
        ringShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!cb.hasRings())
                continue;
            if (screenR[i] * 2f < MIN_TEXTURE_DIAMETER_PX)
                continue;

            float ringOuterScreen = screenR[i] * (float) (cb.ring.outerRadius / cb.radius);
            float ringInnerScreen = screenR[i] * (float) (cb.ring.innerRadius / cb.radius);
            RingProjection topViewRing = projectTopViewRing(cb, ringOuterScreen);
            float innerFrac = ringInnerScreen / Math.max(1e-6f, ringOuterScreen);
            float outerFrac = 1.0f;

            Texture ringTex = assets.getRingTexture(cb);
            if (ringTex != null) {
                ringTex.bind(0);
                ringShader.setUniformi("u_texture", 0);
                ringShader.setUniformf("u_hasTexture", 1.0f);
                ringShader.setUniformf("u_ringColor", 1.0f, 1.0f, 1.0f);
            } else {
                int color = cb.ring.color != 0 ? cb.ring.color : cb.color.base;
                ringShader.setUniformf("u_hasTexture", 0.0f);
                ringShader.setUniformf("u_ringColor",
                        ((color >> 24) & 0xFF) / 255f,
                        ((color >> 16) & 0xFF) / 255f,
                        ((color >> 8) & 0xFF) / 255f);
            }
            ringShader.setUniformf("u_innerRadius", innerFrac);
            ringShader.setUniformf("u_outerRadius", outerFrac);
            ringShader.setUniformf("u_opacity", cb.ring.opacity);
            ringShader.setUniformf("u_center", screenX[i], screenY[i]);
            ringShader.setUniformf("u_invAxisX", topViewRing.invAxisXx, topViewRing.invAxisXy);
            ringShader.setUniformf("u_invAxisY", topViewRing.invAxisYx, topViewRing.invAxisYy);
            ringShader.setUniformf("u_bodyRadius", (float) (cb.radius / cb.ring.outerRadius));
            ringShader.setUniformf("u_localViewZ", topViewRing.localViewZx, topViewRing.localViewZy);

            fillRect(screenX[i], screenY[i], topViewRing.boundsHalfWidth, topViewRing.boundsHalfHeight);
            assets.glowMesh().setVertices(glowVerts);
            assets.glowMesh().render(ringShader, GL20.GL_TRIANGLES);
        }
    }

    /**
     * Pass 4: Small/untextured bodies as flat-colour circles (<8px or no texture).
     */
    private void renderSmallBodies2D(List<SimObject> objects,
            float[] screenX, float[] screenY, float[] screenR) {
        int n = objects.size();

        Map<SimObject, Integer> objIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            objIndex.put(objects.get(i), i);
        }

        shapeRenderer.setProjectionMatrix(camera.getCamera().combined);
        shapeRenderer.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            // Skip bodies already rendered as textured (≥8px with texture).
            if (screenR[i] * 2f >= MIN_TEXTURE_DIAMETER_PX && assets.getTexture(cb) != null)
                continue;

            if (RenderUtils.shouldSkipOverlappingMoon(obj, i, screenX, screenY, screenR, objIndex))
                continue;

            float r = ((cb.color.base >> 24) & 0xFF) / 255f;
            float g = ((cb.color.base >> 16) & 0xFF) / 255f;
            float b = ((cb.color.base >> 8) & 0xFF) / 255f;

            // Star glow ring (flat fallback).
            if (cb.bodyType == CelestialBody.BodyType.STAR) {
                shapeRenderer.setColor(r, g, b, 0.15f);
                shapeRenderer.circle(screenX[i], screenY[i], screenR[i] * 1.5f, 32);
            }

            shapeRenderer.setColor(r, g, b, 1f);
            shapeRenderer.circle(screenX[i], screenY[i], screenR[i], adaptiveSegments(screenR[i]));
        }

        shapeRenderer.end();
    }

    /** Reduce circle segment count for tiny objects to save draw calls. */
    private static int adaptiveSegments(float screenRadius) {
        if (screenRadius < 3)
            return 8;
        if (screenRadius < 10)
            return 16;
        return 32;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void fillRect(float cx, float cy, float halfWidth, float halfHeight) {
        float x1 = cx - halfWidth, y1 = cy - halfHeight;
        float x2 = cx + halfWidth, y2 = cy + halfHeight;
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
    }

    private void setTopViewSurfaceFrame(ShaderProgram shader, CelestialBody cb, boolean includeRotation) {
        camera.buildBodyRotationMatrix(bodyRotationMatrix,
                cb,
                includeRotation ? cb.rotationAngle : 0.0);

        float[] m = bodyRotationMatrix.val;
        shader.setUniformf("u_worldToBodyRow0", m[Matrix4.M00], m[Matrix4.M10], m[Matrix4.M20]);
        shader.setUniformf("u_worldToBodyRow1", m[Matrix4.M01], m[Matrix4.M11], m[Matrix4.M21]);
        shader.setUniformf("u_worldToBodyRow2", m[Matrix4.M02], m[Matrix4.M12], m[Matrix4.M22]);
    }

    private RingProjection projectTopViewRing(CelestialBody cb, float outerScreenRadius) {
        camera.buildBodyRotationMatrix(bodyRotationMatrix,
                cb,
                0.0);

        float[] m = bodyRotationMatrix.val;
        float axisXx = m[Matrix4.M00] * outerScreenRadius;
        float axisXy = m[Matrix4.M10] * outerScreenRadius;
        float axisYx = m[Matrix4.M02] * outerScreenRadius;
        float axisYy = m[Matrix4.M12] * outerScreenRadius;

        float det = axisXx * axisYy - axisXy * axisYx;
        float invDet = Math.abs(det) > 1e-6f ? 1f / det : 0f;

        RingProjection projection = new RingProjection();
        projection.invAxisXx = axisYy * invDet;
        projection.invAxisXy = -axisYx * invDet;
        projection.invAxisYx = -axisXy * invDet;
        projection.invAxisYy = axisXx * invDet;
        projection.localViewZx = m[Matrix4.M20];
        projection.localViewZy = m[Matrix4.M22];
        projection.boundsHalfWidth = (float) Math.sqrt(axisXx * axisXx + axisYx * axisYx) * 1.05f;
        projection.boundsHalfHeight = (float) Math.sqrt(axisXy * axisXy + axisYy * axisYy) * 1.05f;
        return projection;
    }

    private static final class RingProjection {
        float invAxisXx;
        float invAxisXy;
        float invAxisYx;
        float invAxisYy;
        float localViewZx;
        float localViewZy;
        float boundsHalfWidth;
        float boundsHalfHeight;
    }

    private void setViewDirUniform(ShaderProgram shader, CelestialBody cb) {
        float vdx = (float) (cb.x - camera.getCamPosX());
        float vdy = (float) (cb.y - camera.getCamPosY());
        float vdz = (float) (cb.z - camera.getCamPosZ());
        float len = (float) Math.sqrt(vdx * vdx + vdy * vdy + vdz * vdz);
        if (len > 0f) {
            vdx /= len;
            vdy /= len;
            vdz /= len;
        }
        shader.setUniformf("u_viewDir", vdx, vdy, vdz);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void dispose() {
        assets.dispose();
    }

    public float getMinTextureDiameter() {
        return MIN_TEXTURE_DIAMETER_PX;
    }
}
