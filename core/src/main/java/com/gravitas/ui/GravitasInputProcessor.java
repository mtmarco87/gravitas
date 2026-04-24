package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.CameraMode;
import com.gravitas.rendering.core.SimRenderer;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.rendering.orbit.OrbitPredictor;
import com.gravitas.rendering.orbit.OrbitTrail;
import com.gravitas.settings.AppSettings;
import com.gravitas.settings.CameraSettings;
import com.gravitas.settings.OverlaySettings;
import com.gravitas.settings.SimulationSettings;
import com.gravitas.state.AppState;
import com.gravitas.state.SimulationState;
import com.gravitas.state.UiState;
import com.gravitas.ui.settings.SettingsPanelController;
import com.gravitas.ui.settings.SettingsPanelModel;
import com.gravitas.ui.settings.WarpPresets;

import java.util.Objects;

/**
 * Handles all keyboard / mouse input for the simulation.
 *
 * Keyboard:
 * SPACE — pause / resume
 * , (comma) — previous warp preset
 * . (period) — next warp preset
 * 1-0 — warp presets: 1x 10x 1k 10k 100k 500k 1M 10M 100M 1B
 * V — toggle visual scale mode (exaggerated planet sizes)
 * T — cycle overlays (trails / trails+orbits / none)
 * Y — cycle orbit renderer (solid / CPU dashed / GPU dashed)
 * P — cycle follow mode (free / orbit upright / orbit plane / orbit axial /
 * rotation axial)
 * C — toggle camera mode (TOP_VIEW / FREE_CAM)
 * Z — toggle FREE_CAM FOV mode (adaptive / fixed)
 * L — toggle orbit dimensions (FLAT 2D / 3D)
 * F — clear follow target
 * R — reset camera to nearest system star
 * X — open settings menu
 *
 * Mouse:
 * Left drag — pan
 * Right drag (FREE_CAM) — orbit camera around focus
 * Scroll — zoom toward cursor / dolly
 */
public class GravitasInputProcessor extends InputAdapter {

    public record Actions(Runnable toggleOrbitsDimensions, Runnable cameraReset, Runnable simulationReset) {
        public Actions {
            Objects.requireNonNull(toggleOrbitsDimensions, "toggleOrbitsDimensions");
            Objects.requireNonNull(cameraReset, "cameraReset");
            Objects.requireNonNull(simulationReset, "simulationReset");
        }
    }

    private static final float CAMERA_ARROW_PAN_PX_PER_SEC = 400f;
    private static final float CAMERA_KEY_ROT_SPEED = 1.5f;
    private static final int TAP_DRAG_THRESHOLD_PX = 12;

    private final WorldCamera camera;
    private final PhysicsEngine physics;
    private final SimRenderer simRenderer;
    private final OrbitPredictor orbitPredictor;
    private final MeasureTool measureTool;
    private final CameraSettings cameraSettings;
    private final SimulationState simulationState;
    private final UiState uiState;
    private final OverlaySettings overlaySettings;
    private final SimulationSettings simulationSettings;
    private final Actions actions;
    private final SettingsPanelController settingsPanelController;

    private double prePauseWarp = 1.0;
    // Left-mouse pointer / pan state.
    private boolean leftDragging = false;
    private boolean leftPanning = false;

    // Right-mouse orbit-drag state (FREE_CAM).
    private boolean rightDragging = false;
    private float lastOrbitX, lastOrbitY;

    // Double-click / tap tracking for follow-body.
    private int touchDownScreenX, touchDownScreenY;
    private long lastTapTimeMs = -1; // time of most recent tap
    private int lastTapScreenX, lastTapScreenY;
    // Pending single-tap: body to follow after the double-click window expires.
    private CelestialBody pendingSingleTapBody = null;
    private long pendingSingleTapMs = -1;

