package com.gravitas.rendering.core;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.Universe;
import com.gravitas.entities.bodies.Belt;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.entities.bodies.celestial_body.enums.BodyType;
import com.gravitas.entities.core.SimObject;
import com.gravitas.rendering.celestial_body.CelestialBodyRenderer;
import com.gravitas.rendering.orbit.OrbitOcclusionMask;
import com.gravitas.rendering.orbit.OrbitPredictor;
import com.gravitas.rendering.orbit.OrbitTrail;
import com.gravitas.settings.AppSettings;
import com.gravitas.settings.FxSettings;
import com.gravitas.settings.OverlaySettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Renders all SimObjects in 2D using libGDX ShapeRenderer.
 *
 * Rendering layers (draw order):
 * 1. Orbit trails (line strips, faded)
 * 2. Celestial bodies (filled circles)
 * 3. Body labels (handled by HUD via BitmapFont)
 *
 * A new OrbitTrail is created automatically for each object on first render.
 * Trails are sampled at every physics tick via {@link #recordTrailPositions()}.
 */
public class SimRenderer {

    private enum OcclusionVisibilityMode {
        STANDARD,
        CLEAR
    }

    private static final int OCCLUSION_SUBDIVISION_DEPTH = 5;

    private static final float TRAIL_ALPHA_MAX = 0.8f;
    private static final float TRAIL_ALPHA_MIN = 0.05f;
    private static final float SPIN_AXIS_MIN_BODY_RADIUS_PX = 6f;
    private static final float SPIN_AXIS_HALF_LENGTH_SCALE = 1.45f;
    private static final float SPIN_AXIS_LINE_ALPHA = 0.70f;
    private static final float SPIN_AXIS_MARKER_SCALE = 0.5f;
    private static final float SPIN_AXIS_MARKER_MIN_RADIUS_PX = 2.2f;
    private static final float SPIN_AXIS_MARKER_MAX_RADIUS_PX = 5.5f;
    private static final float NORTH_MARKER_ALPHA = 0.98f;
    private static final float ORBIT_NORMAL_LINE_ALPHA = 0.78f;
    private static final float ORBIT_NORMAL_MARKER_ALPHA = 0.98f;
    private static final float PRIME_MERIDIAN_LINE_ALPHA = 0.76f;
    private static final float PRIME_MERIDIAN_MARKER_ALPHA = 0.98f;
    private static final float PRIME_MERIDIAN_VISIBILITY_BIAS_PX = 1.5f;
    private static final double TOP_VIEW_AXIS_ALIGNMENT_EPSILON = 1e-6;

    /**
     * Visual-scale mode selector.
     * true = logarithmic: VS_LOG_A * ln(radius_km) + VS_LOG_B
     * false = power-law: VS_POW_BASE * (radius_km ^ VS_POW_EXP)
     */
    private static final boolean LOGARITHMIC_SCALE = true;

    // --- Logarithmic params ---
    private static final float VS_LOG_A = 1.7f;
    private static final float VS_LOG_B = -10.5f;

    // --- Power-law params ---
    private static final float VS_POW_BASE = 0.45f;
    private static final float VS_POW_EXP = 0.25f;

    private static final float VISUAL_SCALE_MIN_PX = 2f;
    /**
     * Pixel tolerance for the primary-body overlap check. Visual scale stays ON
     * as long as two primaries overlap by less than this many pixels.
     */
    private static final float VS_OVERLAP_TOLERANCE_PX = 3f;

    private final ShapeRenderer shapeRenderer;
    private final WorldCamera camera;
    private final Universe universe;
    private final OverlaySettings overlaySettings;
    private final Map<String, OrbitTrail> trails = new HashMap<>();
    private final double[] scratchSpinAxis = new double[3];
    private final double[] scratchOrbitNormal = new double[3];
    private final double[] scratchPrimeMeridian = new double[3];
    private final List<SpinAxisMarker> spinAxisMarkers = new ArrayList<>();

    /**
     * Texture-based celestial body renderer (handles spherical projection,
     * rotation, glow).
     */
    private CelestialBodyRenderer celestialBodyRenderer;

    /** Cached to detect visual-scale mode changes and clear the overlap latch. */
    private boolean cachedVisualScaleMode;

    /**
     * Latch: once an overlap is detected the visual scale stays inhibited
     * (all bodies drawn at true size) until the user scrolls/zooms
     * or toggles the V key, which calls resetVsInhibit().
     */
    private boolean vsInhibited = false;

    /** Scratch arrays re-used each frame to avoid allocation. */
    private float[] trailCoords = new float[OrbitTrail.DEFAULT_CAPACITY * 2];

    /** Statistical belts (asteroid belt, Kuiper belt). */
    private final List<BeltParticles> beltParticles = new ArrayList<>();

    /** Pre-generated belt particle positions in world metres. */
    private static class BeltParticles {
        final double[] angle; // current orbital angle (radians)
        final double[] radius; // orbital radius (metres)
        final double[] zOff; // vertical offset above/below ecliptic (metres)
        final double[] angVel; // angular velocity (rad/s) from Kepler: sqrt(GM/r³)
        final Color color;
        final int count;
        final SimObject parent;

        // GM_Sun (m³/s²)
        private static final double GM_SUN = 1.32712440018e20;

        /**
         * Z-scatter thickness as a fraction of each particle's orbital radius.
         * Real asteroid belt has ~±10° inclination spread ≈ ±17% of r;
         * we use a smaller visual fraction for a cleaner look.
         */
        private static final double Z_SCATTER_FRACTION = 0.08;

        BeltParticles(Belt belt) {
            this.count = belt.particleCount;
            this.parent = belt.parent;
            this.angle = new double[count];
            this.radius = new double[count];
            this.zOff = new double[count];
            this.angVel = new double[count];
            int rgba = belt.color;
            float r = ((rgba >> 24) & 0xFF) / 255f;
            float g = ((rgba >> 16) & 0xFF) / 255f;
            float b = ((rgba >> 8) & 0xFF) / 255f;
            this.color = new Color(r, g, b, 1f);

            // Deterministic scatter within the annulus.
            Random rng = new Random(belt.name.hashCode());
            for (int i = 0; i < count; i++) {
                angle[i] = rng.nextDouble() * 2.0 * Math.PI;
                // Uniform distribution in area: r = sqrt(rand * (R²-r²) + r²)
                double r2Inner = belt.innerRadius * belt.innerRadius;
                double r2Outer = belt.outerRadius * belt.outerRadius;
                radius[i] = Math.sqrt(rng.nextDouble() * (r2Outer - r2Inner) + r2Inner);
                // Kepler: ω = sqrt(GM / r³)
                angVel[i] = Math.sqrt(GM_SUN / (radius[i] * radius[i] * radius[i]));
                // Scatter above/below ecliptic: Gaussian-ish from (rand-0.5)
                zOff[i] = (rng.nextDouble() - 0.5) * 2.0 * Z_SCATTER_FRACTION * radius[i];
            }
        }

        /** Advance all particle angles by simDt seconds of simulation time. */
        void advance(double simDt) {
            for (int i = 0; i < count; i++) {
                angle[i] += angVel[i] * simDt;
            }
        }
    }

    private static final class SpinAxisMarker {
        float screenX;
        float screenY;
        float radius;
        int segments;
    }

    private void rebuildBeltParticles() {
        beltParticles.clear();
        for (Belt b : universe.getBelts()) {
            beltParticles.add(new BeltParticles(b));
        }
    }

    public SimRenderer(ShapeRenderer shapeRenderer, WorldCamera camera, Universe universe, AppSettings settings) {
        this.shapeRenderer = shapeRenderer;
        this.camera = camera;
        this.universe = Objects.requireNonNull(universe, "universe");
        AppSettings appSettings = Objects.requireNonNull(settings, "settings");
        FxSettings fxSettings = appSettings.getFxSettings();
        this.overlaySettings = appSettings.getOverlaySettings();
        this.cachedVisualScaleMode = overlaySettings.isVisualScaleMode();
        this.celestialBodyRenderer = new CelestialBodyRenderer(camera, shapeRenderer);
        this.celestialBodyRenderer.setFxSettings(fxSettings);
        rebuildBeltParticles();
    }

    /** Called by input handler on scroll/zoom so the VS latch can re-evaluate. */
    public void resetVsInhibit() {
        vsInhibited = false;
    }

    public boolean isTrailsEnabled() {
        return overlaySettings.isShowTrails();
    }

    public boolean isVisualScaleMode() {
        return overlaySettings.isVisualScaleMode();
    }

    // -------------------------------------------------------------------------
    // Called each physics step (not necessarily each render frame)
    // -------------------------------------------------------------------------

    public void recordTrailPositions() {
        List<SimObject> objects = universe.getSimObjects();
        for (SimObject obj : objects) {
            if (!obj.active)
                continue;
            OrbitTrail trail = trails.computeIfAbsent(obj.id, id -> new OrbitTrail());
            trail.record(obj.x, obj.y, obj.z);
        }
    }

    // -------------------------------------------------------------------------
    // Called each render frame
    // -------------------------------------------------------------------------

    public void render(double simDt, OrbitPredictor orbitPredictor, double timeWarpFactor) {
        List<SimObject> objects = universe.getSimObjects();
        renderBelts(simDt, objects);

        boolean renderPredictorBeforeBodies = orbitPredictor != null && orbitPredictor.shouldRenderBeforeBodies();
        if (renderPredictorBeforeBodies) {
            orbitPredictor.render(objects, timeWarpFactor, null);
        }

        int n = objects.size();
        float[] sx = new float[n];
        float[] sy = new float[n];
        float[] sr = new float[n];
        computeScreenLayout(objects, sx, sy, sr);

        renderBodies(objects, sx, sy, sr, simDt);

        OrbitOcclusionMask occlusionMask = new OrbitOcclusionMask(camera, objects, sr);
        if (overlaySettings.isShowSpinAxis()) {
            renderSpinAxisOverlays(objects, occlusionMask, sr);
        }
        if (overlaySettings.isShowOrbitNormal()) {
            renderOrbitNormalOverlays(objects, occlusionMask, sr);
        }
        if (overlaySettings.isShowPrimeMeridian()) {
            renderPrimeMeridianOverlays(objects, occlusionMask, sr);
        }
        if (overlaySettings.isShowTrails()) {
            renderTrails(objects, occlusionMask, sr);
        }
        if (orbitPredictor != null && !renderPredictorBeforeBodies) {
            orbitPredictor.render(objects, timeWarpFactor,
                    camera.getMode() == CameraMode.FREE_CAM ? occlusionMask : null);
        }
    }

    public void prewarmTopViewAssets() {
        celestialBodyRenderer.prewarmTopViewAssets(universe.getSimObjects());
    }

    // -------------------------------------------------------------------------
    // Belt rendering
    // -------------------------------------------------------------------------

    private void renderBelts(double simDt, List<SimObject> objects) {
        if (beltParticles.isEmpty())
            return;

        // Advance orbital angles
        for (BeltParticles bp : beltParticles) {
            bp.advance(simDt);
        }

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        float vpW = camera.getCamera().viewportWidth;
        float vpH = camera.getCamera().viewportHeight;

        for (BeltParticles bp : beltParticles) {
            // Find the parent body this belt orbits around.
            double parentX = 0, parentY = 0, parentZ = 0;
            if (bp.parent != null) {
                parentX = bp.parent.x;
                parentY = bp.parent.y;
                parentZ = bp.parent.z;
            }

            for (int i = 0; i < bp.count; i++) {
                double wx = parentX + bp.radius[i] * Math.cos(bp.angle[i]);
                double wy = parentY + bp.radius[i] * Math.sin(bp.angle[i]);
                double wz = parentZ + bp.zOff[i];

                Vector2 sp = camera.worldToScreen(wx, wy, wz);
                float sx = sp.x;
                float sy = sp.y;

                // Frustum cull: skip particles outside the screen.
                if (sx < -2 || sx > vpW + 2 || sy < -2 || sy > vpH + 2) {
                    continue;
                }

                shapeRenderer.setColor(bp.color.r, bp.color.g, bp.color.b, 0.35f);
                shapeRenderer.circle(sx, sy, 1.2f, 4);
            }
        }
        shapeRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Spin-axis overlay rendering
    // -------------------------------------------------------------------------

    private void renderSpinAxisOverlays(List<SimObject> objects, OrbitOcclusionMask occlusionMask,
            float[] screenRadii) {
        spinAxisMarkers.clear();
        boolean topView = camera.getMode() == CameraMode.TOP_VIEW;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
            SimObject obj = objects.get(objectIndex);
            if (!(obj instanceof CelestialBody body) || !body.active) {
                continue;
            }

            float screenRadius = objectIndex < screenRadii.length ? screenRadii[objectIndex] : 0f;
            if (screenRadius < SPIN_AXIS_MIN_BODY_RADIUS_PX) {
                continue;
            }

            double selfClipRadius = trailSelfClipRadius(body, screenRadius);
            if (selfClipRadius <= 0.0) {
                continue;
            }

            double halfLength = selfClipRadius * SPIN_AXIS_HALF_LENGTH_SCALE;
            camera.getBodySpinAxis(body, scratchSpinAxis);

            double southX = body.x - scratchSpinAxis[0] * halfLength;
            double southY = body.y - scratchSpinAxis[1] * halfLength;
            double southZ = body.z - scratchSpinAxis[2] * halfLength;
            double northX = body.x + scratchSpinAxis[0] * halfLength;
            double northY = body.y + scratchSpinAxis[1] * halfLength;
            double northZ = body.z + scratchSpinAxis[2] * halfLength;

            if (!topView
                    && camera.depthOf(body.x, body.y, body.z) <= 0.0) {
                continue;
            }

            int accentColor = body.color.glow != 0 ? body.color.glow : body.color.base;
            float accentR = ((accentColor >> 24) & 0xFF) / 255f;
            float accentG = ((accentColor >> 16) & 0xFF) / 255f;
            float accentB = ((accentColor >> 8) & 0xFF) / 255f;
            shapeRenderer.setColor(lerp(accentR, 1f, 0.32f),
                    lerp(accentG, 1f, 0.32f),
                    lerp(accentB, 1f, 0.32f),
                    SPIN_AXIS_LINE_ALPHA);

            boolean northVisible;
            if (topView) {
                northVisible = drawTopViewSpinAxisSegments(body,
                        selfClipRadius,
                        halfLength,
                        scratchSpinAxis[0],
                        scratchSpinAxis[1],
                        scratchSpinAxis[2],
                        occlusionMask);
            } else {
                drawVisibleTrailSegmentClipped(southX, southY, southZ,
                        northX, northY, northZ,
                        body.x, body.y, body.z,
                        selfClipRadius,
                        occlusionMask);
                northVisible = isVisiblePoint(northX, northY, northZ, occlusionMask);
            }

            if (!northVisible) {
                continue;
            }

            Vector2 northScreen = camera.worldToScreen(northX, northY, northZ);
            if (!isFiniteScreenPoint(northScreen)) {
                continue;
            }

            float markerRadius = SPIN_AXIS_MARKER_SCALE * clamp(screenRadius * 0.18f,
                    SPIN_AXIS_MARKER_MIN_RADIUS_PX,
                    SPIN_AXIS_MARKER_MAX_RADIUS_PX);
            int markerSegments = adaptiveSegments(markerRadius);

            SpinAxisMarker marker = new SpinAxisMarker();
            marker.screenX = northScreen.x;
            marker.screenY = northScreen.y;
            marker.radius = markerRadius;
            marker.segments = markerSegments;
            spinAxisMarkers.add(marker);
        }
        shapeRenderer.end();

        if (spinAxisMarkers.isEmpty()) {
            return;
        }

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (SpinAxisMarker marker : spinAxisMarkers) {
            shapeRenderer.setColor(1f, 1f, 1f, NORTH_MARKER_ALPHA);
            shapeRenderer.circle(marker.screenX, marker.screenY,
                    marker.radius,
                    marker.segments);
        }
        shapeRenderer.end();
    }

    private void renderOrbitNormalOverlays(List<SimObject> objects, OrbitOcclusionMask occlusionMask,
            float[] screenRadii) {
        spinAxisMarkers.clear();
        boolean topView = camera.getMode() == CameraMode.TOP_VIEW;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
            SimObject obj = objects.get(objectIndex);
            if (!(obj instanceof CelestialBody body) || !body.active || body.parent == null) {
                continue;
            }

            float screenRadius = objectIndex < screenRadii.length ? screenRadii[objectIndex] : 0f;
            if (screenRadius < SPIN_AXIS_MIN_BODY_RADIUS_PX) {
                continue;
            }

            double selfClipRadius = trailSelfClipRadius(body, screenRadius);
            if (selfClipRadius <= 0.0) {
                continue;
            }

            double halfLength = selfClipRadius * SPIN_AXIS_HALF_LENGTH_SCALE;
            scratchOrbitNormal[0] = body.orbitFrame.normalX;
            scratchOrbitNormal[1] = body.orbitFrame.normalY;
            scratchOrbitNormal[2] = body.orbitFrame.normalZ;

            double southX = body.x - scratchOrbitNormal[0] * halfLength;
            double southY = body.y - scratchOrbitNormal[1] * halfLength;
            double southZ = body.z - scratchOrbitNormal[2] * halfLength;
            double northX = body.x + scratchOrbitNormal[0] * halfLength;
            double northY = body.y + scratchOrbitNormal[1] * halfLength;
            double northZ = body.z + scratchOrbitNormal[2] * halfLength;

            if (!topView && camera.depthOf(body.x, body.y, body.z) <= 0.0) {
                continue;
            }

            shapeRenderer.setColor(0.96f, 0.78f, 0.34f, ORBIT_NORMAL_LINE_ALPHA);

            boolean northVisible;
            if (topView) {
                northVisible = drawTopViewSpinAxisSegments(body,
                        selfClipRadius,
                        halfLength,
                        scratchOrbitNormal[0],
                        scratchOrbitNormal[1],
                        scratchOrbitNormal[2],
                        occlusionMask);
            } else {
                drawVisibleTrailSegmentClipped(southX, southY, southZ,
                        northX, northY, northZ,
                        body.x, body.y, body.z,
                        selfClipRadius,
                        occlusionMask);
                northVisible = isVisiblePoint(northX, northY, northZ, occlusionMask);
            }

            if (!northVisible) {
                continue;
            }

            Vector2 northScreen = camera.worldToScreen(northX, northY, northZ);
            if (!isFiniteScreenPoint(northScreen)) {
                continue;
            }

            float markerRadius = SPIN_AXIS_MARKER_SCALE * clamp(screenRadius * 0.18f,
                    SPIN_AXIS_MARKER_MIN_RADIUS_PX,
                    SPIN_AXIS_MARKER_MAX_RADIUS_PX);
            int markerSegments = adaptiveSegments(markerRadius);

            SpinAxisMarker marker = new SpinAxisMarker();
            marker.screenX = northScreen.x;
            marker.screenY = northScreen.y;
            marker.radius = markerRadius;
            marker.segments = markerSegments;
            spinAxisMarkers.add(marker);
        }
        shapeRenderer.end();

        if (spinAxisMarkers.isEmpty()) {
            return;
        }

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (SpinAxisMarker marker : spinAxisMarkers) {
            shapeRenderer.setColor(0.98f, 0.85f, 0.48f, ORBIT_NORMAL_MARKER_ALPHA);
            shapeRenderer.circle(marker.screenX, marker.screenY,
                    marker.radius,
                    marker.segments);
        }
        shapeRenderer.end();
    }

    private void renderPrimeMeridianOverlays(List<SimObject> objects, OrbitOcclusionMask occlusionMask,
            float[] screenRadii) {
        spinAxisMarkers.clear();
        boolean topView = camera.getMode() == CameraMode.TOP_VIEW;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
            SimObject obj = objects.get(objectIndex);
            if (!(obj instanceof CelestialBody body) || !body.active) {
                continue;
            }

            float screenRadius = objectIndex < screenRadii.length ? screenRadii[objectIndex] : 0f;
            if (screenRadius < SPIN_AXIS_MIN_BODY_RADIUS_PX) {
                continue;
            }

            double selfClipRadius = trailSelfClipRadius(body, screenRadius);
            if (selfClipRadius <= 0.0) {
                continue;
            }

            double halfLength = selfClipRadius * SPIN_AXIS_HALF_LENGTH_SCALE;
            body.getPrimeMeridianAxis(scratchPrimeMeridian);

            double southX = body.x - scratchPrimeMeridian[0] * halfLength;
            double southY = body.y - scratchPrimeMeridian[1] * halfLength;
            double southZ = body.z - scratchPrimeMeridian[2] * halfLength;
            double northX = body.x + scratchPrimeMeridian[0] * halfLength;
            double northY = body.y + scratchPrimeMeridian[1] * halfLength;
            double northZ = body.z + scratchPrimeMeridian[2] * halfLength;

            if (!topView && camera.depthOf(body.x, body.y, body.z) <= 0.0) {
                continue;
            }

            shapeRenderer.setColor(0.42f, 0.86f, 0.98f, PRIME_MERIDIAN_LINE_ALPHA);

            boolean northVisible;
            if (topView) {
                northVisible = drawTopViewSpinAxisSegments(body,
                        selfClipRadius,
                        halfLength,
                        scratchPrimeMeridian[0],
                        scratchPrimeMeridian[1],
                        scratchPrimeMeridian[2],
                        occlusionMask);
            } else {
                drawVisibleTrailSegmentClipped(southX, southY, southZ,
                        northX, northY, northZ,
                        body.x, body.y, body.z,
                        selfClipRadius,
                        occlusionMask,
                        OcclusionVisibilityMode.CLEAR);
                northVisible = isPointVisibleForMode(
                        northX,
                        northY,
                        northZ,
                        occlusionMask,
                        OcclusionVisibilityMode.CLEAR);
            }

            if (!northVisible) {
                continue;
            }

            Vector2 northScreen = camera.worldToScreen(northX, northY, northZ);
            if (!isFiniteScreenPoint(northScreen)) {
                continue;
            }

            float markerRadius = SPIN_AXIS_MARKER_SCALE * clamp(screenRadius * 0.18f,
                    SPIN_AXIS_MARKER_MIN_RADIUS_PX,
                    SPIN_AXIS_MARKER_MAX_RADIUS_PX);
            int markerSegments = adaptiveSegments(markerRadius);

            SpinAxisMarker marker = new SpinAxisMarker();
            marker.screenX = northScreen.x;
            marker.screenY = northScreen.y;
            marker.radius = markerRadius;
            marker.segments = markerSegments;
            spinAxisMarkers.add(marker);
        }
        shapeRenderer.end();

        if (spinAxisMarkers.isEmpty()) {
            return;
        }

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (SpinAxisMarker marker : spinAxisMarkers) {
            shapeRenderer.setColor(0.70f, 0.94f, 1.0f, PRIME_MERIDIAN_MARKER_ALPHA);
            shapeRenderer.circle(marker.screenX, marker.screenY,
                    marker.radius,
                    marker.segments);
        }
        shapeRenderer.end();
    }

    private boolean drawTopViewSpinAxisSegments(CelestialBody body,
            double bodyRadius,
            double halfLength,
            double axisX,
            double axisY,
            double axisZ,
            OrbitOcclusionMask occlusionMask) {
        double projectedAxisLength = Math.sqrt(axisX * axisX + axisY * axisY);

        if (Math.abs(axisZ) <= TOP_VIEW_AXIS_ALIGNMENT_EPSILON) {
            drawSpinAxisSubSegment(body, axisX, axisY, axisZ, -halfLength, -bodyRadius, occlusionMask);
            drawSpinAxisSubSegment(body, axisX, axisY, axisZ, bodyRadius, halfLength, occlusionMask);
            return true;
        }

        double projectedDiskExit = projectedAxisLength > TOP_VIEW_AXIS_ALIGNMENT_EPSILON
                ? bodyRadius / projectedAxisLength
                : Double.POSITIVE_INFINITY;

        if (axisZ > 0.0) {
            drawSpinAxisSubSegment(body, axisX, axisY, axisZ, bodyRadius, halfLength, occlusionMask);
            if (projectedDiskExit < halfLength) {
                drawSpinAxisSubSegment(body, axisX, axisY, axisZ, -halfLength, -projectedDiskExit, occlusionMask);
            }
            return true;
        }

        drawSpinAxisSubSegment(body, axisX, axisY, axisZ, -halfLength, -bodyRadius, occlusionMask);
        if (projectedDiskExit < halfLength) {
            drawSpinAxisSubSegment(body, axisX, axisY, axisZ, projectedDiskExit, halfLength, occlusionMask);
            return true;
        }
        return false;
    }

    private void drawSpinAxisSubSegment(CelestialBody body,
            double axisX,
            double axisY,
            double axisZ,
            double t0,
            double t1,
            OrbitOcclusionMask occlusionMask) {
        if (t1 - t0 <= 1e-9) {
            return;
        }

        double wx0 = body.x + axisX * t0;
        double wy0 = body.y + axisY * t0;
        double wz0 = body.z + axisZ * t0;
        double wx1 = body.x + axisX * t1;
        double wy1 = body.y + axisY * t1;
        double wz1 = body.z + axisZ * t1;
        drawVisibleTrailSubSegment(wx0, wy0, wz0,
                wx1, wy1, wz1,
                occlusionMask);
    }

    private boolean isVisiblePoint(double wx, double wy, double wz, OrbitOcclusionMask occlusionMask) {
        if (occlusionMask == null || !occlusionMask.isEnabled()) {
            return true;
        }
        return occlusionMask.isPointVisible(wx, wy, wz);
    }

    private static boolean isFiniteScreenPoint(Vector2 point) {
        return Float.isFinite(point.x) && Float.isFinite(point.y)
                && Math.abs(point.x) < 1e6f && Math.abs(point.y) < 1e6f;
    }

    private boolean isPointClearlyVisible(double wx,
            double wy,
            double wz,
            OrbitOcclusionMask occlusionMask,
            double cameraBiasMeters) {
        if (isVisiblePoint(wx, wy, wz, occlusionMask)) {
            return true;
        }
        if (cameraBiasMeters <= 0.0) {
            return false;
        }

        double toCameraX = camera.getCamPosX() - wx;
        double toCameraY = camera.getCamPosY() - wy;
        double toCameraZ = camera.getCamPosZ() - wz;
        double toCameraLen = Math.sqrt(toCameraX * toCameraX + toCameraY * toCameraY + toCameraZ * toCameraZ);
        if (toCameraLen <= cameraBiasMeters) {
            return false;
        }

        double invToCameraLen = 1.0 / toCameraLen;
        return isVisiblePoint(
                wx + toCameraX * invToCameraLen * cameraBiasMeters,
                wy + toCameraY * invToCameraLen * cameraBiasMeters,
                wz + toCameraZ * invToCameraLen * cameraBiasMeters,
                occlusionMask);
    }

    private static int adaptiveSegments(float screenRadius) {
        if (screenRadius < 3f) {
            return 8;
        }
        if (screenRadius < 10f) {
            return 16;
        }
        return 32;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private double markerVisibilityBiasMeters(double wx, double wy, double wz) {
        double bias = camera.screenToWorldSphereRadius(PRIME_MERIDIAN_VISIBILITY_BIAS_PX, wx, wy, wz);
        if (!Double.isFinite(bias) || bias <= 0.0) {
            return 1.0;
        }
        return Math.max(1.0, bias);
    }

    // -------------------------------------------------------------------------
    // Trail rendering
    // -------------------------------------------------------------------------

    private void renderTrails(List<SimObject> objects, OrbitOcclusionMask occlusionMask, float[] screenRadii) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
            SimObject obj = objects.get(objectIndex);
            if (!obj.active)
                continue;
            OrbitTrail trail = trails.get(obj.id);
            if (trail == null || trail.pointCount() < 2)
                continue;

            double selfClipRadius = trailSelfClipRadius(obj,
                    objectIndex < screenRadii.length ? screenRadii[objectIndex] : 0f);

            if (trailCoords.length < trail.pointCount() * 2) {
                trailCoords = new float[trail.pointCount() * 2];
            }
            int n = trail.toScreenCoords(camera, trailCoords);

            Color bodyColor = unpackColor(getColor(obj));
            for (int i = 0; i < n - 1; i++) {
                // Fade alpha along the trail: newest = TRAIL_ALPHA_MAX, oldest =
                // TRAIL_ALPHA_MIN.
                float t = (float) i / (n - 1);
                float alpha = TRAIL_ALPHA_MIN + t * (TRAIL_ALPHA_MAX - TRAIL_ALPHA_MIN);
                shapeRenderer.setColor(bodyColor.r, bodyColor.g, bodyColor.b, alpha);
                drawVisibleTrailSegment(obj, selfClipRadius, trail, i, occlusionMask);
            }
        }
        shapeRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Body rendering
    // -------------------------------------------------------------------------

    private void renderBodies(List<SimObject> objects, float[] sx, float[] sy, float[] sr, double simDt) {
        // Delegate all celestial body rendering (textured + small dots).
        if (celestialBodyRenderer != null) {
            celestialBodyRenderer.render(objects, sx, sy, sr, simDt);
        }
    }

    private void drawVisibleTrailSegment(SimObject owner, double selfClipRadius,
            OrbitTrail trail, int index, OrbitOcclusionMask occlusionMask) {
        double wx0 = trail.worldXAt(index);
        double wy0 = trail.worldYAt(index);
        double wz0 = trail.worldZAt(index);
        double wx1 = trail.worldXAt(index + 1);
        double wy1 = trail.worldYAt(index + 1);
        double wz1 = trail.worldZAt(index + 1);

        if (selfClipRadius > 0.0) {
            drawVisibleTrailSegmentClipped(wx0, wy0, wz0,
                    wx1, wy1, wz1,
                    owner.x, owner.y, owner.z,
                    selfClipRadius,
                    occlusionMask);
            return;
        }

        drawVisibleTrailSubSegment(wx0, wy0, wz0,
                wx1, wy1, wz1,
                occlusionMask);
    }

    private void drawVisibleTrailSegmentClipped(double wx0, double wy0, double wz0,
            double wx1, double wy1, double wz1,
            double cx, double cy, double cz,
            double radius,
            OrbitOcclusionMask occlusionMask) {
        drawVisibleTrailSegmentClipped(wx0, wy0, wz0,
                wx1, wy1, wz1,
                cx, cy, cz,
                radius,
                occlusionMask,
                OcclusionVisibilityMode.STANDARD);
    }

    private void drawVisibleTrailSegmentClipped(double wx0, double wy0, double wz0,
            double wx1, double wy1, double wz1,
            double cx, double cy, double cz,
            double radius,
            OrbitOcclusionMask occlusionMask,
            OcclusionVisibilityMode visibilityMode) {
        double dx = wx1 - wx0;
        double dy = wy1 - wy0;
        double dz = wz1 - wz0;
        double a = dx * dx + dy * dy + dz * dz;
        if (a <= 1e-12) {
            return;
        }

        double ox = wx0 - cx;
        double oy = wy0 - cy;
        double oz = wz0 - cz;
        double radiusSq = radius * radius;
        boolean inside0 = ox * ox + oy * oy + oz * oz <= radiusSq;
        double ex = wx1 - cx;
        double ey = wy1 - cy;
        double ez = wz1 - cz;
        boolean inside1 = ex * ex + ey * ey + ez * ez <= radiusSq;

        if (inside0 && inside1) {
            return;
        }

        double b = 2.0 * (ox * dx + oy * dy + oz * dz);
        double c = ox * ox + oy * oy + oz * oz - radiusSq;
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant <= 0.0) {
            drawVisibleTrailSubSegment(wx0, wy0, wz0,
                    wx1, wy1, wz1,
                    occlusionMask,
                    visibilityMode);
            return;
        }

        double sqrtDiscriminant = Math.sqrt(discriminant);
        double t0 = (-b - sqrtDiscriminant) / (2.0 * a);
        double t1 = (-b + sqrtDiscriminant) / (2.0 * a);
        double enterT = Math.min(t0, t1);
        double exitT = Math.max(t0, t1);

        if (!inside0 && !inside1) {
            if (enterT <= 0.0 || enterT >= 1.0 || exitT <= 0.0 || exitT >= 1.0) {
                drawVisibleTrailSubSegment(wx0, wy0, wz0,
                        wx1, wy1, wz1,
                        occlusionMask,
                        visibilityMode);
                return;
            }

            double enterX = lerp(wx0, wx1, enterT);
            double enterY = lerp(wy0, wy1, enterT);
            double enterZ = lerp(wz0, wz1, enterT);
            double exitX = lerp(wx0, wx1, exitT);
            double exitY = lerp(wy0, wy1, exitT);
            double exitZ = lerp(wz0, wz1, exitT);

            drawVisibleTrailSubSegment(wx0, wy0, wz0,
                    enterX, enterY, enterZ,
                    occlusionMask,
                    visibilityMode);
            drawVisibleTrailSubSegment(exitX, exitY, exitZ,
                    wx1, wy1, wz1,
                    occlusionMask,
                    visibilityMode);
            return;
        }

        if (!inside0) {
            double clipT = Math.max(0.0, Math.min(1.0, enterT));
            double clipX = lerp(wx0, wx1, clipT);
            double clipY = lerp(wy0, wy1, clipT);
            double clipZ = lerp(wz0, wz1, clipT);
            drawVisibleTrailSubSegment(wx0, wy0, wz0,
                    clipX, clipY, clipZ,
                    occlusionMask,
                    visibilityMode);
            return;
        }

        double clipT = Math.max(0.0, Math.min(1.0, exitT));
        double clipX = lerp(wx0, wx1, clipT);
        double clipY = lerp(wy0, wy1, clipT);
        double clipZ = lerp(wz0, wz1, clipT);
        drawVisibleTrailSubSegment(clipX, clipY, clipZ,
                wx1, wy1, wz1,
                occlusionMask,
                visibilityMode);
    }

    private void drawVisibleTrailSubSegment(double wx0, double wy0, double wz0,
            double wx1, double wy1, double wz1,
            OrbitOcclusionMask occlusionMask) {
        drawVisibleTrailSubSegment(wx0, wy0, wz0,
                wx1, wy1, wz1,
                occlusionMask,
                OcclusionVisibilityMode.STANDARD);
    }

    private void drawVisibleTrailSubSegment(double wx0, double wy0, double wz0,
            double wx1, double wy1, double wz1,
            OrbitOcclusionMask occlusionMask,
            OcclusionVisibilityMode visibilityMode) {
        Vector2 s0 = camera.worldToScreen(wx0, wy0, wz0);
        Vector2 s1 = camera.worldToScreen(wx1, wy1, wz1);
        if (occlusionMask == null || !occlusionMask.isEnabled()
                || !occlusionMask.segmentMayBeOccluded(s0.x, s0.y, s1.x, s1.y)) {
            shapeRenderer.line(s0.x, s0.y, s1.x, s1.y);
            return;
        }

        drawVisibleTrailSegmentRecursive(wx0, wy0, wz0, s0.x, s0.y,
                wx1, wy1, wz1, s1.x, s1.y,
                occlusionMask, OCCLUSION_SUBDIVISION_DEPTH,
                visibilityMode);
    }

    private double trailSelfClipRadius(SimObject obj, float screenRadius) {
        if (!(obj instanceof CelestialBody) || screenRadius <= 0f) {
            return 0.0;
        }
        return camera.screenToWorldSphereRadius(screenRadius, obj.x, obj.y, obj.z);
    }

    private void drawVisibleTrailSegmentRecursive(double wx0, double wy0, double wz0, float sx0, float sy0,
            double wx1, double wy1, double wz1, float sx1, float sy1,
            OrbitOcclusionMask occlusionMask, int depth,
            OcclusionVisibilityMode visibilityMode) {
        if (!occlusionMask.segmentMayBeOccluded(sx0, sy0, sx1, sy1)) {
            shapeRenderer.line(sx0, sy0, sx1, sy1);
            return;
        }

        boolean visible0 = isPointVisibleForMode(wx0, wy0, wz0, occlusionMask, visibilityMode);
        boolean visible1 = isPointVisibleForMode(wx1, wy1, wz1, occlusionMask, visibilityMode);
        double wxMid = lerp(wx0, wx1, 0.5);
        double wyMid = lerp(wy0, wy1, 0.5);
        double wzMid = lerp(wz0, wz1, 0.5);
        boolean visibleMid = isPointVisibleForMode(wxMid, wyMid, wzMid, occlusionMask, visibilityMode);

        if (depth <= 0 || (visible0 == visibleMid && visibleMid == visible1)) {
            if (visibleMid) {
                shapeRenderer.line(sx0, sy0, sx1, sy1);
            }
            return;
        }

        Vector2 midScreen = camera.worldToScreen(wxMid, wyMid, wzMid);
        drawVisibleTrailSegmentRecursive(wx0, wy0, wz0, sx0, sy0,
                wxMid, wyMid, wzMid, midScreen.x, midScreen.y,
                occlusionMask, depth - 1,
                visibilityMode);
        drawVisibleTrailSegmentRecursive(wxMid, wyMid, wzMid, midScreen.x, midScreen.y,
                wx1, wy1, wz1, sx1, sy1,
                occlusionMask, depth - 1,
                visibilityMode);
    }

    private boolean isPointVisibleForMode(double wx,
            double wy,
            double wz,
            OrbitOcclusionMask occlusionMask,
            OcclusionVisibilityMode visibilityMode) {
        if (visibilityMode == OcclusionVisibilityMode.CLEAR) {
            return isPointClearlyVisible(
                    wx,
                    wy,
                    wz,
                    occlusionMask,
                    markerVisibilityBiasMeters(wx, wy, wz));
        }
        return isVisiblePoint(wx, wy, wz, occlusionMask);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Fills the parallel screen-coordinate arrays (position + radius) for every
     * object, applying visual-scale inflation and overlap inhibition.
     */
    private void computeScreenLayout(List<SimObject> objects, float[] sx, float[] sy, float[] sr) {
        int n = objects.size();
        boolean visualScaleMode = getVisualScaleMode();

        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            Vector2 sc = camera.worldToScreen(obj.x, obj.y, obj.z);
            sx[i] = sc.x;
            sy[i] = sc.y;
            sr[i] = desiredVisualRadius(obj, visualScaleMode);
        }

        if (!visualScaleMode)
            return;

        if (vsInhibited) {
            for (int i = 0; i < n; i++) {
                sr[i] = trueScreenRadius(objects.get(i));
            }
            return;
        }

        boolean anyOverlap = false;
        boolean freeCam = camera.getMode() == CameraMode.FREE_CAM;
        if (freeCam) {
            // 3D world-space overlap: check if inflated spheres intersect.
            outer3d: for (int i = 0; i < n; i++) {
                SimObject oi = objects.get(i);
                if (!oi.active || !isPrimaryBody(oi))
                    continue;
                double ri = camera.screenToWorldSphereRadius(sr[i], oi.x, oi.y, oi.z);
                for (int j = i + 1; j < n; j++) {
                    SimObject oj = objects.get(j);
                    if (!oj.active || !isPrimaryBody(oj))
                        continue;
                    double rj = camera.screenToWorldSphereRadius(sr[j], oj.x, oj.y, oj.z);
                    double dx = oi.x - oj.x;
                    double dy = oi.y - oj.y;
                    double dz = oi.z - oj.z;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (ri + rj > dist) {
                        anyOverlap = true;
                        break outer3d;
                    }
                }
            }
        } else {
            // 2D screen-space overlap (original heuristic for TOP_VIEW).
            outer2d: for (int i = 0; i < n; i++) {
                SimObject oi = objects.get(i);
                if (!oi.active || !isPrimaryBody(oi))
                    continue;
                for (int j = i + 1; j < n; j++) {
                    SimObject oj = objects.get(j);
                    if (!oj.active || !isPrimaryBody(oj))
                        continue;
                    float dx = sx[i] - sx[j];
                    float dy = sy[i] - sy[j];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (sr[i] + sr[j] > dist + VS_OVERLAP_TOLERANCE_PX) {
                        anyOverlap = true;
                        break outer2d;
                    }
                }
            }
        }

        if (anyOverlap) {
            vsInhibited = true;
            for (int i = 0; i < n; i++) {
                sr[i] = trueScreenRadius(objects.get(i));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * True physical screen radius, floored at VISUAL_SCALE_MIN_PX.
     * Used as the fallback when visual scale would cause overlap.
     */
    private float trueScreenRadius(SimObject obj) {
        return Math.max(VISUAL_SCALE_MIN_PX,
                camera.worldSphereRadiusToScreen(obj.radius, obj.x, obj.y, obj.z));
    }

    /**
     * A "primary" body is a star, a planet, or a dwarf planet that orbits the
     * star directly (no parent, or parent is a STAR). These are used for the
     * global overlap decision; moons are excluded so Terra-Luna proximity never
     * suppresses the visual scale of the whole system.
     */
    private static boolean isPrimaryBody(SimObject obj) {
        if (!(obj instanceof CelestialBody cb))
            return false;
        return switch (cb.bodyType) {
            case STAR -> true;
            case PLANET -> true;
            case DWARF_PLANET -> cb.parent == null
                    || cb.parent.bodyType == BodyType.STAR;
            default -> false;
        };
    }

    /**
     * Desired display radius:
     * - V OFF: true physical size, floored at VISUAL_SCALE_MIN_PX.
     * - V ON: log or power-law scale (per LOGARITHMIC_SCALE) OR true screen
     * size, whichever is larger.
     * Overlap clamping in renderBodies may reduce this to trueScreenRadius.
     */
    private float desiredVisualRadius(SimObject obj, boolean visualScaleMode) {
        float trueRadius = camera.worldSphereRadiusToScreen(obj.radius, obj.x, obj.y, obj.z);
        if (!visualScaleMode) {
            return Math.max(VISUAL_SCALE_MIN_PX, trueRadius);
        }
        double radiusKm = obj.radius / 1000.0;
        float visualPx;
        if (LOGARITHMIC_SCALE) {
            visualPx = (float) (VS_LOG_A * Math.log(radiusKm) + VS_LOG_B);
        } else {
            visualPx = (float) (VS_POW_BASE * Math.pow(radiusKm, VS_POW_EXP));
        }
        return Math.max(Math.max(VISUAL_SCALE_MIN_PX, visualPx), trueRadius);
    }

    private boolean getVisualScaleMode() {
        boolean visualScaleMode = overlaySettings.isVisualScaleMode();
        if (cachedVisualScaleMode != visualScaleMode) {
            vsInhibited = false;
            cachedVisualScaleMode = visualScaleMode;
        }
        return visualScaleMode;
    }

    private int getColor(SimObject obj) {
        if (obj instanceof CelestialBody cb)
            return cb.color.base;
        return 0xFFFFFFFF; // spacecraft = white
    }

    private Color unpackColor(int rgba8888) {
        float r = ((rgba8888 >> 24) & 0xFF) / 255f;
        float g = ((rgba8888 >> 16) & 0xFF) / 255f;
        float b = ((rgba8888 >> 8) & 0xFF) / 255f;
        float a = (rgba8888 & 0xFF) / 255f;
        return new Color(r, g, b, a);
    }

    /** Returns the trail for the given object ID, or null. */
    public OrbitTrail getTrail(String objectId) {
        return trails.get(objectId);
    }

    /** Clears all recorded orbit trails. */
    public void clearTrails() {
        trails.clear();
    }

    public void dispose() {
        if (celestialBodyRenderer != null) {
            celestialBodyRenderer.dispose();
        }
        // ShapeRenderer is owned by Gravitas and disposed there.
    }
}
