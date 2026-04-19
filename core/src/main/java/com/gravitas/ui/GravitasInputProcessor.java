package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.math.Vector2;
import com.gravitas.entities.CelestialBody;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.celestial_body.CelestialFxSettings;
import com.gravitas.rendering.core.SimRenderer;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.rendering.orbit.OrbitRenderMode;
import com.gravitas.rendering.orbit.OrbitPredictor;
import com.gravitas.rendering.orbit.OrbitTrail;

/**
 * Handles all keyboard / mouse input for the simulation.
 *
 * Keyboard:
 * SPACE — pause / resume
 * , (comma) — previous warp preset
 * . (period) — next warp preset
 * 1-0 — warp presets: 1x 10x 1k 10k 100k 500k 1M 10M 100M 1B
 * V — toggle visual scale mode (exaggerated planet sizes)
 * T — cycle overlays (trails / trails+orbits / trails+orbits+spin / none)
 * Y — cycle orbit renderer (solid / CPU dashed / GPU dashed)
 * P — cycle follow mode (free / orbit upright / orbit plane / orbit axial /
 * rotation axial)
 * C — toggle camera mode (TOP_VIEW / FREE_CAM)
 * Z — toggle FREE_CAM FOV mode (adaptive / fixed)
 * L — toggle legacy 2D / full 3D mode
 * F — clear follow target
 * R — reset camera to nearest system star
 * X — open celestial FX menu
 *
 * Mouse:
 * Left drag — pan
 * Right drag (FREE_CAM) — orbit camera around focus
 * Scroll — zoom toward cursor / dolly
 */
public class GravitasInputProcessor extends InputAdapter {

    public enum OverlayArtifactsMode {
        TRAILS_ONLY("Trails", true, false, false),
        TRAILS_AND_ORBITS("Trails+Orbits", true, true, false),
        TRAILS_ORBITS_AND_SPIN("Trails+Orbits+Spin", true, true, true),
        NONE("None", false, false, false);

        private final String hudLabel;
        private final boolean showTrails;
        private final boolean showOrbitPredictors;
        private final boolean showSpinAxis;

        OverlayArtifactsMode(String hudLabel, boolean showTrails, boolean showOrbitPredictors,
                boolean showSpinAxis) {
            this.hudLabel = hudLabel;
            this.showTrails = showTrails;
            this.showOrbitPredictors = showOrbitPredictors;
            this.showSpinAxis = showSpinAxis;
        }

        public String hudLabel() {
            return hudLabel;
        }

        public boolean showTrails() {
            return showTrails;
        }

        public boolean showOrbitPredictors() {
            return showOrbitPredictors;
        }

        public boolean showSpinAxis() {
            return showSpinAxis;
        }

        public OverlayArtifactsMode next() {
            OverlayArtifactsMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }
    }

    private enum CelestialFxMenuPage {
        MAIN("FX Settings"),
        CLOUD("Clouds Settings"),
        LIGHTING("Lighting Settings"),
        ATMOSPHERE("Atmosphere Settings");

        private final String title;