    public GravitasInputProcessor(WorldCamera camera, PhysicsEngine physics,
            SimRenderer simRenderer, OrbitPredictor orbitPredictor, MeasureTool measureTool,
            AppSettings settings, AppState appState, SettingsPanelModel settingsPanelModel, Actions actions) {
        this.camera = camera;
        this.physics = physics;
        this.simRenderer = Objects.requireNonNull(simRenderer, "simRenderer");
        this.orbitPredictor = Objects.requireNonNull(orbitPredictor, "orbitPredictor");
        this.measureTool = Objects.requireNonNull(measureTool, "measureTool");
        AppSettings appSettings = Objects.requireNonNull(settings, "settings");
        AppState runtimeState = Objects.requireNonNull(appState, "appState");
        this.simulationState = runtimeState.getSimulation();
        this.uiState = runtimeState.getUi();
        this.cameraSettings = appSettings.getCameraSettings();
        this.overlaySettings = appSettings.getOverlaySettings();
        this.simulationSettings = appSettings.getSimulationSettings();
        this.actions = Objects.requireNonNull(actions, "actions");
        simulationSettings.setTimeWarp(physics.getTimeWarpFactor());
        prePauseWarp = simulationSettings.getTimeWarp();
        physics.applyPhysicsSettings(appSettings.getPhysicsSettings());
        this.settingsPanelController = new SettingsPanelController(
                camera,
                physics,
                measureTool,
                appSettings,
                runtimeState,
                Objects.requireNonNull(settingsPanelModel, "settingsPanelModel"),
                actions,
                this::clearPendingTapState,
                this::resetPointerDragging);
    }

    // -------------------------------------------------------------------------
    // Keyboard
    // -------------------------------------------------------------------------

