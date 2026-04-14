package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.CelestialBody;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.OrbitPredictor;
import com.gravitas.rendering.WorldCamera;

/**
 * Handles all keyboard / mouse input for the simulation.
 *
 * Keyboard:
 * SPACE — pause / resume
 * , (comma) — previous warp preset
 * . (period) — next warp preset
 * 1-0 — warp presets: 1x 10x 100x 1k 10k 100k 1M 10M 100M 1B
 * V — toggle visual scale mode (exaggerated planet sizes)
 * T — toggle orbital ellipse predictors
 * F — clear follow target
 *
 * Mouse:
 * Left drag — pan
 * Scroll — zoom toward cursor
 */
public class GravitasInputProcessor extends InputAdapter {

    private static final double[] WARP_PRESETS = {
            1, 10, 100, 1_000, 10_000, 100_000,
            1_000_000, 10_000_000, 100_000_000, 1_000_000_000
    };

    private final PhysicsEngine physics;
    private final WorldCamera camera;
    private final OrbitPredictor orbitPredictor;
    private MeasureTool measureTool;

    private boolean paused = false;
    private double prePauseWarp = 1.0;

    // Toggleable modes (read by SimRenderer / OrbitPredictor via accessors)
    private boolean visualScaleMode = true;
    private boolean showOrbitPredictors = false;

    // Left-mouse pan state.
    private boolean leftDragging = false;

    // Double-click / tap tracking for follow-body.
    private int touchDownScreenX, touchDownScreenY;
    private long lastTapTimeMs = -1; // time of most recent tap
    private int lastTapScreenX, lastTapScreenY;
    // Pending single-tap: body to follow after the double-click window expires.
    private CelestialBody pendingSingleTapBody = null;
    private long pendingSingleTapMs = -1;

    public GravitasInputProcessor(PhysicsEngine physics, WorldCamera camera,
            OrbitPredictor orbitPredictor) {
        this.physics = physics;
        this.camera = camera;
        this.orbitPredictor = orbitPredictor;
    }

    // -------------------------------------------------------------------------
    // Keyboard
    // -------------------------------------------------------------------------

