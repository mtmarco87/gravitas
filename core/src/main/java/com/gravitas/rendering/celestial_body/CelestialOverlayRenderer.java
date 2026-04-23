package com.gravitas.rendering.celestial_body;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.core.SimObject;
import com.gravitas.rendering.core.ProjectedEllipse;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.settings.FxSettings;
import com.gravitas.settings.enums.CloudFxMode;
import com.gravitas.util.AngleUtils;
import com.gravitas.util.GeometryUtils;

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
    private static final float EARTH_ATMOSPHERE_DENSITY = 1.225f;
    private static final float DENSE_ATMOSPHERE_DENSITY = 5.3f;
    private static final float CLOUD_NEAR_PX = 2000f;
    private static final float CLOUD_FAR_PX = 120f;
    private static final double CLOUD_ROTATION_WRAP = Math.PI * 2.0 * 200.0;
    private static final int DEFAULT_STAR_CORE_COLOR = 0xFFF2CCFF;
    private static final int DEFAULT_STAR_EDGE_COLOR = 0xFFD27FFF;

    private final WorldCamera camera;
    private final CelestialAssetsRenderer assets;
    private final float minTextureDiameterPx;
    private final float logDepthC;

    private final Matrix4 modelMatrix = new Matrix4();
    private final Matrix4 mvpMatrix = new Matrix4();
    private final Matrix4 bodyRotationMatrix = new Matrix4();
    private final Matrix4 lightRotationMatrix = new Matrix4();
    private final Matrix4 bodyToCameraMatrix = new Matrix4();
    private final float[] glowVerts = new float[16];
    private final float[] scratchWorldLightDir = new float[3];
    private final float[] scratchLocalLightDir = new float[3];

    CelestialOverlayRenderer(WorldCamera camera, CelestialAssetsRenderer assets,
            float minTextureDiameterPx, float logDepthC) {
        this.camera = camera;
        this.assets = assets;
        this.minTextureDiameterPx = minTextureDiameterPx;
        this.logDepthC = logDepthC;
    }

    void renderClouds3D(List<SimObject> objects, float[] screenR, Matrix4 vp,
            float cloudTime, CloudFxMode cloudFxMode,
            FxSettings fxSettings) {
        if (cloudFxMode == null || cloudFxMode.isOff())
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
            boolean proceduralEnabled = cloudFxMode.proceduralEnabled() && cb.clouds.hasProcedural();
            boolean textureEnabled = cloudFxMode.texturesEnabled() && cb.clouds.hasTexture();
            if (!cb.clouds.configured || (!proceduralEnabled && !textureEnabled))
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
            // Wider local wrap keeps cloud spin stable: avoids precision loss and wrap
            // jumps.
            float cloudRotation = (float) AngleUtils.wrapAngle(cb.getAccumulatedSpinAngle(), CLOUD_ROTATION_WRAP);
            cloud3dShader.setUniformf("u_cloudRotation", cloudRotation);
            cloud3dShader.setUniformf("u_zoomFade", zoomFade);
            cloud3dShader.setUniformf("u_enableCloudProcedural", proceduralEnabled ? 1.0f : 0.0f);
            cloud3dShader.setUniformf("u_cloudProceduralPreset", cb.clouds.procedural.shaderValue());
            applyCloudFxUniforms(cloud3dShader, fxSettings);
            setLocalLightDirUniform(cloud3dShader, cb);
            bindCloudTexture(cloud3dShader, cb, textureEnabled);

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

    void renderAtmosphereGlow3D(List<SimObject> objects, float[] screenR, Matrix4 vp,
            FxSettings fxSettings) {
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
            setCloudSurfaceFrame(atmosphere3dShader, cb, ellipse);
            setLocalLightDirUniform(atmosphere3dShader, cb);
            setAtmosphereGlowColor(atmosphere3dShader, cb);
            atmosphere3dShader.setUniformf("u_isStar", cb.bodyType == BodyType.STAR ? 1.0f : 0.0f);
            atmosphere3dShader.setUniformf("u_denseAtmosphereFactor", atmosphereDenseFactor(cb));
            applyAtmosphereFxUniforms(atmosphere3dShader, fxSettings);
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
            if (cb.bodyType != BodyType.STAR)
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
            float cloudTime, CloudFxMode cloudFxMode,
            FxSettings fxSettings) {
        if (cloudFxMode == null || cloudFxMode.isOff())
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
            boolean proceduralEnabled = cloudFxMode.proceduralEnabled() && cb.clouds.hasProcedural();
            boolean textureEnabled = cloudFxMode.texturesEnabled() && cb.clouds.hasTexture();
            if (!cb.clouds.configured || (!proceduralEnabled && !textureEnabled))
                continue;
            float diameter = screenR[i] * 2f;
            if (diameter < minTextureDiameterPx)
                continue;

            float zoomFade = 1.0f - Math.min(1.0f,
                    Math.max(0.0f, (diameter - CLOUD_FAR_PX) / (CLOUD_NEAR_PX - CLOUD_FAR_PX)));
            zoomFade = zoomFade * zoomFade;

            setTopViewSurfaceFrame(cloudShader, cb);
            // Wider local wrap keeps cloud spin stable: avoids precision loss and wrap
            // jumps.
            float cloudRotation = (float) AngleUtils.wrapAngle(cb.getAccumulatedSpinAngle(), CLOUD_ROTATION_WRAP);
            cloudShader.setUniformf("u_cloudRotation", cloudRotation);
            cloudShader.setUniformf("u_zoomFade", zoomFade);
            cloudShader.setUniformf("u_enableCloudProcedural", proceduralEnabled ? 1.0f : 0.0f);
            cloudShader.setUniformf("u_cloudProceduralPreset", cb.clouds.procedural.shaderValue());
            applyCloudFxUniforms(cloudShader, fxSettings);
            setLocalLightDirUniform(cloudShader, cb);
            bindCloudTexture(cloudShader, cb, textureEnabled);
            float cr = ((cb.clouds.color >> 24) & 0xFF) / 255f;
            float cg = ((cb.clouds.color >> 16) & 0xFF) / 255f;
            float cbb = ((cb.clouds.color >> 8) & 0xFF) / 255f;
            cloudShader.setUniformf("u_cloudColor", cr, cg, cbb);

            fillQuad(screenX[i], screenY[i], screenR[i]);
            assets.glowMesh().setVertices(glowVerts);
            assets.glowMesh().render(cloudShader, GL20.GL_TRIANGLES);
        }
    }

    void renderAtmosphereGlow2D(List<SimObject> objects, float[] screenX, float[] screenY, float[] screenR,
            FxSettings fxSettings) {
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

            setTopViewSurfaceFrame(atmosphereShader, cb);
            setLocalLightDirUniform(atmosphereShader, cb);
            setAtmosphereGlowColor(atmosphereShader, cb);
            atmosphereShader.setUniformf("u_isStar", cb.bodyType == BodyType.STAR ? 1.0f : 0.0f);
            atmosphereShader.setUniformf("u_denseAtmosphereFactor", atmosphereDenseFactor(cb));
            applyAtmosphereFxUniforms(atmosphereShader, fxSettings);
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
            if (cb.bodyType != BodyType.STAR)
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

    private void bindCloudTexture(ShaderProgram shader, CelestialBody cb, boolean textureEnabled) {
        Texture cloudTexture = textureEnabled ? assets.getCloudTexture(cb) : null;
        if (cloudTexture != null) {
            cloudTexture.bind(0);
            shader.setUniformi("u_cloudTexture", 0);
            shader.setUniformf("u_hasCloudTexture", 1.0f);
        } else {
            shader.setUniformi("u_cloudTexture", 0);
            shader.setUniformf("u_hasCloudTexture", 0.0f);
        }
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    private void applyCloudFxUniforms(ShaderProgram shader, FxSettings fxSettings) {
        FxSettings settings = fxSettings != null ? fxSettings : new FxSettings();
        shader.setUniformi("u_cloudDayNightMode", settings.isCloudDayNightActive() ? 1 : 0);
        shader.setUniformi("u_cloudTerminatorMode", settings.getCloudTerminatorMode().shaderValue());
        shader.setUniformi("u_cloudCompositingMode", settings.getCloudCompositingMode().shaderValue());
        shader.setUniformf("u_cloudTextureAlphaWeight", settings.getCloudTextureAlphaWeight());
        shader.setUniformf("u_cloudProceduralAlphaWeight", settings.getCloudProceduralAlphaWeight());
        shader.setUniformf("u_cloudProceduralTextureCoupling", settings.getCloudProceduralTextureCoupling());
    }

    private void applyAtmosphereFxUniforms(ShaderProgram shader, FxSettings fxSettings) {
        FxSettings settings = fxSettings != null ? fxSettings : new FxSettings();
        shader.setUniformi("u_atmosphereDayNightMode", settings.isAtmosphereDayNightActive() ? 1 : 0);
        shader.setUniformf("u_atmosphereNightOuterFloor", settings.getAtmosphereNightOuterFloor());
        shader.setUniformf("u_atmosphereNightInnerFloor", settings.getAtmosphereNightInnerFloor());
        shader.setUniformf("u_atmosphereDenseNightOuterFloor", settings.getAtmosphereDenseNightOuterFloor());
        shader.setUniformf("u_atmosphereDenseNightInnerFloor", settings.getAtmosphereDenseNightInnerFloor());
    }

    private void setLocalLightDirUniform(ShaderProgram shader, CelestialBody cb) {
        if (!computeLocalLightDir(cb, scratchLocalLightDir)) {
            shader.setUniformf("u_lightDirLocal", 0f, 1f, 0f);
            return;
        }

        shader.setUniformf("u_lightDirLocal",
                scratchLocalLightDir[0], scratchLocalLightDir[1], scratchLocalLightDir[2]);
    }

    private boolean computeLocalLightDir(CelestialBody cb, float[] out) {
        if (!computeWorldLightDir(cb, scratchWorldLightDir)) {
            out[0] = 0f;
            out[1] = 1f;
            out[2] = 0f;
            return false;
        }

        camera.buildObjectOrientationMatrix(lightRotationMatrix, cb);
        float[] m = lightRotationMatrix.val;
        out[0] = scratchWorldLightDir[0] * m[Matrix4.M00]
                + scratchWorldLightDir[1] * m[Matrix4.M10]
                + scratchWorldLightDir[2] * m[Matrix4.M20];
        out[1] = scratchWorldLightDir[0] * m[Matrix4.M01]
                + scratchWorldLightDir[1] * m[Matrix4.M11]
                + scratchWorldLightDir[2] * m[Matrix4.M21];
        out[2] = scratchWorldLightDir[0] * m[Matrix4.M02]
                + scratchWorldLightDir[1] * m[Matrix4.M12]
                + scratchWorldLightDir[2] * m[Matrix4.M22];

        if (!GeometryUtils.normalize(out[0], out[1], out[2], out)) {
            out[0] = 0f;
            out[1] = 1f;
            out[2] = 0f;
            return false;
        }
        return true;
    }

    private boolean computeWorldLightDir(CelestialBody cb, float[] out) {
        CelestialBody lightSource = resolvePrimaryLightSource(cb);
        if (lightSource == null || lightSource == cb) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1f;
            return false;
        }

        float ldx = (float) (lightSource.x - cb.x);
        float ldy = (float) (lightSource.y - cb.y);
        float ldz = (float) (lightSource.z - cb.z);
        if (!GeometryUtils.normalize(ldx, ldy, ldz, out)) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1f;
            return false;
        }
        return true;
    }

    private CelestialBody resolvePrimaryLightSource(CelestialBody body) {
        CelestialBody root = body;
        while (root.parent != null) {
            root = root.parent;
        }
        return root.bodyType == BodyType.STAR ? root : null;
    }

    private void setCloudSurfaceFrame(ShaderProgram shader, CelestialBody cb, ProjectedEllipse ellipse) {
        camera.buildObjectOrientationMatrix(bodyRotationMatrix, cb);
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

        float axisXLen = (float) Math.sqrt(GeometryUtils.lengthSq(ellipse.axisXX, ellipse.axisXY));
        float axisYLen = (float) Math.sqrt(GeometryUtils.lengthSq(ellipse.axisYX, ellipse.axisYY));

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
        camera.buildObjectOrientationMatrix(bodyRotationMatrix, cb);

        float[] m = bodyRotationMatrix.val;
        shader.setUniformf("u_worldToBodyRow0", m[Matrix4.M00], m[Matrix4.M10], m[Matrix4.M20]);
        shader.setUniformf("u_worldToBodyRow1", m[Matrix4.M01], m[Matrix4.M11], m[Matrix4.M21]);
        shader.setUniformf("u_worldToBodyRow2", m[Matrix4.M02], m[Matrix4.M12], m[Matrix4.M22]);
    }

    private boolean shouldRenderAtmosphereGlow(CelestialBody cb) {
        return cb.bodyType == BodyType.STAR || cb.hasAtmosphere();
    }

    private float atmosphereGlowScale(CelestialBody cb) {
        return cb.bodyType == BodyType.STAR ? STAR_ATMO_RING_SCALE : ATMO_RING_SCALE;
    }

    private float atmosphereGlowIntensity(CelestialBody cb, float screenRadius) {
        if (cb.bodyType == BodyType.STAR) {
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

    private float atmosphereDenseFactor(CelestialBody cb) {
        if (cb.bodyType == BodyType.STAR)
            return 0f;

        float density = (float) cb.atmosphereDensitySeaLevel;
        if (density <= EARTH_ATMOSPHERE_DENSITY)
            return 0f;

        float range = Math.max(1e-4f, DENSE_ATMOSPHERE_DENSITY - EARTH_ATMOSPHERE_DENSITY);
        float normalized = (density - EARTH_ATMOSPHERE_DENSITY) / range;
        normalized = Math.min(1f, Math.max(0f, normalized));
        return normalized * normalized * (3f - 2f * normalized);
    }

    private void setAtmosphereGlowColor(ShaderProgram shader, CelestialBody cb) {
        int color = cb.bodyType == BodyType.STAR && cb.color.edge != 0
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

        camera.buildAxisFrameModelMatrix(modelMatrix,
                cb.x, cb.y, cb.z,
                maskRadius,
                cb);
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
