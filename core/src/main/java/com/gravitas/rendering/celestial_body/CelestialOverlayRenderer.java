package com.gravitas.rendering.celestial_body;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.rendering.core.ProjectedEllipse;
import com.gravitas.rendering.core.WorldCamera;

import java.util.List;

/**
 * Renders additive and translucent overlays shared by celestial bodies:
 * clouds, atmosphere glow, and star glow in both 2D and 3D views.
 */
final class CelestialOverlayRenderer {

    private static final float ATMO_RING_SCALE = 1.18f;
    private static final float STAR_ATMO_RING_SCALE = 1.14f;
    private static final float STAR_ATMO_MAX_INTENSITY = 0.22f;
    private static final float STAR_GLOW_SCALE = 1.60f;
    private static final float STAR_GLOW_NEAR_PX = 380f;
    private static final float STAR_GLOW_FAR_PX = 180f;
    private static final float CLOUD_NEAR_PX = 2000f;
    private static final float CLOUD_FAR_PX = 120f;
    private static final int DEFAULT_STAR_CORE_COLOR = 0xFFF2CCFF;
    private static final int DEFAULT_STAR_EDGE_COLOR = 0xFFD27FFF;

    private final WorldCamera camera;
    private final CelestialAssetsRenderer assets;
    private final float minTextureDiameterPx;
    private final float logDepthC;

    private final Matrix4 modelMatrix = new Matrix4();
    private final Matrix4 mvpMatrix = new Matrix4();
    private final Matrix4 bodyRotationMatrix = new Matrix4();
    private final Matrix4 bodyToCameraMatrix = new Matrix4();
    private final float[] glowVerts = new float[16];

    CelestialOverlayRenderer(WorldCamera camera, CelestialAssetsRenderer assets,
            float minTextureDiameterPx, float logDepthC) {
        this.camera = camera;
        this.assets = assets;
        this.minTextureDiameterPx = minTextureDiameterPx;
        this.logDepthC = logDepthC;
    }