        CelestialFxMenuPage(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    private static final float CAMERA_ARROW_PAN_PX_PER_SEC = 400f;
    private static final float CAMERA_KEY_ROT_SPEED = 1.5f;
    private static final float CELESTIAL_FX_PANEL_MARGIN_PX = 12f;
    private static final float CELESTIAL_FX_STATUS_DURATION_SEC = 1.5f;
    private static final float CELESTIAL_FX_EDIT_REPEAT_DELAY_SEC = 0.35f;
    private static final float CELESTIAL_FX_EDIT_REPEAT_INTERVAL_SEC = 0.05f;
    private static final int CELESTIAL_FX_MAIN_OPTION_COUNT = 4;
    private static final int CELESTIAL_FX_CLOUD_OPTION_COUNT = 8;
    private static final int CELESTIAL_FX_LIGHTING_OPTION_COUNT = 3;
    private static final int CELESTIAL_FX_ATMOSPHERE_OPTION_COUNT = 6;

    private static final double[] WARP_PRESETS = {
            1, 10, 1_000, 10_000, 100_000, 500_000,
            1_000_000, 10_000_000, 100_000_000, 1_000_000_000
    };

    private static final String[] WARP_PRESET_LABELS = {
            "1x [1]", "10x [2]", "1000x [3]", "10000x [4]", "100000x [5]",
            "500000x [6]", "1000000x [7]", "10000000x [8]", "100000000x [9]", "1000000000x [0]"
    };

    private final PhysicsEngine physics;
    private final WorldCamera camera;
    private final OrbitPredictor orbitPredictor;
    private SimRenderer simRenderer;
    private MeasureTool measureTool;
    private Runnable dimensionToggle;
    private Runnable cameraReset;

    private boolean paused = false;
    private double prePauseWarp = 1.0;

    // Toggleable modes (read by SimRenderer / OrbitPredictor via accessors)
    private boolean visualScaleMode = true;
    private OverlayArtifactsMode overlayArtifactsMode = OverlayArtifactsMode.TRAILS_ONLY;
    private final CelestialFxSettings celestialFxSettings = new CelestialFxSettings();
    private boolean celestialFxMenuOpen = false;
    private CelestialFxMenuPage celestialFxMenuPage = CelestialFxMenuPage.MAIN;
    private boolean celestialFxMenuEditMode = false;
    private int celestialFxMainSelection = 0;
    private int celestialFxCloudSelection = 0;
    private int celestialFxLightingSelection = 0;
    private int celestialFxAtmosphereSelection = 0;
    private int celestialFxEditRepeatDirection = 0;
    private float celestialFxEditRepeatTimer = 0f;
    private boolean celestialFxEditRepeatDelayed = false;
    private float celestialFxEditStartValue = 0f;
    private String celestialFxManualInputBuffer = "";
    private boolean celestialFxPanelDragging = false;
    private boolean celestialFxPanelPositioned = false;
    private float celestialFxPanelX = 0f;
    private float celestialFxPanelY = 0f;
    private float celestialFxPanelWidth = 0f;
    private float celestialFxPanelHeight = 0f;
    private float celestialFxPanelDragOffsetX = 0f;
    private float celestialFxPanelDragOffsetY = 0f;
    private String celestialFxStatusText = "";
    private float celestialFxStatusTimer = 0f;

    // Left-mouse pan state.
    private boolean leftDragging = false;

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
        if (celestialFxMenuOpen) {
            return handleCelestialFxMenuKey(keycode);
        }

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
            case Input.Keys.T -> overlayArtifactsMode = overlayArtifactsMode.next();
            case Input.Keys.Y -> orbitPredictor.cycleOrbitRenderMode();
            case Input.Keys.P -> camera.cycleFollowFrameMode();
            case Input.Keys.X -> toggleCelestialFxMenu();
            case Input.Keys.ESCAPE -> {
                if (measureTool != null && measureTool.isActive()) {
                    clearPendingTapState();
                    measureTool.cancel();
                } else {
                    return false;
                }
            }
            case Input.Keys.C -> toggleCameraMode();
            case Input.Keys.Z -> camera.cycleFreeCamFovMode();
            case Input.Keys.L -> {
                if (dimensionToggle != null)
                    dimensionToggle.run();
            }
            case Input.Keys.F -> camera.clearFollow();
            case Input.Keys.R -> {
                if (cameraReset != null)
                    cameraReset.run();
            }
            case Input.Keys.Q -> Gdx.app.exit();
            case Input.Keys.M -> {
                clearPendingTapState();
                if (measureTool != null)
                    measureTool.toggle();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean handleCelestialFxMenuKey(int keycode) {
        if (celestialFxMenuEditMode) {
            return handleCelestialFxMenuEditKey(keycode);
        }

        switch (keycode) {
            case Input.Keys.X -> celestialFxMenuOpen = false;
            case Input.Keys.ESCAPE, Input.Keys.LEFT -> backOutOfCelestialFxMenu();
            case Input.Keys.UP -> moveCelestialFxSelection(-1);
            case Input.Keys.DOWN -> moveCelestialFxSelection(1);
            case Input.Keys.NUM_1 -> {
                if (celestialFxMenuPage == CelestialFxMenuPage.MAIN) {
                    celestialFxMainSelection = 0;
                    activateCelestialFxSelection();
                }
            }
            case Input.Keys.NUM_2 -> {
                if (celestialFxMenuPage == CelestialFxMenuPage.MAIN) {
                    celestialFxMainSelection = 1;
                    activateCelestialFxSelection();
                }
            }
            case Input.Keys.NUM_3 -> {
                if (celestialFxMenuPage == CelestialFxMenuPage.MAIN) {
                    celestialFxMainSelection = 2;
                    activateCelestialFxSelection();
                }
            }
            case Input.Keys.NUM_4 -> {
                if (celestialFxMenuPage == CelestialFxMenuPage.MAIN) {
                    celestialFxMainSelection = 3;
                    activateCelestialFxSelection();
                }
            }
            case Input.Keys.NUM_5 -> {
                return true;
            }
            case Input.Keys.ENTER, Input.Keys.SPACE, Input.Keys.RIGHT -> activateCelestialFxSelection();
            default -> {
                return true;
            }
        }
        return true;
    }

    private boolean handleCelestialFxMenuEditKey(int keycode) {
        switch (keycode) {
            case Input.Keys.X -> celestialFxMenuOpen = false;
            case Input.Keys.ESCAPE, Input.Keys.LEFT -> cancelCelestialFxEditMode();
            case Input.Keys.ENTER, Input.Keys.SPACE, Input.Keys.RIGHT -> commitCelestialFxEditMode();
            case Input.Keys.UP -> nudgeCelestialFxValue(1);
            case Input.Keys.DOWN -> nudgeCelestialFxValue(-1);
            case Input.Keys.BACKSPACE -> handleCelestialFxBackspace();
            default -> {
                return true;
            }
        }
        return true;
    }

    private void toggleCelestialFxMenu() {
        celestialFxMenuOpen = !celestialFxMenuOpen;
        celestialFxPanelDragging = false;
        if (celestialFxMenuOpen) {
            celestialFxMenuPage = CelestialFxMenuPage.MAIN;
            clearCelestialFxStatus();
            exitCelestialFxEditMode();
            clearPendingTapState();
            camera.onPanEnd();
            leftDragging = false;
            rightDragging = false;
        }
    }

    private void backOutOfCelestialFxMenu() {
        if (celestialFxMenuPage == CelestialFxMenuPage.MAIN) {
            celestialFxMenuOpen = false;
            return;
        }

        celestialFxMenuPage = CelestialFxMenuPage.MAIN;
        exitCelestialFxEditMode();
    }

    private void moveCelestialFxSelection(int delta) {
        switch (celestialFxMenuPage) {
            case MAIN -> celestialFxMainSelection = wrapSelection(celestialFxMainSelection + delta,
                    CELESTIAL_FX_MAIN_OPTION_COUNT);
            case CLOUD -> celestialFxCloudSelection = wrapSelection(celestialFxCloudSelection + delta,
                    CELESTIAL_FX_CLOUD_OPTION_COUNT);
            case LIGHTING -> celestialFxLightingSelection = wrapSelection(celestialFxLightingSelection + delta,
                    CELESTIAL_FX_LIGHTING_OPTION_COUNT);
            case ATMOSPHERE -> celestialFxAtmosphereSelection = wrapSelection(celestialFxAtmosphereSelection + delta,
                    CELESTIAL_FX_ATMOSPHERE_OPTION_COUNT);
        }
    }

    private int wrapSelection(int index, int count) {
        return (index + count) % count;
    }

    private void activateCelestialFxSelection() {
        switch (celestialFxMenuPage) {
            case MAIN -> activateCelestialFxMainSelection();
            case CLOUD -> activateCelestialFxCloudSelection();
            case LIGHTING -> activateCelestialFxLightingSelection();
            case ATMOSPHERE -> activateCelestialFxAtmosphereSelection();
        }
    }

    private void activateCelestialFxMainSelection() {
        switch (celestialFxMainSelection) {
            case 0 -> {
                celestialFxMenuPage = CelestialFxMenuPage.CLOUD;
                exitCelestialFxEditMode();
            }
            case 1 -> {
                celestialFxMenuPage = CelestialFxMenuPage.LIGHTING;
                exitCelestialFxEditMode();
            }
            case 2 -> {
                celestialFxMenuPage = CelestialFxMenuPage.ATMOSPHERE;
                exitCelestialFxEditMode();
            }
            case 3 -> {
                celestialFxSettings.resetToDefaults();
                showCelestialFxStatus("Defaults restored");
            }
            default -> {
            }
        }
    }

    private void activateCelestialFxCloudSelection() {
        switch (celestialFxCloudSelection) {
            case 0 -> backOutOfCelestialFxMenu();
            case 1 -> celestialFxSettings.cycleCloudFxMode();
            case 2 -> celestialFxSettings.toggleCloudDayNightEnabled();
            case 3 -> celestialFxSettings.cycleCloudTerminatorMode();
            case 4 -> celestialFxSettings.cycleCloudCompositingMode();
            case 5, 6, 7 -> toggleCelestialFxEditMode();
            default -> {
            }
        }
    }

    private void activateCelestialFxLightingSelection() {
        switch (celestialFxLightingSelection) {
            case 0 -> backOutOfCelestialFxMenu();
            case 1 -> celestialFxSettings.toggleDayNightEnabled();
            case 2 -> celestialFxSettings.toggleRingShadowEnabled();
            default -> {
            }
        }
    }

    private void activateCelestialFxAtmosphereSelection() {
        switch (celestialFxAtmosphereSelection) {
            case 0 -> backOutOfCelestialFxMenu();
            case 1 -> celestialFxSettings.toggleAtmosphereDayNightEnabled();
            case 2, 3, 4, 5 -> toggleCelestialFxEditMode();
            default -> {
            }
        }
    }

    private void toggleCelestialFxEditMode() {
        if (celestialFxMenuEditMode) {
            commitCelestialFxEditMode();
            return;
        }
        celestialFxEditStartValue = currentCelestialFxEditableValue();
        celestialFxMenuEditMode = true;
        celestialFxEditRepeatDirection = 0;
        celestialFxEditRepeatTimer = 0f;
        celestialFxEditRepeatDelayed = false;
        celestialFxManualInputBuffer = "";
    }

    private void exitCelestialFxEditMode() {
        celestialFxMenuEditMode = false;
        celestialFxEditRepeatDirection = 0;
        celestialFxEditRepeatTimer = 0f;
        celestialFxEditRepeatDelayed = false;
        celestialFxManualInputBuffer = "";
    }

    private void commitCelestialFxEditMode() {
        if (!celestialFxManualInputBuffer.isEmpty()) {
            applyCelestialFxAbsoluteValue(celestialFxManualInputBuffer);
        }
        exitCelestialFxEditMode();
    }

    private void cancelCelestialFxEditMode() {
        if (isCurrentSelectionNumericEditable()) {
            applyCelestialFxStoredValue(celestialFxEditStartValue);
        }
        exitCelestialFxEditMode();
    }

    private void handleCelestialFxBackspace() {
        if (celestialFxManualInputBuffer.isEmpty()) {
            return;
        }
        celestialFxManualInputBuffer = celestialFxManualInputBuffer.substring(0,
                celestialFxManualInputBuffer.length() - 1);
    }

    private void nudgeCelestialFxValue(int direction) {
        celestialFxManualInputBuffer = "";
        adjustCelestialFxValue(direction * CelestialFxSettings.VALUE_STEP);
        celestialFxEditRepeatDirection = direction;
        celestialFxEditRepeatTimer = 0f;
        celestialFxEditRepeatDelayed = false;
    }

    private void adjustCelestialFxValue(float delta) {
        switch (celestialFxMenuPage) {
            case CLOUD -> {
                switch (celestialFxCloudSelection) {
                    case 5 -> celestialFxSettings.addCloudProceduralTextureCoupling(delta);
                    case 6 -> celestialFxSettings.addCloudTextureAlphaWeight(delta);
                    case 7 -> celestialFxSettings.addCloudProceduralAlphaWeight(delta);
                    default -> {
                    }
                }
            }
            case ATMOSPHERE -> {
                switch (celestialFxAtmosphereSelection) {
                    case 2 -> celestialFxSettings.addAtmosphereNightOuterFloor(delta);
                    case 3 -> celestialFxSettings.addAtmosphereNightInnerFloor(delta);
                    case 4 -> celestialFxSettings.addAtmosphereDenseNightOuterFloor(delta);
                    case 5 -> celestialFxSettings.addAtmosphereDenseNightInnerFloor(delta);
                    default -> {
                    }
                }
            }
            case MAIN, LIGHTING -> {
            }
        }
    }

    private void applyCelestialFxAbsoluteValue(String rawValue) {
        if (!isCurrentSelectionNumericEditable()) {
            return;
        }

        try {
            float value = Float.parseFloat(rawValue);
            switch (celestialFxMenuPage) {
                case CLOUD -> {
                    switch (celestialFxCloudSelection) {
                        case 5 -> celestialFxSettings.setCloudProceduralTextureCoupling(value);
                        case 6 -> celestialFxSettings.setCloudTextureAlphaWeight(value);
                        case 7 -> celestialFxSettings.setCloudProceduralAlphaWeight(value);
                        default -> {
                        }
                    }
                }
                case ATMOSPHERE -> {
                    switch (celestialFxAtmosphereSelection) {
                        case 2 -> celestialFxSettings.setAtmosphereNightOuterFloor(value);
                        case 3 -> celestialFxSettings.setAtmosphereNightInnerFloor(value);
                        case 4 -> celestialFxSettings.setAtmosphereDenseNightOuterFloor(value);
                        case 5 -> celestialFxSettings.setAtmosphereDenseNightInnerFloor(value);
                        default -> {
                        }
                    }
                }
                case MAIN, LIGHTING -> {
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyCelestialFxStoredValue(float value) {
        switch (celestialFxMenuPage) {
            case CLOUD -> {
                switch (celestialFxCloudSelection) {
                    case 5 -> celestialFxSettings.setCloudProceduralTextureCoupling(value);
                    case 6 -> celestialFxSettings.setCloudTextureAlphaWeight(value);
                    case 7 -> celestialFxSettings.setCloudProceduralAlphaWeight(value);
                    default -> {
                    }
                }
            }
            case ATMOSPHERE -> {
                switch (celestialFxAtmosphereSelection) {
                    case 2 -> celestialFxSettings.setAtmosphereNightOuterFloor(value);
                    case 3 -> celestialFxSettings.setAtmosphereNightInnerFloor(value);
                    case 4 -> celestialFxSettings.setAtmosphereDenseNightOuterFloor(value);
                    case 5 -> celestialFxSettings.setAtmosphereDenseNightInnerFloor(value);
                    default -> {
                    }
                }
            }
            case MAIN, LIGHTING -> {
            }
        }
    }

    private float currentCelestialFxEditableValue() {
        return switch (celestialFxMenuPage) {
            case CLOUD -> switch (celestialFxCloudSelection) {
                case 5 -> celestialFxSettings.getCloudProceduralTextureCoupling();
                case 6 -> celestialFxSettings.getCloudTextureAlphaWeight();
                case 7 -> celestialFxSettings.getCloudProceduralAlphaWeight();
                default -> 0f;
            };
            case ATMOSPHERE -> switch (celestialFxAtmosphereSelection) {
                case 2 -> celestialFxSettings.getAtmosphereNightOuterFloor();
                case 3 -> celestialFxSettings.getAtmosphereNightInnerFloor();
                case 4 -> celestialFxSettings.getAtmosphereDenseNightOuterFloor();
                case 5 -> celestialFxSettings.getAtmosphereDenseNightInnerFloor();
                default -> 0f;
            };
            case MAIN, LIGHTING -> 0f;
        };
    }

    @Override
    public boolean keyTyped(char character) {
        if (!celestialFxMenuOpen || !isCurrentSelectionNumericEditable()) {
            return false;
        }

        if ((character >= '0' && character <= '9') || character == '.' || character == ',') {
            if (!celestialFxMenuEditMode) {
                toggleCelestialFxEditMode();
            }
            appendCelestialFxInputChar(character == ',' ? '.' : character);
            return true;
        }
        return false;
    }

    private void appendCelestialFxInputChar(char character) {
        if (character == '.' && celestialFxManualInputBuffer.contains(".")) {
            return;
        }
        if (character == '.' && celestialFxManualInputBuffer.isEmpty()) {
            celestialFxManualInputBuffer = "0.";
            return;
        }
        celestialFxManualInputBuffer += character;
    }

    private boolean isCurrentSelectionNumericEditable() {
        return switch (celestialFxMenuPage) {
            case CLOUD -> celestialFxCloudSelection >= 5;
            case LIGHTING -> false;
            case ATMOSPHERE -> celestialFxAtmosphereSelection >= 2;
            case MAIN -> false;
        };
    }

    private void toggleCameraMode() {
        if (camera.isCameraModeTransitionActive()) {
            return;
        }

        if (camera.getMode() == WorldCamera.CameraMode.TOP_VIEW) {
            camera.switchToFreeCamSmooth();
        } else {
            camera.switchToTopViewSmooth();
        }
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
        return nearestWarpPresetIndex(physics.getTimeWarpFactor());
    }

    public static String formatWarpPreset(double warp) {
        return WARP_PRESET_LABELS[nearestWarpPresetIndex(warp)];
    }

    private static int nearestWarpPresetIndex(double warp) {
        double safeWarp = Math.max(1.0, warp);
        double warpLog10 = Math.log10(safeWarp);
        int bestIndex = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < WARP_PRESETS.length; i++) {
            double distance = Math.abs(warpLog10 - Math.log10(WARP_PRESETS[i]));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
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
        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM)
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

        for (var obj : physics.getObjects()) {
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

        for (var obj : physics.getObjects()) {
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
        if (!overlayArtifactsMode.showOrbitPredictors())
            return null;

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
        if (simRenderer == null || !overlayArtifactsMode.showTrails())
            return null;
        float sySrc = Gdx.graphics.getHeight() - screenY;
        float bestDistSq = ORBIT_HIT_PX * ORBIT_HIT_PX;
        CelestialBody bestBody = null;

        for (var obj : physics.getObjects()) {
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
        physics.setTimeWarpFactor(warp);
        paused = false;
    }

    // -------------------------------------------------------------------------
    // Mouse scroll — zoom toward cursor
    // -------------------------------------------------------------------------

    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (celestialFxMenuOpen) {
            return true;
        }
        camera.onScroll(Gdx.input.getX(), Gdx.input.getY(), amountY);
        if (simRenderer != null)
            simRenderer.resetVsInhibit();
        return true;
    }

    // -------------------------------------------------------------------------
    // Left-mouse pan
    // -------------------------------------------------------------------------

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (celestialFxMenuOpen) {
            clearPendingTapState();
            leftDragging = false;
            rightDragging = false;
            if (button == Input.Buttons.LEFT) {
                float uiY = Gdx.graphics.getHeight() - screenY;
                if (isInsideCelestialFxPanel(screenX, uiY)) {
                    celestialFxPanelDragging = true;
                    celestialFxPanelDragOffsetX = screenX - celestialFxPanelX;
                    celestialFxPanelDragOffsetY = uiY - celestialFxPanelY;
                }
            }
            return true;
        }
        if (button == Input.Buttons.LEFT) {
            touchDownScreenX = screenX;
            touchDownScreenY = screenY;
            if (measureTool != null && measureTool.isActive()) {
                clearPendingTapState();
                leftDragging = false;
                return true;
            }
            camera.onPanBegin(screenX, screenY);
            leftDragging = true;
            return true;
        }
        if (button == Input.Buttons.RIGHT
                && camera.getMode() == WorldCamera.CameraMode.FREE_CAM) {
            rightDragging = true;
            lastOrbitX = screenX;
            lastOrbitY = screenY;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (celestialFxMenuOpen) {
            if (celestialFxPanelDragging) {
                float uiY = Gdx.graphics.getHeight() - screenY;
                celestialFxPanelPositioned = true;
                celestialFxPanelX = screenX - celestialFxPanelDragOffsetX;
                celestialFxPanelY = uiY - celestialFxPanelDragOffsetY;
                clampCelestialFxPanelPosition(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(),
                        celestialFxPanelWidth, celestialFxPanelHeight);
            }
            return true;
        }
        if (rightDragging) {
            camera.onOrbitDrag(screenX - lastOrbitX, screenY - lastOrbitY);
            lastOrbitX = screenX;
            lastOrbitY = screenY;
            return true;
        }
        if (measureTool != null && measureTool.isActive()) {
            return true;
        }
        if (leftDragging) {
            camera.onPanDrag(screenX, screenY);
            return true;
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (celestialFxMenuOpen) {
            leftDragging = false;
            rightDragging = false;
            if (button == Input.Buttons.LEFT) {
                celestialFxPanelDragging = false;
            }
            return true;
        }
        if (button == Input.Buttons.RIGHT) {
            rightDragging = false;
            return true;
        }
        if (button == Input.Buttons.LEFT) {
            camera.onPanEnd();
            leftDragging = false;

            // Measure tool intercepts clicks when active.
            if (measureTool != null && measureTool.isActive()) {
                clearPendingTapState();
                int dx = screenX - touchDownScreenX;
                int dy = screenY - touchDownScreenY;
                if (dx * dx + dy * dy <= 12 * 12) {
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
                    if (target == null)
                        target = findBodyOnTrail(screenX, screenY);
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
        if (celestialFxMenuOpen) {
            updateCelestialFxMenu(dt);
            return;
        }

        pollContinuousCameraInput(dt);

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

    private void updateCelestialFxMenu(float dt) {
        if (celestialFxStatusTimer > 0f) {
            celestialFxStatusTimer = Math.max(0f, celestialFxStatusTimer - dt);
            if (celestialFxStatusTimer == 0f) {
                celestialFxStatusText = "";
            }
        }

        if (!celestialFxMenuEditMode || !isCurrentSelectionNumericEditable()) {
            celestialFxEditRepeatDirection = 0;
            celestialFxEditRepeatTimer = 0f;
            celestialFxEditRepeatDelayed = false;
            return;
        }

        int direction = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
            direction = 1;
        } else if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            direction = -1;
        }

        if (direction == 0) {
            celestialFxEditRepeatDirection = 0;
            celestialFxEditRepeatTimer = 0f;
            celestialFxEditRepeatDelayed = false;
            return;
        }

        if (celestialFxEditRepeatDirection != direction) {
            celestialFxEditRepeatDirection = direction;
            celestialFxEditRepeatTimer = 0f;
            celestialFxEditRepeatDelayed = false;
            return;
        }

        celestialFxEditRepeatTimer += dt;
        if (!celestialFxEditRepeatDelayed) {
            if (celestialFxEditRepeatTimer >= CELESTIAL_FX_EDIT_REPEAT_DELAY_SEC) {
                celestialFxEditRepeatTimer -= CELESTIAL_FX_EDIT_REPEAT_DELAY_SEC;
                celestialFxEditRepeatDelayed = true;
                celestialFxManualInputBuffer = "";
                adjustCelestialFxValue(direction * CelestialFxSettings.VALUE_STEP);
            }
            return;
        }

        while (celestialFxEditRepeatTimer >= CELESTIAL_FX_EDIT_REPEAT_INTERVAL_SEC) {
            celestialFxEditRepeatTimer -= CELESTIAL_FX_EDIT_REPEAT_INTERVAL_SEC;
            celestialFxManualInputBuffer = "";
            adjustCelestialFxValue(direction * CelestialFxSettings.VALUE_STEP);
        }
    }

    private void pollContinuousCameraInput(float dt) {
        if (camera.getMode() == WorldCamera.CameraMode.FREE_CAM) {
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
        return overlayArtifactsMode.showOrbitPredictors();
    }

    public boolean isShowTrails() {
        return overlayArtifactsMode.showTrails();
    }

    public boolean isShowSpinAxisOverlay() {
        return overlayArtifactsMode.showSpinAxis();
    }

    public OverlayArtifactsMode getOverlayArtifactsMode() {
        return overlayArtifactsMode;
    }

    public boolean isCloudFxEnabled() {
        return !celestialFxSettings.getCloudFxMode().isOff();
    }

    public CelestialFxSettings getCelestialFxSettings() {
        return celestialFxSettings;
    }

    public boolean isSurfaceFxEnabled() {
        return celestialFxSettings.isDayNightEnabled();
    }

    public boolean isRingShadowFxEnabled() {
        return celestialFxSettings.isRingShadowEnabled();
    }

    public boolean isCelestialFxMenuOpen() {
        return celestialFxMenuOpen;
    }

    public int getCelestialFxMenuSelection() {
        return switch (celestialFxMenuPage) {
            case MAIN -> celestialFxMainSelection;
            case CLOUD -> celestialFxCloudSelection;
            case LIGHTING -> celestialFxLightingSelection;
            case ATMOSPHERE -> celestialFxAtmosphereSelection;
        };
    }

    public String getCelestialFxMenuTitle() {
        return celestialFxMenuPage.title();
    }

    public String getCelestialFxMenuHint() {
        if (celestialFxMenuEditMode) {
            return "Type value, Up/Down nudge, Enter apply, Esc/Left cancel";
        }
        return switch (celestialFxMenuPage) {
            case MAIN -> "Up/Down select, Enter open, 1-4 quick open, X/Esc close";
            case CLOUD, LIGHTING, ATMOSPHERE -> "Up/Down select, Enter change, Left back, X/Esc close";
        };
    }

    public int getCelestialFxMenuOptionCount() {
        return switch (celestialFxMenuPage) {
            case MAIN -> CELESTIAL_FX_MAIN_OPTION_COUNT;
            case CLOUD -> CELESTIAL_FX_CLOUD_OPTION_COUNT;
            case LIGHTING -> CELESTIAL_FX_LIGHTING_OPTION_COUNT;
            case ATMOSPHERE -> CELESTIAL_FX_ATMOSPHERE_OPTION_COUNT;
        };
    }

    public String getCelestialFxOptionLine(int index) {
        return switch (celestialFxMenuPage) {
            case MAIN -> mainCelestialFxOptionLine(index);
            case CLOUD -> cloudCelestialFxOptionLine(index);
            case LIGHTING -> lightingCelestialFxOptionLine(index);
            case ATMOSPHERE -> atmosphereCelestialFxOptionLine(index);
        };
    }

    public boolean isCelestialFxMenuEditMode() {
        return celestialFxMenuEditMode;
    }

    private String mainCelestialFxOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Clouds Settings >";
            case 1 -> "2. Lighting Settings >";
            case 2 -> "3. Atmosphere Settings >";
            case 3 -> "4. Restore Defaults";
            default -> "?";
        };
    }

    public boolean isCelestialFxStatusVisible() {
        return celestialFxStatusTimer > 0f && !celestialFxStatusText.isEmpty();
    }

    public String getCelestialFxStatusText() {
        return celestialFxStatusText;
    }

    private String cloudCelestialFxOptionLine(int index) {
        String edit = editSuffix(index, celestialFxCloudSelection);
        return switch (index) {
            case 0 -> "1. < Back";
            case 1 -> "2. Mode [" + celestialFxSettings.getCloudFxMode().hudLabel() + "]";
            case 2 -> "3. Day/Night [" + subordinateDayNightLabel(celestialFxSettings.isCloudDayNightEnabled()) + "]";
            case 3 -> "4. Terminator [" + celestialFxSettings.getCloudTerminatorMode().hudLabel() + "]";
            case 4 -> "5. Compositing [" + celestialFxSettings.getCloudCompositingMode().hudLabel() + "]";
            case 5 -> "6. Coupling [" + formatFxValue(celestialFxSettings.getCloudProceduralTextureCoupling()) + "]"
                    + edit;
            case 6 -> "7. Texture Alpha [" + formatFxValue(celestialFxSettings.getCloudTextureAlphaWeight()) + "]"
                    + edit;
            case 7 -> "8. Procedural Alpha [" + formatFxValue(celestialFxSettings.getCloudProceduralAlphaWeight())
                    + "]" + edit;
            default -> "?";
        };
    }

    private String lightingCelestialFxOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. < Back";
            case 1 -> "2. Global Day/Night [" + (celestialFxSettings.isDayNightEnabled() ? "ON" : "OFF") + "]";
            case 2 -> "3. Ring Shadows [" + (celestialFxSettings.isRingShadowEnabled() ? "ON" : "OFF") + "]";
            default -> "?";
        };
    }

    private String atmosphereCelestialFxOptionLine(int index) {
        String edit = editSuffix(index, celestialFxAtmosphereSelection);
        return switch (index) {
            case 0 -> "1. < Back";
            case 1 ->
                "2. Day/Night [" + subordinateDayNightLabel(celestialFxSettings.isAtmosphereDayNightEnabled()) + "]";
            case 2 -> "3. Base Night Outer [" + formatFxValue(celestialFxSettings.getAtmosphereNightOuterFloor()) + "]"
                    + edit;
            case 3 -> "4. Base Night Inner [" + formatFxValue(celestialFxSettings.getAtmosphereNightInnerFloor()) + "]"
                    + edit;
            case 4 -> "5. Dense Night Outer [" + formatFxValue(celestialFxSettings.getAtmosphereDenseNightOuterFloor())
                    + "]" + edit;
            case 5 -> "6. Dense Night Inner [" + formatFxValue(celestialFxSettings.getAtmosphereDenseNightInnerFloor())
                    + "]" + edit;
            default -> "?";
        };
    }

    private String subordinateDayNightLabel(boolean localEnabled) {
        String localLabel = localEnabled ? "ON" : "OFF";
        if (celestialFxSettings.isDayNightEnabled()) {
            return localLabel;
        }
        return localLabel + ", global OFF";
    }

    private String formatFxValue(float value) {
        return String.format("%.2f", value);
    }

    private String editSuffix(int index, int selection) {
        if (!celestialFxMenuEditMode || selection != index) {
            return "";
        }
        if (!celestialFxManualInputBuffer.isEmpty()) {
            return " <TYPE " + celestialFxManualInputBuffer + ">";
        }
        return " <EDIT>";
    }

    public float resolveCelestialFxPanelX(int screenWidth, float panelWidth) {
        if (!celestialFxPanelPositioned) {
            return screenWidth * 0.5f - panelWidth * 0.5f;
        }
        clampCelestialFxPanelPosition(screenWidth, Gdx.graphics.getHeight(), panelWidth, celestialFxPanelHeight);
        return celestialFxPanelX;
    }

    public float resolveCelestialFxPanelY(int screenHeight, float panelHeight) {
        if (!celestialFxPanelPositioned) {
            return screenHeight * 0.5f - panelHeight * 0.5f;
        }
        clampCelestialFxPanelPosition(Gdx.graphics.getWidth(), screenHeight, celestialFxPanelWidth, panelHeight);
        return celestialFxPanelY;
    }

    public void setCelestialFxPanelBounds(float x, float y, float width, float height) {
        celestialFxPanelWidth = width;
        celestialFxPanelHeight = height;
        if (!celestialFxPanelPositioned) {
            celestialFxPanelX = x;
            celestialFxPanelY = y;
        }
        clampCelestialFxPanelPosition(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), width, height);
    }

    private boolean isInsideCelestialFxPanel(float x, float y) {
        return x >= celestialFxPanelX && x <= celestialFxPanelX + celestialFxPanelWidth
                && y >= celestialFxPanelY && y <= celestialFxPanelY + celestialFxPanelHeight;
    }

    private void clampCelestialFxPanelPosition(int screenWidth, int screenHeight, float panelWidth, float panelHeight) {
        float maxX = Math.max(CELESTIAL_FX_PANEL_MARGIN_PX, screenWidth - panelWidth - CELESTIAL_FX_PANEL_MARGIN_PX);
        float maxY = Math.max(CELESTIAL_FX_PANEL_MARGIN_PX, screenHeight - panelHeight - CELESTIAL_FX_PANEL_MARGIN_PX);
        celestialFxPanelX = Math.max(CELESTIAL_FX_PANEL_MARGIN_PX, Math.min(celestialFxPanelX, maxX));
        celestialFxPanelY = Math.max(CELESTIAL_FX_PANEL_MARGIN_PX, Math.min(celestialFxPanelY, maxY));
    }

    private void showCelestialFxStatus(String text) {
        celestialFxStatusText = text;
        celestialFxStatusTimer = CELESTIAL_FX_STATUS_DURATION_SEC;
    }

    private void clearCelestialFxStatus() {
        celestialFxStatusText = "";
        celestialFxStatusTimer = 0f;
    }

    public OrbitRenderMode getOrbitRenderMode() {
        return orbitPredictor.getOrbitRenderMode();
    }

    public void setSimRenderer(SimRenderer renderer) {
        this.simRenderer = renderer;
    }

    public void setMeasureTool(MeasureTool tool) {
        this.measureTool = tool;
    }

    public void setDimensionToggle(Runnable toggle) {
        this.dimensionToggle = toggle;
    }

    public void setCameraReset(Runnable reset) {
        this.cameraReset = reset;
    }

    public MeasureTool getMeasureTool() {
        return measureTool;
    }
}