    @Override
    public boolean keyDown(int keycode) {
        switch (keycode) {
            case Input.Keys.SPACE -> togglePause();
            case Input.Keys.COMMA -> prevPreset();
            case Input.Keys.PERIOD -> nextPreset();
            case Input.Keys.NUM_1 -> setWarp(WARP_PRESETS[0]);
            case Input.Keys.NUM_2 -> setWarp(WARP_PRESETS[1]);
            case Input.Keys.NUM_3 -> setWarp(WARP_PRESETS[2]);
            case Input.Keys.NUM_4 -> setWarp(WARP_PRESETS[3]);
            case Input.Keys.NUM_5 -> setWarp(WARP_PRESETS[4]);
            case Input.Keys.NUM_6 -> setWarp(WARP_PRESETS[5]);
            case Input.Keys.NUM_7 -> setWarp(WARP_PRESETS[6]);
            case Input.Keys.NUM_8 -> setWarp(WARP_PRESETS[7]);
            case Input.Keys.NUM_9 -> setWarp(WARP_PRESETS[8]);
            case Input.Keys.NUM_0 -> setWarp(WARP_PRESETS[9]);
            case Input.Keys.V -> visualScaleMode = !visualScaleMode;
            case Input.Keys.T -> showOrbitPredictors = !showOrbitPredictors;
            case Input.Keys.F -> camera.clearFollow();
            case Input.Keys.Q -> Gdx.app.exit();
            case Input.Keys.M -> {
                if (measureTool != null)
                    measureTool.toggle();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void togglePause() {
        if (paused) {
            physics.setTimeWarpFactor(prePauseWarp);
            paused = false;
        } else {
            prePauseWarp = physics.getTimeWarpFactor();
            physics.setTimeWarpFactor(0);
            paused = true;
        }
    }

    private void prevPreset() {
        if (paused)
            return;
        int idx = currentPresetIndex();
        if (idx > 0)
            setWarp(WARP_PRESETS[idx - 1]);
    }

    private void nextPreset() {
        if (paused)
            return;
        int idx = currentPresetIndex();
        if (idx < WARP_PRESETS.length - 1)
            setWarp(WARP_PRESETS[idx + 1]);
    }

    /**
     * Returns the index in WARP_PRESETS closest to the current warp (log scale).
     */
    private int currentPresetIndex() {
        double w = physics.getTimeWarpFactor();
        int idx = (int) Math.round(Math.log10(Math.max(1.0, w)));
        return Math.min(WARP_PRESETS.length - 1, Math.max(0, idx));
    }

    /**
     * Find a body whose screen-space disc contains the click (≤30 px).
     * If a moon and its parent planet both contain the click, the planet wins.
     */
    private CelestialBody findBodyAt(int screenX, int screenY) {
        CelestialBody best = null;
        float bestDistSq = 30 * 30;

        for (var obj : physics.getObjects()) {
            if (!(obj instanceof CelestialBody cb) || !cb.active)
                continue;
            Vector2 sc = camera.worldToScreen(cb.x, cb.y);
            float bsy = Gdx.graphics.getHeight() - sc.y;
            float ddx = screenX - sc.x;
            float ddy = screenY - bsy;
            float dSq = ddx * ddx + ddy * ddy;
            if (dSq > 30 * 30)
                continue;
            // Prefer this body over the current best if:
            // - it's the first candidate, OR
            // - it is a parent of the current best (planet beats its moon), OR
            // - it is simply closer and the current best is not its parent.
            if (best == null) {
                best = cb;
                bestDistSq = dSq;
            } else if (isAncestor(cb, best)) {
                // cb is a parent of best → cb wins unconditionally
                best = cb;
                bestDistSq = dSq;
            } else if (!isAncestor(best, cb) && dSq < bestDistSq) {
                // neither is an ancestor of the other → closer wins
                best = cb;
                bestDistSq = dSq;
            }
        }
        return best;
    }

    /**
     * Returns true if {@code ancestor} is a direct or indirect parent of
     * {@code body}.
     */
    private static boolean isAncestor(CelestialBody ancestor, CelestialBody body) {
        CelestialBody cur = body.parent;
        while (cur != null) {
            if (cur == ancestor)
                return true;
            cur = cur.parent;
        }
        return false;
    }

    /**
     * Lazy orbit hit-test: compute Keplerian points for every body on-the-spot
     * and return the body whose orbit passes within ORBIT_HIT_PX of the click.
     */
    private static final float ORBIT_HIT_PX = 8f;

    private CelestialBody findBodyOnOrbit(int screenX, int screenY) {
        // Work in bottom-left screen coords (same as worldToScreen output).
        float sySrc = Gdx.graphics.getHeight() - screenY;
        float bestDistSq = ORBIT_HIT_PX * ORBIT_HIT_PX;
        CelestialBody bestBody = null;

        for (var obj : physics.getObjects()) {
            if (!(obj instanceof CelestialBody cb))
                continue;
            if (!cb.active || cb.parent == null)
                continue;

            double[] pts = orbitPredictor.computeOrbitWorldPoints(cb, cb.parent);
            if (pts == null)
                continue;

            for (int i = 0; i < pts.length / 2 - 1; i++) {
                Vector2 p0 = camera.worldToScreen(pts[i * 2], pts[i * 2 + 1]);
                Vector2 p1 = camera.worldToScreen(pts[(i + 1) * 2], pts[(i + 1) * 2 + 1]);
                float distSq = pointToSegmentDistSq(screenX, sySrc,
                        p0.x, p0.y, p1.x, p1.y);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestBody = cb;
                }
            }
        }
        return bestBody;
    }

    /** Squared distance from point (px,py) to segment (ax,ay)-(bx,by). */
    private static float pointToSegmentDistSq(float px, float py,
            float ax, float ay, float bx, float by) {
        float abx = bx - ax, aby = by - ay;
        float lenSq = abx * abx + aby * aby;
        if (lenSq == 0) {
            float dx = px - ax, dy = py - ay;
            return dx * dx + dy * dy;
        }
        float t = Math.max(0, Math.min(1, ((px - ax) * abx + (py - ay) * aby) / lenSq));
        float cx = ax + t * abx - px;
        float cy = ay + t * aby - py;
        return cx * cx + cy * cy;
    }

    private void setWarp(double warp) {
        physics.setTimeWarpFactor(warp);
        paused = false;
    }

    // -------------------------------------------------------------------------
    // Mouse scroll — zoom toward cursor
    // -------------------------------------------------------------------------

    @Override
    public boolean scrolled(float amountX, float amountY) {
        camera.onScroll(Gdx.input.getX(), Gdx.input.getY(), amountY);
        return true;
    }

    // -------------------------------------------------------------------------
    // Left-mouse pan
    // -------------------------------------------------------------------------

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.LEFT) {
            touchDownScreenX = screenX;
            touchDownScreenY = screenY;
            camera.onPanBegin(screenX, screenY);
            leftDragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (leftDragging) {
            camera.onPanDrag(screenX, screenY);
            return true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.LEFT) {
            camera.onPanEnd();
            leftDragging = false;

            // Measure tool intercepts clicks when active.
            if (measureTool != null && measureTool.isActive()) {
                int dx = screenX - touchDownScreenX;
                int dy = screenY - touchDownScreenY;
                if (dx * dx + dy * dy <= 12 * 12) {
                    measureTool.onClick(screenX, screenY);
                }
                return true;
            }

            // Tap detection (no significant drag).
            int dx = screenX - touchDownScreenX;
            int dy = screenY - touchDownScreenY;
            if (dx * dx + dy * dy <= 12 * 12) {
                long now = System.currentTimeMillis();
                int ddx = screenX - lastTapScreenX;
                int ddy = screenY - lastTapScreenY;
                if (lastTapTimeMs >= 0 && (now - lastTapTimeMs) < 300
                        && ddx * ddx + ddy * ddy <= 30 * 30) {
                    // Double-tap: cancel pending single-tap, follow + smooth zoom.
                    pendingSingleTapBody = null;
                    pendingSingleTapMs = -1;
                    CelestialBody target = findBodyAt(screenX, screenY);
                    if (target == null)
                        target = findBodyOnOrbit(screenX, screenY);
                    if (target != null) {
                        camera.setFollowTarget(target);
                        camera.startSmoothZoomTo(target.radius);
                    }
                    lastTapTimeMs = -1;
                } else {
                    // First tap: find target now but defer the follow action by 300ms
                    // so that a second tap can upgrade it to a double-tap.
                    CelestialBody target = findBodyAt(screenX, screenY);
                    if (target == null)
                        target = findBodyOnOrbit(screenX, screenY);
                    pendingSingleTapBody = target;
                    pendingSingleTapMs = now;
                    lastTapTimeMs = now;
                    lastTapScreenX = screenX;
                    lastTapScreenY = screenY;
                }
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Per-frame update (call from game loop)
    // -------------------------------------------------------------------------

    /**
     * Must be called once per frame from the game loop.
     * Fires deferred single-tap actions once the double-click window has expired.
     */
    public void update() {
        if (pendingSingleTapMs >= 0) {
            if (System.currentTimeMillis() - pendingSingleTapMs >= 300) {
                if (pendingSingleTapBody != null) {
                    camera.setFollowTarget(pendingSingleTapBody);
                } else {
                    camera.setFollowTarget(null); // tap on empty space → unfollow
                }
                pendingSingleTapBody = null;
                pendingSingleTapMs = -1;
                lastTapTimeMs = -1;
            }
        }
    }

    // -------------------------------------------------------------------------
    // State accessors
    // -------------------------------------------------------------------------

    public boolean isPaused() {
        return paused;
    }

    public boolean isVisualScaleMode() {
        return visualScaleMode;
    }

    public boolean isShowOrbitPredictors() {
        return showOrbitPredictors;
    }

    public void setMeasureTool(MeasureTool tool) {
        this.measureTool = tool;
    }

    public MeasureTool getMeasureTool() {
        return measureTool;
    }
}