    void renderClouds3D(List<SimObject> objects, float[] screenR, Matrix4 vp,
            float cloudTime, boolean celestialFx) {
        if (!celestialFx)
            return;
        int n = objects.size();
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

        ShaderProgram cloud3dShader = assets.cloud3dShader();
        cloud3dShader.bind();
        cloud3dShader.setUniformf("u_logDepthC", logDepthC);
        cloud3dShader.setUniformf("u_cloudTime", cloudTime);
        cloud3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cloud3dShader.setUniformMatrix("u_viewProj", vp);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!cb.clouds.enabled)
                continue;
            float diameter = screenR[i] * 2f;
            if (diameter < minTextureDiameterPx)
                continue;

            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - CLOUD_FAR_PX) / (CLOUD_NEAR_PX - CLOUD_FAR_PX)));
            zoomFade = zoomFade * zoomFade;

            double effectiveRadius = inflatedRadius(cb, cb.radius * 1.002, screenR[i]);
            ProjectedEllipse ellipse = camera.projectSphereEllipse(effectiveRadius, cb.x, cb.y, cb.z);
            float cx = (float) (cb.x - camera.getCamPosX());
            float cy = (float) (cb.y - camera.getCamPosY());
            float cz = (float) (cb.z - camera.getCamPosZ());
            Vector2 centerScreen = camera.worldToScreen(cb.x, cb.y, cb.z);

            beginOverlayStencilMask(cb, effectiveRadius, vp);
            cloud3dShader.bind();
            cloud3dShader.setUniformf("u_logDepthC", logDepthC);
            cloud3dShader.setUniformf("u_cloudTime", cloudTime);
            cloud3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            cloud3dShader.setUniformMatrix("u_viewProj", vp);
            cloud3dShader.setUniformf("u_center", cx, cy, cz);
            cloud3dShader.setUniformf("u_centerOffset", ellipse.centerX - centerScreen.x,
                    ellipse.centerY - centerScreen.y);
            cloud3dShader.setUniformf("u_axisX", ellipse.axisXX, ellipse.axisXY);
            cloud3dShader.setUniformf("u_axisY", ellipse.axisYX, ellipse.axisYY);
            setCloudSurfaceFrame(cloud3dShader, cb, ellipse);
            cloud3dShader.setUniformf("u_rotation", (float) cb.rotationAngle);
            cloud3dShader.setUniformf("u_zoomFade", zoomFade);

            float cr = ((cb.clouds.color >> 24) & 0xFF) / 255f;
            float cg = ((cb.clouds.color >> 16) & 0xFF) / 255f;
            float cbb = ((cb.clouds.color >> 8) & 0xFF) / 255f;
            cloud3dShader.setUniformf("u_cloudColor", cr, cg, cbb);

            assets.dotMesh().render(cloud3dShader, GL20.GL_TRIANGLES);
            endOverlayStencilMask();
        }

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
    }

    void renderAtmosphereGlow3D(List<SimObject> objects, float[] screenR, Matrix4 vp) {
        int n = objects.size();
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        ShaderProgram atmosphere3dShader = assets.atmosphere3dShader();
        atmosphere3dShader.bind();
        atmosphere3dShader.setUniformf("u_logDepthC", logDepthC);
        atmosphere3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        atmosphere3dShader.setUniformMatrix("u_viewProj", vp);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!shouldRenderAtmosphereGlow(cb))
                continue;
            if (screenR[i] * 2f < minTextureDiameterPx)
                continue;

            float intensity = atmosphereGlowIntensity(cb, screenR[i]);
            if (intensity <= 0f)
                continue;

            ProjectedEllipse ellipse = camera
                    .projectSphereEllipse(inflatedRadius(cb, cb.radius, screenR[i]), cb.x, cb.y, cb.z);
            float glowScale = atmosphereGlowScale(cb);
            float glowRadius = screenR[i] * glowScale;
            float innerFrac = screenR[i] / glowRadius;
            float cx = (float) (cb.x - camera.getCamPosX());
            float cy = (float) (cb.y - camera.getCamPosY());
            float cz = (float) (cb.z - camera.getCamPosZ());
            Vector2 centerScreen = camera.worldToScreen(cb.x, cb.y, cb.z);

            beginOverlayStencilMask(cb, inflatedRadius(cb, cb.radius * glowScale, screenR[i]), vp);
            atmosphere3dShader.bind();
            atmosphere3dShader.setUniformf("u_logDepthC", logDepthC);
            atmosphere3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            atmosphere3dShader.setUniformMatrix("u_viewProj", vp);
            atmosphere3dShader.setUniformf("u_center", cx, cy, cz);
            atmosphere3dShader.setUniformf("u_centerOffset", ellipse.centerX - centerScreen.x,
                    ellipse.centerY - centerScreen.y);
            atmosphere3dShader.setUniformf("u_axisX", ellipse.axisXX * glowScale, ellipse.axisXY * glowScale);
            atmosphere3dShader.setUniformf("u_axisY", ellipse.axisYX * glowScale, ellipse.axisYY * glowScale);
            setAtmosphereGlowColor(atmosphere3dShader, cb);
            atmosphere3dShader.setUniformf("u_intensity", intensity);
            atmosphere3dShader.setUniformf("u_innerRadius", innerFrac);

            assets.dotMesh().render(atmosphere3dShader, GL20.GL_TRIANGLES);
            endOverlayStencilMask();
        }

        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    void renderStarGlow3D(List<SimObject> objects, float[] screenR, Matrix4 vp) {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);

        ShaderProgram starGlow3dShader = assets.starGlow3dShader();
        starGlow3dShader.bind();
        starGlow3dShader.setUniformf("u_logDepthC", logDepthC);
        starGlow3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        starGlow3dShader.setUniformMatrix("u_viewProj", vp);

        int n = objects.size();
        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (cb.bodyType != CelestialBody.BodyType.STAR)
                continue;
            float diameter = screenR[i] * 2f;
            if (diameter < minTextureDiameterPx)
                continue;

            float glowRadius = screenR[i] * STAR_GLOW_SCALE;
            float innerFrac = screenR[i] / glowRadius;
            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - STAR_GLOW_FAR_PX)
                            / (STAR_GLOW_NEAR_PX - STAR_GLOW_FAR_PX)));

            float cx = (float) (cb.x - camera.getCamPosX());
            float cy = (float) (cb.y - camera.getCamPosY());
            float cz = (float) (cb.z - camera.getCamPosZ());
            double effectiveRadius = inflatedRadius(cb, cb.radius, screenR[i]);
            ProjectedEllipse ellipse = camera.projectSphereEllipse(effectiveRadius, cb.x, cb.y, cb.z);
            Vector2 centerScreen = camera.worldToScreen(cb.x, cb.y, cb.z);

            beginOverlayStencilMask(cb, inflatedRadius(cb, cb.radius * STAR_GLOW_SCALE, screenR[i]), vp);
            starGlow3dShader.bind();
            starGlow3dShader.setUniformf("u_logDepthC", logDepthC);
            starGlow3dShader.setUniformf("u_viewport", Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            starGlow3dShader.setUniformMatrix("u_viewProj", vp);
            starGlow3dShader.setUniformf("u_center", cx, cy, cz);
            starGlow3dShader.setUniformf("u_centerOffset",
                    ellipse.centerX - centerScreen.x,
                    ellipse.centerY - centerScreen.y);
            starGlow3dShader.setUniformf("u_axisX",
                    ellipse.axisXX * STAR_GLOW_SCALE,
                    ellipse.axisXY * STAR_GLOW_SCALE);
            starGlow3dShader.setUniformf("u_axisY",
                    ellipse.axisYX * STAR_GLOW_SCALE,
                    ellipse.axisYY * STAR_GLOW_SCALE);
            setRgbUniform(starGlow3dShader, "u_coreColor", starGlowCoreColor(cb));
            setRgbUniform(starGlow3dShader, "u_edgeColor", starGlowEdgeColor(cb));
            starGlow3dShader.setUniformf("u_innerRadius", innerFrac);
            starGlow3dShader.setUniformf("u_zoomFade", zoomFade);

            assets.dotMesh().render(starGlow3dShader, GL20.GL_TRIANGLES);
            endOverlayStencilMask();
        }

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    void renderClouds2D(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR,
            float cloudTime, boolean celestialFx) {
        if (!celestialFx)
            return;
        int n = objects.size();
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShaderProgram cloudShader = assets.cloudShader();
        cloudShader.bind();
        cloudShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);
        cloudShader.setUniformf("u_cloudTime", cloudTime);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!cb.clouds.enabled)
                continue;
            float diameter = screenR[i] * 2f;
            if (diameter < minTextureDiameterPx)
                continue;

            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - CLOUD_FAR_PX) / (CLOUD_NEAR_PX - CLOUD_FAR_PX)));
            zoomFade = zoomFade * zoomFade;

            setTopViewSurfaceFrame(cloudShader, cb);
            cloudShader.setUniformf("u_rotation", (float) cb.rotationAngle);
            cloudShader.setUniformf("u_zoomFade", zoomFade);
            float cr = ((cb.clouds.color >> 24) & 0xFF) / 255f;
            float cg = ((cb.clouds.color >> 16) & 0xFF) / 255f;
            float cbb = ((cb.clouds.color >> 8) & 0xFF) / 255f;
            cloudShader.setUniformf("u_cloudColor", cr, cg, cbb);

            fillQuad(screenX[i], screenY[i], screenR[i]);
            assets.glowMesh().setVertices(glowVerts);
            assets.glowMesh().render(cloudShader, GL20.GL_TRIANGLES);
        }
    }

    void renderAtmosphereGlow2D(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR) {
        int n = objects.size();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);

        ShaderProgram atmosphereShader = assets.atmosphereShader();
        atmosphereShader.bind();
        atmosphereShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (!shouldRenderAtmosphereGlow(cb))
                continue;
            if (screenR[i] * 2f < minTextureDiameterPx)
                continue;

            float glowRadius = screenR[i] * atmosphereGlowScale(cb);
            float innerFrac = screenR[i] / glowRadius;
            float intensity = atmosphereGlowIntensity(cb, screenR[i]);
            if (intensity <= 0f)
                continue;

            setAtmosphereGlowColor(atmosphereShader, cb);
            atmosphereShader.setUniformf("u_intensity", intensity);
            atmosphereShader.setUniformf("u_innerRadius", innerFrac);

            fillQuad(screenX[i], screenY[i], glowRadius);
            assets.glowMesh().setVertices(glowVerts);
            assets.glowMesh().render(atmosphereShader, GL20.GL_TRIANGLES);
        }

        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    void renderStarGlow2D(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE);

        ShaderProgram starGlowShader = assets.starGlowShader();
        starGlowShader.bind();
        starGlowShader.setUniformMatrix("u_projTrans", camera.getCamera().combined);

        int n = objects.size();
        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            if (cb.bodyType != CelestialBody.BodyType.STAR)
                continue;
            float diameter = screenR[i] * 2f;
            if (diameter < minTextureDiameterPx)
                continue;

            float glowRadius = screenR[i] * STAR_GLOW_SCALE;
            float innerFrac = screenR[i] / glowRadius;
            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - STAR_GLOW_FAR_PX)
                            / (STAR_GLOW_NEAR_PX - STAR_GLOW_FAR_PX)));

            setRgbUniform(starGlowShader, "u_coreColor", starGlowCoreColor(cb));
            setRgbUniform(starGlowShader, "u_edgeColor", starGlowEdgeColor(cb));
            starGlowShader.setUniformf("u_innerRadius", innerFrac);
            starGlowShader.setUniformf("u_zoomFade", zoomFade);

            fillQuad(screenX[i], screenY[i], glowRadius);
            assets.glowMesh().setVertices(glowVerts);
            assets.glowMesh().render(starGlowShader, GL20.GL_TRIANGLES);
        }

        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void setCloudSurfaceFrame(ShaderProgram shader, CelestialBody cb, ProjectedEllipse ellipse) {
        camera.buildBodyRotationMatrix(bodyRotationMatrix, cb, cb.rotationAngle);
        bodyToCameraMatrix.set(camera.getViewMatrix()).mul(bodyRotationMatrix);

        float[] m = bodyToCameraMatrix.val;

        float localXx = m[Matrix4.M00];
        float localXy = m[Matrix4.M10];
        float localXz = m[Matrix4.M20];
        float localYx = m[Matrix4.M01];
        float localYy = m[Matrix4.M11];
        float localYz = m[Matrix4.M21];
        float localZx = m[Matrix4.M02];
        float localZy = m[Matrix4.M12];
        float localZz = m[Matrix4.M22];

        float axisXLen = (float) Math.sqrt(ellipse.axisXX * ellipse.axisXX + ellipse.axisXY * ellipse.axisXY);
        float axisYLen = (float) Math.sqrt(ellipse.axisYX * ellipse.axisYX + ellipse.axisYY * ellipse.axisYY);

        float billboardXx = axisXLen > 1e-5f ? ellipse.axisXX / axisXLen : 1f;
        float billboardXy = axisXLen > 1e-5f ? ellipse.axisXY / axisXLen : 0f;
        float billboardYx = axisYLen > 1e-5f ? ellipse.axisYX / axisYLen : 0f;
        float billboardYy = axisYLen > 1e-5f ? ellipse.axisYY / axisYLen : 1f;

        shader.setUniformf("u_billboardToBodyRow0",
                localXx * billboardXx + localXy * billboardXy,
                localXx * billboardYx + localXy * billboardYy,
                localXz);
        shader.setUniformf("u_billboardToBodyRow1",
                localYx * billboardXx + localYy * billboardXy,
                localYx * billboardYx + localYy * billboardYy,
                localYz);
        shader.setUniformf("u_billboardToBodyRow2",
                localZx * billboardXx + localZy * billboardXy,
                localZx * billboardYx + localZy * billboardYy,
                localZz);
    }

    private void setTopViewSurfaceFrame(ShaderProgram shader, CelestialBody cb) {
        camera.buildBodyRotationMatrix(bodyRotationMatrix, cb, cb.rotationAngle);

        float[] m = bodyRotationMatrix.val;
        shader.setUniformf("u_worldToBodyRow0", m[Matrix4.M00], m[Matrix4.M10], m[Matrix4.M20]);
        shader.setUniformf("u_worldToBodyRow1", m[Matrix4.M01], m[Matrix4.M11], m[Matrix4.M21]);
        shader.setUniformf("u_worldToBodyRow2", m[Matrix4.M02], m[Matrix4.M12], m[Matrix4.M22]);
    }

    private boolean shouldRenderAtmosphereGlow(CelestialBody cb) {
        return cb.bodyType == CelestialBody.BodyType.STAR || cb.hasAtmosphere();
    }

    private float atmosphereGlowScale(CelestialBody cb) {
        return cb.bodyType == CelestialBody.BodyType.STAR ? STAR_ATMO_RING_SCALE : ATMO_RING_SCALE;
    }

    private float atmosphereGlowIntensity(CelestialBody cb, float screenRadius) {
        if (cb.bodyType == CelestialBody.BodyType.STAR) {
            float diameter = screenRadius * 2f;
            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - STAR_GLOW_FAR_PX)
                            / (STAR_GLOW_NEAR_PX - STAR_GLOW_FAR_PX)));
            float closeupFade = 1.0f - zoomFade;
            closeupFade = closeupFade * closeupFade;
            return STAR_ATMO_MAX_INTENSITY * closeupFade;
        }

        float density = (float) cb.atmosphereDensitySeaLevel;
        float intensity = (float) (0.15 + 0.15 * Math.log1p(density * 2.0));
        return Math.min(0.50f, Math.max(0.15f, intensity));
    }

    private void setAtmosphereGlowColor(ShaderProgram shader, CelestialBody cb) {
        int color = cb.bodyType == CelestialBody.BodyType.STAR && cb.color.edge != 0
                ? cb.color.edge
                : (cb.color.glow != 0 ? cb.color.glow : cb.color.base);
        setRgbUniform(shader, "u_glowColor", color);
    }

    private int starGlowCoreColor(CelestialBody cb) {
        return cb.color.core != 0 ? cb.color.core : DEFAULT_STAR_CORE_COLOR;
    }

    private int starGlowEdgeColor(CelestialBody cb) {
        return cb.color.edge != 0 ? cb.color.edge : DEFAULT_STAR_EDGE_COLOR;
    }

    private void setRgbUniform(ShaderProgram shader, String uniform, int color) {
        shader.setUniformf(uniform,
                ((color >> 24) & 0xFF) / 255f,
                ((color >> 16) & 0xFF) / 255f,
                ((color >> 8) & 0xFF) / 255f);
    }

    private void beginOverlayStencilMask(CelestialBody cb, double maskRadius, Matrix4 vp) {
        Gdx.gl.glEnable(GL20.GL_STENCIL_TEST);
        Gdx.gl.glClearStencil(0);
        Gdx.gl.glStencilMask(0xFF);
        Gdx.gl.glClear(GL20.GL_STENCIL_BUFFER_BIT);
        Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
        Gdx.gl.glColorMask(false, false, false, false);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        camera.buildModelMatrix(modelMatrix,
                cb.x, cb.y, cb.z,
                maskRadius,
                cb,
                0);
        mvpMatrix.set(vp).mul(modelMatrix);

        ShaderProgram overlayMask3dShader = assets.overlayMask3dShader();
        overlayMask3dShader.bind();
        overlayMask3dShader.setUniformf("u_logDepthC", logDepthC);
        overlayMask3dShader.setUniformMatrix("u_mvp", mvpMatrix);
        assets.sphereMesh().render(overlayMask3dShader, GL20.GL_TRIANGLES);

        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glStencilMask(0x00);
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
    }

    private void endOverlayStencilMask() {
        Gdx.gl.glStencilMask(0xFF);
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);
    }

    private double inflatedRadius(CelestialBody body, double baseRadius, float screenRadius) {
        if (screenRadius <= 0f)
            return baseRadius;
        double desired = camera.screenToWorldSphereRadius(screenRadius, body.x, body.y, body.z);
        double ratio = body.radius > 0 ? baseRadius / body.radius : 1.0;
        return Math.max(baseRadius, desired * ratio);
    }

    private void fillQuad(float cx, float cy, float halfSize) {
        float x1 = cx - halfSize;
        float y1 = cy - halfSize;
        float x2 = cx + halfSize;
        float y2 = cy + halfSize;
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
}