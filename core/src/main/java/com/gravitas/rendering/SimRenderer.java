package com.gravitas.rendering;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.BeltData;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Trails are sampled at every physics tick via
 * {@link #recordTrailPositions(List)}.
 */
public class SimRenderer {

    private static final int CIRCLE_SEGMENTS = 32;
    private static final float TRAIL_ALPHA_MAX = 0.8f;
    private static final float TRAIL_ALPHA_MIN = 0.05f;

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
    private final Map<String, OrbitTrail> trails = new HashMap<>();

    /**
     * Texture-based celestial body renderer (handles spherical projection,
     * rotation, glow).
     */
    private CelestialBodyRenderer celestialBodyRenderer;

    /** When true, body radii are exaggerated for visibility. */
    private boolean visualScaleMode = true;

    /**
     * Latch: once an overlap is detected the visual scale stays inhibited
     * (all bodies drawn at true size) until the zoom level changes, which
     * clears the latch and allows a fresh evaluation.
     */
    private boolean vsInhibited = false;
    private float lastZoom = -1f;

    /** Scratch arrays re-used each frame to avoid allocation. */
    private float[] trailCoords = new float[OrbitTrail.DEFAULT_CAPACITY * 2];

    /** Statistical belts (asteroid belt, Kuiper belt). */
    private final List<BeltParticles> beltParticles = new ArrayList<>();

    /** Pre-generated belt particle positions in world metres. */
    private static class BeltParticles {
        final double[] angle; // current orbital angle (radians)
        final double[] radius; // orbital radius (metres)
        final double[] angVel; // angular velocity (rad/s) from Kepler: sqrt(GM/r³)
        final Color color;
        final int count;
        final String parentName; // name of parent body this belt orbits

        // GM_Sun (m³/s²)
        private static final double GM_SUN = 1.32712440018e20;

        BeltParticles(BeltData belt) {
            this.count = belt.particleCount;
            this.parentName = belt.parentName;
            this.angle = new double[count];
            this.radius = new double[count];
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
            }
        }

        /** Advance all particle angles by simDt seconds of simulation time. */
        void advance(double simDt) {
            for (int i = 0; i < count; i++) {
                angle[i] += angVel[i] * simDt;
            }
        }
    }

    /**
     * Supplies belt data for rendering. Call once after loading.
     */
    public void setBelts(List<BeltData> belts) {
        beltParticles.clear();
        for (BeltData b : belts) {
            beltParticles.add(new BeltParticles(b));
        }
    }

    public SimRenderer(ShapeRenderer shapeRenderer, WorldCamera camera) {
        this.shapeRenderer = shapeRenderer;
        this.camera = camera;
    }

    /**
     * Initialises the texture-based planet renderer for a specific stellar system.
     * Must be called after libGDX GL context is ready (i.e. inside create()).
     * 
     * @param systemFolder folder name under assets/textures/ (e.g. "solar_system")
     */
    public void initCelestialBodyRenderer(String systemFolder) {
        celestialBodyRenderer = new CelestialBodyRenderer(camera, systemFolder);
    }

    public void setVisualScaleMode(boolean enabled) {
        this.visualScaleMode = enabled;
    }

    public void setCelestialFx(boolean enabled) {
        if (celestialBodyRenderer != null) {
            celestialBodyRenderer.setCelestialFx(enabled);
        }
    }

    public boolean isVisualScaleMode() {
        return visualScaleMode;
    }

    // -------------------------------------------------------------------------
    // Called each physics step (not necessarily each render frame)
    // -------------------------------------------------------------------------

    public void recordTrailPositions(List<SimObject> objects) {
        for (SimObject obj : objects) {
            if (!obj.active)
                continue;
            OrbitTrail trail = trails.computeIfAbsent(obj.id, id -> new OrbitTrail());
            trail.record(obj.x, obj.y);
        }
    }

    // -------------------------------------------------------------------------
    // Called each render frame
    // -------------------------------------------------------------------------

    public void render(List<SimObject> objects, double simDt) {
        renderBelts(simDt, objects);
        renderTrails(objects);
        renderBodies(objects);
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
        float mpp = camera.getMetersPerPixel();

        for (BeltParticles bp : beltParticles) {
            // Find the parent body this belt orbits around.
            double parentX = 0, parentY = 0;
            for (SimObject obj : objects) {
                if (obj.name.equals(bp.parentName)) {
                    parentX = obj.x;
                    parentY = obj.y;
                    break;
                }
            }

            for (int i = 0; i < bp.count; i++) {
                double wx = parentX + bp.radius[i] * Math.cos(bp.angle[i]);
                double wy = parentY + bp.radius[i] * Math.sin(bp.angle[i]);
                float sx = (float) ((wx - camera.getFocusX()) / mpp)
                        + camera.getCamera().viewportWidth * 0.5f;
                float sy = (float) ((wy - camera.getFocusY()) / mpp)
                        + camera.getCamera().viewportHeight * 0.5f;

                // Frustum cull: skip particles outside the screen.
                if (sx < -2 || sx > camera.getCamera().viewportWidth + 2
                        || sy < -2 || sy > camera.getCamera().viewportHeight + 2) {
                    continue;
                }

                shapeRenderer.setColor(bp.color.r, bp.color.g, bp.color.b, 0.35f);
                shapeRenderer.circle(sx, sy, 1.2f, 4);
            }
        }
        shapeRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Trail rendering
    // -------------------------------------------------------------------------

    private void renderTrails(List<SimObject> objects) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (SimObject obj : objects) {
            if (!obj.active)
                continue;
            OrbitTrail trail = trails.get(obj.id);
            if (trail == null || trail.pointCount() < 2)
                continue;

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
                shapeRenderer.line(
                        trailCoords[i * 2], trailCoords[i * 2 + 1],
                        trailCoords[i * 2 + 2], trailCoords[i * 2 + 3]);
            }
        }
        shapeRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Body rendering
    // -------------------------------------------------------------------------

    private void renderBodies(List<SimObject> objects) {
        int n = objects.size();

        // Pre-compute screen positions and desired visual radii.
        float[] sx = new float[n];
        float[] sy = new float[n];
        float[] sr = new float[n];
        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            Vector2 sc = camera.worldToScreen(obj.x, obj.y);
            sx[i] = sc.x;
            sy[i] = sc.y;
            sr[i] = desiredVisualRadius(obj);
        }

        // Smart overlap prevention: when visual scale is ON, check whether any
        // two PRIMARY bodies (stars, planets, parentless dwarf planets) would
        // overlap with their desired visual radii. If any pair overlaps, revert
        // ALL bodies to true (unenhanced) radii so the display is consistent.
        //
        // Latch: once inhibited, stay inhibited until the zoom level changes
        // (scroll / animated zoom). This prevents flickering on eccentric orbits
        // where overlap comes and goes at a fixed zoom.
        if (visualScaleMode) {
            float currentZoom = camera.getMetersPerPixel();
            if (currentZoom != lastZoom) {
                vsInhibited = false;
                lastZoom = currentZoom;
            }

            if (vsInhibited) {
                for (int i = 0; i < n; i++) {
                    sr[i] = trueScreenRadius(objects.get(i));
                }
            } else {
                boolean anyOverlap = false;
                outer: for (int i = 0; i < n; i++) {
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
                            break outer;
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
        }

        // Index map for moon-skip parent lookup.
        Map<SimObject, Integer> objIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            objIndex.put(objects.get(i), i);
        }

        // --- Texture pass: render textured planets (atmosphere glow + surfaces) ---
        boolean[] textured = null;
        if (celestialBodyRenderer != null) {
            textured = celestialBodyRenderer.render(objects, sx, sy, sr);
        }

        // --- Shape pass: flat-colour circles for bodies not handled by
        // CelestialBodyRenderer ---
        // ---
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < n; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active)
                continue;

            // Skip bodies already rendered with textures.
            if (textured != null && textured[i])
                continue;

            // Skip a moon that visually overlaps its parent and is comparable in
            // size to it. The sr[moon] >= sr[parent]*0.5 guard means this only
            // triggers at zoom-out (both at visual minima); at zoom-in the moon's
            // true physical radius is tiny and sr[moon] << sr[parent], so the
            // condition is false and the moon draws normally.
            if (obj instanceof CelestialBody cb && cb.bodyType == CelestialBody.BodyType.MOON
                    && cb.parent != null) {
                Integer pi = objIndex.get(cb.parent);
                if (pi != null) {
                    float mdx = sx[i] - sx[pi];
                    float mdy = sy[i] - sy[pi];
                    float mdist = (float) Math.sqrt(mdx * mdx + mdy * mdy);
                    // Skip only when the moon is nearly the same pixel size
                    // as the parent (within 1 px) AND they overlap. At zoom-out
                    // both collapse to VISUAL_SCALE_MIN_PX (2 px) → same size →
                    // skip. At medium zoom moon is visually smaller than planet
                    // (3 vs 6 px) → show. At zoom-in they are far apart → show.
                    if (mdist < sr[i] + sr[pi] && sr[i] >= sr[pi] - 1f) {
                        continue;
                    }
                }
            }

            Color color = unpackColor(getColor(obj));

            // Draw glow ring for stars.
            if (obj instanceof CelestialBody cb && cb.bodyType == CelestialBody.BodyType.STAR) {
                shapeRenderer.setColor(color.r, color.g, color.b, 0.15f);
                shapeRenderer.circle(sx[i], sy[i], sr[i] * 1.5f, CIRCLE_SEGMENTS);
            }

            shapeRenderer.setColor(color);
            shapeRenderer.circle(sx[i], sy[i], sr[i], adaptiveSegments(sr[i]));
        }
        shapeRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * True physical screen radius, floored at VISUAL_SCALE_MIN_PX.
     * Used as the fallback when visual scale would cause overlap.
     */
    private float trueScreenRadius(SimObject obj) {
        return Math.max(VISUAL_SCALE_MIN_PX, camera.worldRadiusToScreen(obj.radius));
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
                    || cb.parent.bodyType == CelestialBody.BodyType.STAR;
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
    private float desiredVisualRadius(SimObject obj) {
        float trueRadius = camera.worldRadiusToScreen(obj.radius);
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

    private int getColor(SimObject obj) {
        if (obj instanceof CelestialBody cb)
            return cb.displayColor;
        return 0xFFFFFFFF; // spacecraft = white
    }

    private Color unpackColor(int rgba8888) {
        float r = ((rgba8888 >> 24) & 0xFF) / 255f;
        float g = ((rgba8888 >> 16) & 0xFF) / 255f;
        float b = ((rgba8888 >> 8) & 0xFF) / 255f;
        float a = (rgba8888 & 0xFF) / 255f;
        return new Color(r, g, b, a);
    }

    /** Reduce circle segment count for tiny objects to save draw calls. */
    private int adaptiveSegments(float screenRadius) {
        if (screenRadius < 3)
            return 8;
        if (screenRadius < 10)
            return 16;
        return CIRCLE_SEGMENTS;
    }

    /** Returns the trail for the given object ID, or null. */
    public OrbitTrail getTrail(String objectId) {
        return trails.get(objectId);
    }

    public void dispose() {
        if (celestialBodyRenderer != null) {
            celestialBodyRenderer.dispose();
        }
        // ShapeRenderer is owned by GravitasGame and disposed there.
    }
}