    @Override
    public boolean keyDown(int keycode) {
        if (settingsPanelController.isOpen()) {
            return settingsPanelController.handleKeyDown(keycode);
        }

        switch (keycode) {
            case Input.Keys.SPACE -> togglePause();
            case Input.Keys.COMMA -> prevPreset();
            case Input.Keys.PERIOD -> nextPreset();
            case Input.Keys.NUM_1 -> setWarp(WarpPresets.get(0));
            case Input.Keys.NUM_2 -> setWarp(WarpPresets.get(1));
            case Input.Keys.NUM_3 -> setWarp(WarpPresets.get(2));
            case Input.Keys.NUM_4 -> setWarp(WarpPresets.get(3));
            case Input.Keys.NUM_5 -> setWarp(WarpPresets.get(4));
            case Input.Keys.NUM_6 -> setWarp(WarpPresets.get(5));
            case Input.Keys.NUM_7 -> setWarp(WarpPresets.get(6));
            case Input.Keys.NUM_8 -> setWarp(WarpPresets.get(7));
            case Input.Keys.NUM_9 -> setWarp(WarpPresets.get(8));
            case Input.Keys.NUM_0 -> setWarp(WarpPresets.get(9));
            case Input.Keys.V -> overlaySettings.toggleVisualScaleMode();
            case Input.Keys.T -> overlaySettings.cycleOrbitOverlayMode();
            case Input.Keys.Y -> overlaySettings.cycleOrbitRenderMode();
            case Input.Keys.P -> cycleFollowFrameMode();
            case Input.Keys.H -> uiState.toggleLegendVisible();
            case Input.Keys.X -> settingsPanelController.toggleMenu();
            case Input.Keys.ESCAPE -> {
                if (measureTool.isActive()) {
                    clearPendingTapState();
                    measureTool.cancel();
                } else {
                    return false;
                }
            }
            case Input.Keys.C -> toggleCameraMode();
            case Input.Keys.Z -> cycleFreeCamFovMode();
            case Input.Keys.L -> actions.toggleOrbitsDimensions().run();
            case Input.Keys.F -> clearFollowTarget();
            case Input.Keys.R -> actions.cameraReset().run();
            case Input.Keys.Q -> Gdx.app.exit();
            case Input.Keys.M -> {
                clearPendingTapState();
                measureTool.toggle();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean keyTyped(char character) {
        if (!settingsPanelController.isOpen()) {
            return false;
        }
        return settingsPanelController.handleKeyTyped(character);
    }

    private void toggleCameraMode() {
        if (camera.isCameraModeTransitionActive()) {
            return;
        }

        if (cameraSettings.getCameraMode() == CameraMode.TOP_VIEW) {
            camera.switchToFreeCamSmooth();
        } else {
            camera.switchToTopViewSmooth();
        }
    }

    private void cycleFollowFrameMode() {
        camera.cycleFollowFrameMode();
    }

    private void cycleFreeCamFovMode() {
        camera.cycleFreeCamFovMode();
    }

    private void clearFollowTarget() {
        camera.clearFollow();
    }

    private void togglePause() {
        if (simulationState.isPaused()) {
            physics.setTimeWarpFactor(prePauseWarp);
            simulationState.setPaused(false);
        } else {
            prePauseWarp = simulationSettings.getTimeWarp();
            physics.setTimeWarpFactor(0);
            simulationState.setPaused(true);
        }
    }

    private void prevPreset() {
        if (simulationState.isPaused())
            return;
        int idx = currentPresetIndex();
        if (idx > 0)
            setWarp(WarpPresets.get(idx - 1));
    }

    private void nextPreset() {
        if (simulationState.isPaused())
            return;
        int idx = currentPresetIndex();
        if (idx < WarpPresets.size() - 1)
            setWarp(WarpPresets.get(idx + 1));
    }

    /**
     * Returns the index in WARP_PRESETS closest to the current warp (log scale).
     */
    private int currentPresetIndex() {
        return WarpPresets.nearestIndex(simulationSettings.getTimeWarp());
    }

    public static String formatWarpPreset(double warp) {
        return WarpPresets.formatPreset(warp);
    }

    public static String formatWarpDisplayLabel(double warp) {
        return WarpPresets.formatDisplayLabel(warp);
    }

    /**
     * Find a body whose screen-space disc contains the click (≤30 px).
     * Dispatches to mode-specific selection:
     * FREE_CAM — depth wins: the body closest to the camera is picked,
     * because screen-space distance is ambiguous when bodies at
     * different depths overlap in projection.
     * TOP_VIEW — ancestor preference first (planet beats its moon when
     * both are under the cursor), then closest on screen wins when
     * neither is an ancestor of the other.
     */
    private CelestialBody findBodyAt(int screenX, int screenY) {
        if (camera.getMode() == CameraMode.FREE_CAM)
            return findBodyAt3D(screenX, screenY);
        return findBodyAt2D(screenX, screenY);
    }

    /**
     * FREE_CAM: ancestor wins over descendant when descendant is a small dot
     * (screen radius < DOT_THRESHOLD_PX) AND both have comparable screen size
     * (ratio > DOT_RATIO_MIN). Otherwise closest to camera wins.
     */
    private static final float DOT_THRESHOLD_PX = 15f;
    private static final float DOT_RATIO_MIN = 0.3f;

    private CelestialBody findBodyAt3D(int screenX, int screenY) {
        CelestialBody best = null;
        double bestDepth = Double.MAX_VALUE;
        float bestScreenR = 0;

        for (var obj : physics.getSimObjects()) {
            if (!(obj instanceof CelestialBody cb) || !cb.active)
                continue;
            Vector2 sc = camera.worldToScreen(cb.x, cb.y, cb.z);
            float bsy = Gdx.graphics.getHeight() - sc.y;
            float ddx = screenX - sc.x;
            float ddy = screenY - bsy;
            if (ddx * ddx + ddy * ddy > 30 * 30)
                continue;

            float screenR = camera.worldSphereRadiusToScreen(cb.radius, cb.x, cb.y, cb.z);
            double depth = camera.depthOf(cb.x, cb.y, cb.z);
            if (best == null) {
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            } else if (bestScreenR < DOT_THRESHOLD_PX && isAncestor(cb, best)
                    && bestScreenR / (screenR + 1e-6f) > DOT_RATIO_MIN) {
                // best is a small dot comparable in size to its ancestor → ancestor wins
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            } else if (screenR < DOT_THRESHOLD_PX && isAncestor(best, cb)
                    && screenR / (bestScreenR + 1e-6f) > DOT_RATIO_MIN) {
                // cb is a small dot comparable in size to its ancestor (best) → keep best
            } else if (depth < bestDepth) {
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            }
        }
        return best;
    }

    /**
     * TOP_VIEW: ancestor wins over descendant; otherwise nearest on screen wins.
     */
    private CelestialBody findBodyAt2D(int screenX, int screenY) {
        CelestialBody best = null;
        float bestDistSq = 30 * 30;

        for (var obj : physics.getSimObjects()) {
            if (!(obj instanceof CelestialBody cb) || !cb.active)
                continue;
            Vector2 sc = camera.worldToScreen(cb.x, cb.y, cb.z);
            float bsy = Gdx.graphics.getHeight() - sc.y;
            float ddx = screenX - sc.x;
            float ddy = screenY - bsy;
            float dSq = ddx * ddx + ddy * ddy;
            if (dSq > 30 * 30)
                continue;

            if (best == null) {
                best = cb;
                bestDistSq = dSq;
            } else if (isAncestor(cb, best)) {
                best = cb;
                bestDistSq = dSq;
            } else if (!isAncestor(best, cb) && dSq < bestDistSq) {
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
        if (!overlaySettings.isShowOrbitPredictors())
            return null;

        // Work in bottom-left screen coords (same as worldToScreen output).
        float sySrc = Gdx.graphics.getHeight() - screenY;
        float bestDistSq = ORBIT_HIT_PX * ORBIT_HIT_PX;
        CelestialBody bestBody = null;

        for (var obj : physics.getSimObjects()) {
            if (!(obj instanceof CelestialBody cb))
                continue;
            if (!cb.active || cb.parent == null)
                continue;

            double[] pts = orbitPredictor.computeOrbitWorldPoints(cb, cb.parent);
            if (pts == null)
                continue;

            for (int i = 0; i < pts.length / 3 - 1; i++) {
                Vector2 p0 = camera.worldToScreen(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]);
                Vector2 p1 = camera.worldToScreen(pts[(i + 1) * 3], pts[(i + 1) * 3 + 1], pts[(i + 1) * 3 + 2]);
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

    /**
     * Hit-test against recorded orbit trails. Returns the body whose trail
     * passes closest to the click within ORBIT_HIT_PX, or null.
     */
    private CelestialBody findBodyOnTrail(int screenX, int screenY) {
        if (!overlaySettings.isShowTrails())
            return null;
        float sySrc = Gdx.graphics.getHeight() - screenY;
        float bestDistSq = ORBIT_HIT_PX * ORBIT_HIT_PX;
        CelestialBody bestBody = null;

        for (var obj : physics.getSimObjects()) {
            if (!(obj instanceof CelestialBody cb) || !cb.active)
                continue;
            OrbitTrail trail = simRenderer.getTrail(obj.id);
            if (trail == null || trail.pointCount() < 2)
                continue;

            float[] coords = new float[trail.pointCount() * 2];
            int n = trail.toScreenCoords(camera, coords);
            for (int i = 0; i < n - 1; i++) {
                float distSq = pointToSegmentDistSq(screenX, sySrc,
                        coords[i * 2], coords[i * 2 + 1],
                        coords[(i + 1) * 2], coords[(i + 1) * 2 + 1]);
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
        applySimulationWarp(warp, false);
    }

    private void applySimulationWarp(double warp, boolean preservePause) {
        simulationSettings.setTimeWarp(warp);
        prePauseWarp = simulationSettings.getTimeWarp();
        if (preservePause && simulationState.isPaused()) {
            physics.setTimeWarpFactor(0);
            return;
        }
        physics.setTimeWarpFactor(simulationSettings.getTimeWarp());
        simulationState.setPaused(false);
    }

    // -------------------------------------------------------------------------
    // Mouse scroll — zoom toward cursor
    // -------------------------------------------------------------------------

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (settingsPanelController.isOpen()) {
            return true;
        }
        camera.onScroll(Gdx.input.getX(), Gdx.input.getY(), amountY);
        simRenderer.resetVsInhibit();
        return true;
    }

    // -------------------------------------------------------------------------
    // Left-mouse pan
    // -------------------------------------------------------------------------

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (settingsPanelController.isOpen()) {
            return settingsPanelController.handleTouchDown(screenX, screenY, button);
        }
        if (button == Input.Buttons.LEFT) {
            touchDownScreenX = screenX;
            touchDownScreenY = screenY;
            if (measureTool.isActive()) {
                clearPendingTapState();
                leftDragging = false;
                leftPanning = false;
                return true;
            }
            camera.onPanBegin(screenX, screenY);
            leftDragging = true;
            leftPanning = false;
            return true;
        }
        if (button == Input.Buttons.RIGHT
                && camera.getMode() == CameraMode.FREE_CAM) {
            rightDragging = true;
            lastOrbitX = screenX;
            lastOrbitY = screenY;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (settingsPanelController.isOpen()) {
            return settingsPanelController.handleTouchDragged(screenX, screenY);
        }
        if (rightDragging) {
            camera.onOrbitDrag(screenX - lastOrbitX, screenY - lastOrbitY);
            lastOrbitX = screenX;
            lastOrbitY = screenY;
            return true;
        }
        if (measureTool.isActive()) {
            return true;
        }
        if (leftDragging) {
            if (!leftPanning) {
                int dx = screenX - touchDownScreenX;
                int dy = screenY - touchDownScreenY;
                if (dx * dx + dy * dy <= TAP_DRAG_THRESHOLD_PX * TAP_DRAG_THRESHOLD_PX) {
                    return true;
                }
                leftPanning = true;
            }
            camera.onPanDrag(screenX, screenY);
            return true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (settingsPanelController.isOpen()) {
            return settingsPanelController.handleTouchUp(screenX, screenY, button);
        }
        if (button == Input.Buttons.RIGHT) {
            rightDragging = false;
            return true;
        }
        if (button == Input.Buttons.LEFT) {
            camera.onPanEnd();
            boolean wasPanning = leftPanning;
            leftDragging = false;
            leftPanning = false;
            if (wasPanning) {
                return true;
            }

            // Measure tool intercepts clicks when active.
            if (measureTool.isActive()) {
                clearPendingTapState();
                int dx = screenX - touchDownScreenX;
                int dy = screenY - touchDownScreenY;
                if (dx * dx + dy * dy <= TAP_DRAG_THRESHOLD_PX * TAP_DRAG_THRESHOLD_PX) {
                    boolean worldLocked = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                            || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
                    boolean bodySnap = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                    measureTool.onClick(screenX, screenY, worldLocked, bodySnap);
                }
                return true;
            }

            // Tap detection (no significant drag).
            int dx = screenX - touchDownScreenX;
            int dy = screenY - touchDownScreenY;
            if (dx * dx + dy * dy <= TAP_DRAG_THRESHOLD_PX * TAP_DRAG_THRESHOLD_PX) {
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
                    if (target == null)
                        target = findBodyOnTrail(screenX, screenY);
                    if (target != null) {
                        camera.startSmoothFollowTarget(target);
                        camera.startSmoothZoomTo(target.radius);
                    }
                    lastTapTimeMs = -1;
                } else {
                    // First tap: find target now but defer the follow action by 300ms
                    // so that a second tap can upgrade it to a double-tap.
                    CelestialBody target = findBodyAt(screenX, screenY);
                    if (target == null)
                        target = findBodyOnOrbit(screenX, screenY);
                    if (target == null)
                        target = findBodyOnTrail(screenX, screenY);
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

    private void clearPendingTapState() {
        pendingSingleTapBody = null;
        pendingSingleTapMs = -1;
        lastTapTimeMs = -1;
    }

    // -------------------------------------------------------------------------
    // Per-frame update (call from game loop)
    // -------------------------------------------------------------------------

    /**
     * Must be called once per frame from the game loop.
     * Fires deferred single-tap actions once the double-click window has expired.
     */
    public void update(float dt) {
        if (settingsPanelController.isOpen()) {
            settingsPanelController.update(dt);
            return;
        }

        pollContinuousCameraInput(dt);

        if (pendingSingleTapMs >= 0) {
            if (System.currentTimeMillis() - pendingSingleTapMs >= 300) {
                if (pendingSingleTapBody != null) {
                    camera.startSmoothFollowTarget(pendingSingleTapBody);
                } else {
                    camera.setFollowTarget(null); // tap on empty space → unfollow
                }
                pendingSingleTapBody = null;
                pendingSingleTapMs = -1;
                lastTapTimeMs = -1;
            }
        }
    }

    private void pollContinuousCameraInput(float dt) {
        if (camera.getMode() == CameraMode.FREE_CAM) {
            float rot = CAMERA_KEY_ROT_SPEED * dt;
            if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
                camera.rotateFreeCamBy(rot, 0f);
            }
            if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
                camera.rotateFreeCamBy(-rot, 0f);
            }
            if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
                camera.rotateFreeCamBy(0f, rot);
            }
            if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
                camera.rotateFreeCamBy(0f, -rot);
            }
            return;
        }

        float panMeters = CAMERA_ARROW_PAN_PX_PER_SEC * camera.getMetersPerPixel() * dt;
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            camera.panTopViewByWorld(-panMeters, 0.0);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            camera.panTopViewByWorld(panMeters, 0.0);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            camera.panTopViewByWorld(0.0, -panMeters);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
            camera.panTopViewByWorld(0.0, panMeters);
        }
    }

    private void resetPointerDragging() {
        leftDragging = false;
        leftPanning = false;
        rightDragging = false;
    }
}
