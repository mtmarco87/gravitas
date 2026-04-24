package com.gravitas.ui.settings;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.CameraMode;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.settings.AppSettings;
import com.gravitas.settings.CameraSettings;
import com.gravitas.settings.FxSettings;
import com.gravitas.settings.OverlaySettings;
import com.gravitas.settings.PhysicsSettings;
import com.gravitas.settings.SimulationSettings;
import com.gravitas.settings.enums.SpinMode;
import com.gravitas.state.AppState;
import com.gravitas.state.CameraState;
import com.gravitas.state.SettingsMenuPage;
import com.gravitas.state.SettingsPanelState;
import com.gravitas.state.SimulationState;
import com.gravitas.ui.GravitasInputProcessor;
import com.gravitas.ui.MeasureTool;

import java.util.Objects;

public final class SettingsPanelController {

    private static final float SETTINGS_PANEL_PAD_PX = 10f;
    private static final float SETTINGS_PANEL_LINE_HEIGHT_PX = 18f;
    private static final float SETTINGS_STATUS_DURATION_SEC = 1.5f;
    private static final float SETTINGS_EDIT_REPEAT_DELAY_SEC = 0.35f;
    private static final float SETTINGS_EDIT_REPEAT_INTERVAL_SEC = 0.05f;

    private final WorldCamera camera;
    private final PhysicsEngine physics;
    private final MeasureTool measureTool;
    private final AppSettings settings;
    private final CameraSettings cameraSettings;
    private final CameraState cameraState;
    private final SimulationState simulationState;
    private final SettingsPanelState settingsPanelState;
    private final SettingsPanelModel settingsPanelModel;
    private final OverlaySettings overlaySettings;
    private final SimulationSettings simulationSettings;
    private final PhysicsSettings physicsSettings;
    private final FxSettings fxSettings;
    private final GravitasInputProcessor.Actions actions;
    private final Runnable clearPendingTapState;
    private final Runnable resetPointerDragging;

    private int settingsEditRepeatDirection = 0;
    private float settingsEditRepeatTimer = 0f;
    private boolean settingsEditRepeatDelayed = false;

    public SettingsPanelController(WorldCamera camera, PhysicsEngine physics, MeasureTool measureTool,
            AppSettings settings, AppState appState, SettingsPanelModel settingsPanelModel,
            GravitasInputProcessor.Actions actions,
            Runnable clearPendingTapState, Runnable resetPointerDragging) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.physics = Objects.requireNonNull(physics, "physics");
        this.measureTool = Objects.requireNonNull(measureTool, "measureTool");
        this.settings = Objects.requireNonNull(settings, "settings");
        AppState runtimeState = Objects.requireNonNull(appState, "appState");
        this.cameraState = runtimeState.getCamera();
        this.simulationState = runtimeState.getSimulation();
        this.settingsPanelState = runtimeState.getUi().getSettingsPanel();
        this.settingsPanelModel = Objects.requireNonNull(settingsPanelModel, "settingsPanelModel");
        this.cameraSettings = settings.getCameraSettings();
        this.overlaySettings = settings.getOverlaySettings();
        this.simulationSettings = settings.getSimulationSettings();
        this.physicsSettings = settings.getPhysicsSettings();
        this.fxSettings = settings.getFxSettings();
        this.actions = Objects.requireNonNull(actions, "actions");
        this.clearPendingTapState = Objects.requireNonNull(clearPendingTapState, "clearPendingTapState");
        this.resetPointerDragging = Objects.requireNonNull(resetPointerDragging, "resetPointerDragging");
    }

    public boolean isOpen() {
        return settingsPanelState.isOpen();
    }

    public void toggleMenu() {
        settingsPanelState.setOpen(!settingsPanelState.isOpen());
        settingsPanelState.setDragging(false);
        if (settingsPanelState.isOpen()) {
            normalizeSettingsSelection();
            clearSettingsStatus();
            exitSettingsEditMode();
            clearPendingTapState.run();
            camera.onPanEnd();
            resetPointerDragging.run();
        }
    }

    public boolean handleKeyDown(int keycode) {
        if (settingsPanelState.isEditMode()) {
            return handleEditKeyDown(keycode);
        }

        switch (keycode) {
            case Input.Keys.Q -> Gdx.app.exit();
            case Input.Keys.X -> settingsPanelState.setOpen(false);
            case Input.Keys.ESCAPE, Input.Keys.LEFT -> backOutOfSettingsMenu();
            case Input.Keys.UP -> moveSettingsSelection(-1);
            case Input.Keys.DOWN -> moveSettingsSelection(1);
            case Input.Keys.NUM_1 -> activateMainQuickSelection(0);
            case Input.Keys.NUM_2 -> activateMainQuickSelection(1);
            case Input.Keys.NUM_3 -> activateMainQuickSelection(2);
            case Input.Keys.NUM_4 -> activateMainQuickSelection(3);
            case Input.Keys.NUM_5 -> activateMainQuickSelection(4);
            case Input.Keys.NUM_6 -> activateMainQuickSelection(5);
            case Input.Keys.COMMA -> cycleSimulationWarpPreset(-1);
            case Input.Keys.PERIOD -> cycleSimulationWarpPreset(1);
            case Input.Keys.ENTER, Input.Keys.SPACE, Input.Keys.RIGHT -> activateSelection();
            default -> {
                return true;
            }
        }
        return true;
    }

    public boolean handleKeyTyped(char character) {
        if (!isCurrentSelectionNumericEditable()) {
            return false;
        }

        if (settingsPanelState.getPage() == SettingsMenuPage.SIMULATION
                && settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0) {
            if (character == '.' || character == ',') {
                return true;
            }
            if (character < '0' || character > '9') {
                return false;
            }
        }

        if ((character >= '0' && character <= '9') || character == '.' || character == ',') {
            if (!settingsPanelState.isEditMode()) {
                toggleSettingsEditMode();
            }
            appendSettingsInputChar(character == ',' ? '.' : character);
            return true;
        }
        return false;
    }

    public void update(float dt) {
        if (settingsPanelState.getStatusTimer() > 0f) {
            settingsPanelState.setStatusTimer(Math.max(0f, settingsPanelState.getStatusTimer() - dt));
            if (settingsPanelState.getStatusTimer() == 0f) {
                settingsPanelState.clearStatus();
            }
        }

        if (!settingsPanelState.isEditMode() || !isCurrentSelectionNumericEditable()) {
            settingsEditRepeatDirection = 0;
            settingsEditRepeatTimer = 0f;
            settingsEditRepeatDelayed = false;
            return;
        }

        int direction = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
            direction = 1;
        } else if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            direction = -1;
        }

        if (direction == 0) {
            settingsEditRepeatDirection = 0;
            settingsEditRepeatTimer = 0f;
            settingsEditRepeatDelayed = false;
            return;
        }

        if (settingsEditRepeatDirection != direction) {
            settingsEditRepeatDirection = direction;
            settingsEditRepeatTimer = 0f;
            settingsEditRepeatDelayed = false;
            return;
        }

        settingsEditRepeatTimer += dt;
        if (!settingsEditRepeatDelayed) {
            if (settingsEditRepeatTimer >= SETTINGS_EDIT_REPEAT_DELAY_SEC) {
                settingsEditRepeatTimer -= SETTINGS_EDIT_REPEAT_DELAY_SEC;
                settingsEditRepeatDelayed = true;
                settingsPanelState.setManualInputBuffer("");
                adjustSettingsValue(direction * FxSettings.VALUE_STEP);
            }
            return;
        }

        while (settingsEditRepeatTimer >= SETTINGS_EDIT_REPEAT_INTERVAL_SEC) {
            settingsEditRepeatTimer -= SETTINGS_EDIT_REPEAT_INTERVAL_SEC;
            settingsPanelState.setManualInputBuffer("");
            adjustSettingsValue(direction * FxSettings.VALUE_STEP);
        }
    }

    public boolean handleTouchDown(int screenX, int screenY, int button) {
        clearPendingTapState.run();
        resetPointerDragging.run();
        settingsPanelState.setPressedInside(false);
        if (button == Input.Buttons.LEFT) {
            float uiY = Gdx.graphics.getHeight() - screenY;
            if (isInsideSettingsPanel(screenX, uiY)) {
                settingsPanelState.setPressedInside(true);
                settingsPanelState.setPressX(screenX);
                settingsPanelState.setPressY(uiY);
                settingsPanelState.setDragging(false);
                settingsPanelState.setDragOffsetX(screenX - settingsPanelState.getX());
                settingsPanelState.setDragOffsetY(uiY - settingsPanelState.getY());
            } else {
                settingsPanelState.setOpen(false);
                settingsPanelState.setDragging(false);
                settingsPanelState.setPressedInside(false);
                exitSettingsEditMode();
            }
        }
        return true;
    }

    public boolean handleTouchDragged(int screenX, int screenY) {
        float uiY = Gdx.graphics.getHeight() - screenY;
        if (settingsPanelState.isPressedInside() && !settingsPanelState.isDragging()) {
            float dx = screenX - settingsPanelState.getPressX();
            float dy = uiY - settingsPanelState.getPressY();
            if (dx * dx + dy * dy > 16f) {
                settingsPanelState.setDragging(true);
            }
        }
        if (settingsPanelState.isDragging()) {
            settingsPanelState.setPositioned(true);
            settingsPanelState.setX(screenX - settingsPanelState.getDragOffsetX());
            settingsPanelState.setY(uiY - settingsPanelState.getDragOffsetY());
            settingsPanelState.clampPosition(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(),
                    settingsPanelState.getWidth(), settingsPanelState.getHeight());
        }
        return true;
    }

    public boolean handleTouchUp(int screenX, int screenY, int button) {
        resetPointerDragging.run();
        if (button == Input.Buttons.LEFT) {
            float uiY = Gdx.graphics.getHeight() - screenY;
            if (settingsPanelState.isPressedInside() && !settingsPanelState.isDragging()
                    && isInsideSettingsPanel(screenX, uiY)) {
                handleSettingsPanelClick(uiY);
            }
            settingsPanelState.setDragging(false);
            settingsPanelState.setPressedInside(false);
        }
        return true;
    }

    private boolean handleEditKeyDown(int keycode) {
        switch (keycode) {
            case Input.Keys.Q -> Gdx.app.exit();
            case Input.Keys.X -> settingsPanelState.setOpen(false);
            case Input.Keys.ESCAPE, Input.Keys.LEFT -> cancelSettingsEditMode();
            case Input.Keys.ENTER, Input.Keys.SPACE, Input.Keys.RIGHT -> commitSettingsEditMode();
            case Input.Keys.UP -> nudgeSettingsValue(1);
            case Input.Keys.DOWN -> nudgeSettingsValue(-1);
            case Input.Keys.BACKSPACE -> handleSettingsBackspace();
            default -> {
                return true;
            }
        }
        return true;
    }

    private void activateMainQuickSelection(int selection) {
        if (settingsPanelState.getPage() != SettingsMenuPage.MAIN) {
            return;
        }
        settingsPanelState.setSelection(SettingsMenuPage.MAIN, selection);
        activateSelection();
    }

    private void backOutOfSettingsMenu() {
        if (settingsPanelState.getPage() == SettingsMenuPage.MAIN) {
            settingsPanelState.setOpen(false);
            return;
        }

        resetSettingsSelection(settingsPanelState.getPage());
        settingsPanelState.setPage(switch (settingsPanelState.getPage()) {
            case OVERLAYS, SIMULATION, PHYSICS, CAMERA, FX -> SettingsMenuPage.MAIN;
            case CLOUD, LIGHTING, ATMOSPHERE -> SettingsMenuPage.FX;
            case MAIN -> SettingsMenuPage.MAIN;
        });
        exitSettingsEditMode();
    }

    private void moveSettingsSelection(int delta) {
        int count = settingsPanelModel.getOptionCount();
        settingsPanelState.setCurrentSelection(wrapSelection(settingsPanelState.getCurrentSelection() + delta, count));
    }

    private int wrapSelection(int index, int count) {
        return (index + count) % count;
    }

    private void activateSelection() {
        switch (settingsPanelState.getPage()) {
            case MAIN -> activateMainSelection();
            case SIMULATION -> activateSimulationSelection();
            case CAMERA -> activateCameraSelection();
            case FX -> activateFxSelection();
            case PHYSICS -> activatePhysicsSelection();
            case OVERLAYS -> activateOverlaySelection();
            case CLOUD -> activateCloudSelection();
            case LIGHTING -> activateLightingSelection();
            case ATMOSPHERE -> activateAtmosphereSelection();
        }
    }

    private void activateMainSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.MAIN)) {
            case 0 -> openSettingsPage(SettingsMenuPage.OVERLAYS);
            case 1 -> openSettingsPage(SettingsMenuPage.SIMULATION);
            case 2 -> openSettingsPage(SettingsMenuPage.PHYSICS);
            case 3 -> openSettingsPage(SettingsMenuPage.CAMERA);
            case 4 -> openSettingsPage(SettingsMenuPage.FX);
            case 5 -> {
                settings.resetToDefaults();
                applySimulationWarp(simulationSettings.getTimeWarp(), true);
                applyPhysicsSettings();
                showSettingsStatus("Defaults restored");
            }
            default -> {
            }
        }
    }

    private void openSettingsPage(SettingsMenuPage page) {
        settingsPanelState.setPage(page);
        exitSettingsEditMode();
    }

    private void activateSimulationSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.SIMULATION)) {
            case 0 -> toggleSettingsEditMode();
            case 1 -> {
                actions.toggleOrbitsDimensions().run();
                showSettingsStatus("Orbits Dimensions -> "
                        + (simulationSettings.isOrbitsDimensions3D() ? "FULL 3D" : "FLAT 2D"));
            }
            case 2 -> {
                simulationSettings.toggleAdvancedTooltipDataEnabled();
                showSettingsStatus("Advanced Tooltip Data -> "
                        + (simulationSettings.isAdvancedTooltipDataEnabled() ? "ON" : "OFF"));
            }
            case 3 -> showSettingsStatus("Load State TODO");
            case 4 -> showSettingsStatus("Save State TODO");
            case 5 -> resetSimulationState();
            case 6 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void resetSimulationState() {
        clearPendingTapState.run();
        exitSettingsEditMode();
        if (measureTool.isActive()) {
            measureTool.cancel();
        }
        actions.simulationReset().run();
        applySimulationWarp(simulationSettings.getTimeWarp(), true);
        showSettingsStatus("State reset");
    }

    private void activateFxSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.FX)) {
            case 0 -> {
                fxSettings.toggleMasterEnabled();
                showSettingsStatus("FX " + (fxSettings.isMasterEnabled() ? "ON" : "OFF"));
            }
            case 1 -> openSettingsPage(SettingsMenuPage.LIGHTING);
            case 2 -> openSettingsPage(SettingsMenuPage.CLOUD);
            case 3 -> openSettingsPage(SettingsMenuPage.ATMOSPHERE);
            case 4 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void activateCameraSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.CAMERA)) {
            case 0 -> {
                CameraMode targetMode = cameraSettings.getCameraMode() == CameraMode.TOP_VIEW
                        ? CameraMode.FREE_CAM
                        : CameraMode.TOP_VIEW;
                toggleCameraMode();
                showSettingsStatus("Camera -> " + (targetMode == CameraMode.FREE_CAM ? "3D" : "2D"));
            }
            case 1 -> {
                cycleFreeCamFovMode();
                showSettingsStatus("FOV -> " + currentCameraFovLabel());
            }
            case 2 -> {
                cycleFollowFrameMode();
                showSettingsStatus("Follow -> " + cameraSettings.getFollowFrameMode().hudLabel());
            }
            case 3 -> {
                clearFollowTarget();
                showSettingsStatus("Follow cleared");
            }
            case 4 -> {
                actions.cameraReset().run();
                showSettingsStatus("Camera reset");
            }
            case 5 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void activatePhysicsSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.PHYSICS)) {
            case 0 -> {
                physicsSettings.cycleSpinMode();
                applyPhysicsSettings();
                normalizeSettingsSelection();
            }
            case 1 -> {
                if (physicsSettings.getSpinMode() == SpinMode.DYNAMIC) {
                    physicsSettings.cycleDynamicSystemsMode();
                    applyPhysicsSettings();
                } else {
                    backOutOfSettingsMenu();
                }
            }
            case 2 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void activateOverlaySelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.OVERLAYS)) {
            case 0 -> overlaySettings.cycleBodyVectorOverlayMode();
            case 1 -> overlaySettings.cycleOrbitOverlayMode();
            case 2 -> overlaySettings.cycleOrbitRenderMode();
            case 3 -> overlaySettings.cycleOrbitPredictorScope();
            case 4 -> overlaySettings.toggleVisualScaleMode();
            case 5 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void activateCloudSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.CLOUD)) {
            case 0 -> fxSettings.cycleCloudFxMode();
            case 1 -> fxSettings.toggleCloudDayNightEnabled();
            case 2 -> fxSettings.cycleCloudTerminatorMode();
            case 3 -> fxSettings.cycleCloudCompositingMode();
            case 4, 5, 6 -> toggleSettingsEditMode();
            case 7 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void activateLightingSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.LIGHTING)) {
            case 0 -> fxSettings.toggleDayNightEnabled();
            case 1 -> fxSettings.toggleRingShadowEnabled();
            case 2 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void activateAtmosphereSelection() {
        switch (settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE)) {
            case 0 -> fxSettings.toggleAtmosphereDayNightEnabled();
            case 1, 2, 3, 4 -> toggleSettingsEditMode();
            case 5 -> backOutOfSettingsMenu();
            default -> {
            }
        }
    }

    private void toggleSettingsEditMode() {
        if (settingsPanelState.isEditMode()) {
            commitSettingsEditMode();
            return;
        }
        settingsPanelState.setEditStartValue(currentSettingsEditableValue());
        settingsPanelState.setEditMode(true);
        settingsEditRepeatDirection = 0;
        settingsEditRepeatTimer = 0f;
        settingsEditRepeatDelayed = false;
        settingsPanelState.setManualInputBuffer("");
    }

    private void exitSettingsEditMode() {
        settingsPanelState.setEditMode(false);
        settingsEditRepeatDirection = 0;
        settingsEditRepeatTimer = 0f;
        settingsEditRepeatDelayed = false;
        settingsPanelState.setManualInputBuffer("");
    }

    private void commitSettingsEditMode() {
        if (!settingsPanelState.getManualInputBuffer().isEmpty()) {
            applySettingsAbsoluteValue(settingsPanelState.getManualInputBuffer());
        }
        exitSettingsEditMode();
    }

    private void cancelSettingsEditMode() {
        if (isCurrentSelectionNumericEditable()) {
            applySettingsStoredValue(settingsPanelState.getEditStartValue());
        }
        exitSettingsEditMode();
    }

    private void handleSettingsBackspace() {
        if (settingsPanelState.getManualInputBuffer().isEmpty()) {
            return;
        }
        String buffer = settingsPanelState.getManualInputBuffer();
        settingsPanelState.setManualInputBuffer(buffer.substring(0, buffer.length() - 1));
    }

    private void nudgeSettingsValue(int direction) {
        settingsPanelState.setManualInputBuffer("");
        adjustSettingsValue(direction * FxSettings.VALUE_STEP);
        settingsEditRepeatDirection = direction;
        settingsEditRepeatTimer = 0f;
        settingsEditRepeatDelayed = false;
    }

    private void adjustSettingsValue(float delta) {
        switch (settingsPanelState.getPage()) {
            case SIMULATION -> {
            }
            case CLOUD -> {
                switch (settingsPanelState.getSelection(SettingsMenuPage.CLOUD)) {
                    case 4 -> fxSettings.addCloudProceduralTextureCoupling(delta);
                    case 5 -> fxSettings.addCloudTextureAlphaWeight(delta);
                    case 6 -> fxSettings.addCloudProceduralAlphaWeight(delta);
                    default -> {
                    }
                }
            }
            case ATMOSPHERE -> {
                switch (settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE)) {
                    case 1 -> fxSettings.addAtmosphereNightOuterFloor(delta);
                    case 2 -> fxSettings.addAtmosphereNightInnerFloor(delta);
                    case 3 -> fxSettings.addAtmosphereDenseNightOuterFloor(delta);
                    case 4 -> fxSettings.addAtmosphereDenseNightInnerFloor(delta);
                    default -> {
                    }
                }
            }
            case MAIN, FX, PHYSICS, CAMERA, OVERLAYS, LIGHTING -> {
            }
        }
    }

    private void applySettingsAbsoluteValue(String rawValue) {
        if (!isCurrentSelectionNumericEditable()) {
            return;
        }

        try {
            double value = Double.parseDouble(rawValue);
            switch (settingsPanelState.getPage()) {
                case SIMULATION -> {
                    if (settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0) {
                        applySimulationWarp(value, true);
                    }
                }
                case CLOUD -> {
                    switch (settingsPanelState.getSelection(SettingsMenuPage.CLOUD)) {
                        case 4 -> fxSettings.setCloudProceduralTextureCoupling((float) value);
                        case 5 -> fxSettings.setCloudTextureAlphaWeight((float) value);
                        case 6 -> fxSettings.setCloudProceduralAlphaWeight((float) value);
                        default -> {
                        }
                    }
                }
                case ATMOSPHERE -> {
                    switch (settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE)) {
                        case 1 -> fxSettings.setAtmosphereNightOuterFloor((float) value);
                        case 2 -> fxSettings.setAtmosphereNightInnerFloor((float) value);
                        case 3 -> fxSettings.setAtmosphereDenseNightOuterFloor((float) value);
                        case 4 -> fxSettings.setAtmosphereDenseNightInnerFloor((float) value);
                        default -> {
                        }
                    }
                }
                case MAIN, FX, PHYSICS, CAMERA, OVERLAYS, LIGHTING -> {
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applySettingsStoredValue(double value) {
        switch (settingsPanelState.getPage()) {
            case SIMULATION -> {
                if (settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0) {
                    applySimulationWarp(value, true);
                }
            }
            case CLOUD -> {
                switch (settingsPanelState.getSelection(SettingsMenuPage.CLOUD)) {
                    case 4 -> fxSettings.setCloudProceduralTextureCoupling((float) value);
                    case 5 -> fxSettings.setCloudTextureAlphaWeight((float) value);
                    case 6 -> fxSettings.setCloudProceduralAlphaWeight((float) value);
                    default -> {
                    }
                }
            }
            case ATMOSPHERE -> {
                switch (settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE)) {
                    case 1 -> fxSettings.setAtmosphereNightOuterFloor((float) value);
                    case 2 -> fxSettings.setAtmosphereNightInnerFloor((float) value);
                    case 3 -> fxSettings.setAtmosphereDenseNightOuterFloor((float) value);
                    case 4 -> fxSettings.setAtmosphereDenseNightInnerFloor((float) value);
                    default -> {
                    }
                }
            }
            case MAIN, FX, PHYSICS, CAMERA, OVERLAYS, LIGHTING -> {
            }
        }
    }

    private double currentSettingsEditableValue() {
        return switch (settingsPanelState.getPage()) {
            case SIMULATION -> settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0
                    ? simulationSettings.getTimeWarp()
                    : 0.0;
            case CLOUD -> switch (settingsPanelState.getSelection(SettingsMenuPage.CLOUD)) {
                case 4 -> fxSettings.getCloudProceduralTextureCoupling();
                case 5 -> fxSettings.getCloudTextureAlphaWeight();
                case 6 -> fxSettings.getCloudProceduralAlphaWeight();
                default -> 0.0;
            };
            case ATMOSPHERE -> switch (settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE)) {
                case 1 -> fxSettings.getAtmosphereNightOuterFloor();
                case 2 -> fxSettings.getAtmosphereNightInnerFloor();
                case 3 -> fxSettings.getAtmosphereDenseNightOuterFloor();
                case 4 -> fxSettings.getAtmosphereDenseNightInnerFloor();
                default -> 0.0;
            };
            case MAIN, FX, PHYSICS, CAMERA, OVERLAYS, LIGHTING -> 0.0;
        };
    }

    private void appendSettingsInputChar(char character) {
        String buffer = settingsPanelState.getManualInputBuffer();
        if (character == '.' && buffer.contains(".")) {
            return;
        }
        if (character == '.' && buffer.isEmpty()) {
            settingsPanelState.setManualInputBuffer("0.");
            return;
        }
        settingsPanelState.setManualInputBuffer(buffer + character);
    }

    private boolean isCurrentSelectionNumericEditable() {
        return switch (settingsPanelState.getPage()) {
            case SIMULATION -> settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0;
            case CLOUD -> {
                int selection = settingsPanelState.getSelection(SettingsMenuPage.CLOUD);
                yield selection >= 4 && selection <= 6;
            }
            case LIGHTING -> false;
            case ATMOSPHERE -> {
                int selection = settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE);
                yield selection >= 1 && selection <= 4;
            }
            case MAIN, FX, PHYSICS, CAMERA, OVERLAYS -> false;
        };
    }

    private String currentCameraFovLabel() {
        return cameraSettings.isAdaptiveFreeCamFovEnabled()
                ? "AUTO " + Math.round(cameraState.getCurrentFreeCamFov()) + "°"
                : "FIX " + fixedFreeCamFovLabel() + "°";
    }

    private int fixedFreeCamFovLabel() {
        return cameraSettings.getFixedFreeCamFovPresetIndex() == 0 ? 5 : 60;
    }

    private void normalizeSettingsSelection() {
        SettingsMenuPage currentPage = settingsPanelState.getPage();
        settingsPanelState.setSelection(currentPage,
                clampSelection(settingsPanelState.getSelection(currentPage), settingsPanelModel.getOptionCount()));
    }

    private int clampSelection(int selection, int count) {
        return Math.max(0, Math.min(selection, count - 1));
    }

    private void resetSettingsSelection(SettingsMenuPage page) {
        if (page != SettingsMenuPage.MAIN) {
            settingsPanelState.resetSelection(page);
        }
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

    private void cycleSimulationWarpPreset(int direction) {
        if (settingsPanelState.getPage() != SettingsMenuPage.SIMULATION
                || settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) != 0
                || settingsPanelState.isEditMode()) {
            return;
        }
        int currentIndex = WarpPresets.nearestIndex(simulationSettings.getTimeWarp());
        int nextIndex = Math.max(0, Math.min(currentIndex + direction, WarpPresets.size() - 1));
        if (nextIndex == currentIndex) {
            return;
        }
        applySimulationWarp(WarpPresets.get(nextIndex), true);
        showSettingsStatus(
                "Time Warp -> " + WarpPresets.formatDisplayLabel(simulationSettings.getTimeWarp()));
    }

    private void applySimulationWarp(double warp, boolean preservePause) {
        simulationSettings.setTimeWarp(warp);
        if (preservePause && simulationState.isPaused()) {
            physics.setTimeWarpFactor(0);
            return;
        }
        physics.setTimeWarpFactor(simulationSettings.getTimeWarp());
        simulationState.setPaused(false);
    }

    private void applyPhysicsSettings() {
        physics.applyPhysicsSettings(physicsSettings);
    }

    private void handleSettingsPanelClick(float y) {
        if (settingsPanelState.isEditMode()) {
            return;
        }
        int optionIndex = settingsOptionIndexAt(y);
        if (optionIndex < 0) {
            return;
        }
        settingsPanelState.setCurrentSelection(optionIndex);
        activateSelection();
    }

    private int settingsOptionIndexAt(float y) {
        float optionsTopY = settingsPanelState.getY() + settingsPanelState.getHeight()
                - SETTINGS_PANEL_PAD_PX
                - SETTINGS_PANEL_LINE_HEIGHT_PX * 2.45f;
        int optionCount = settingsPanelModel.getOptionCount();
        for (int i = 0; i < optionCount; i++) {
            float rowBottom = optionsTopY - i * SETTINGS_PANEL_LINE_HEIGHT_PX - SETTINGS_PANEL_LINE_HEIGHT_PX * 0.85f;
            float rowTop = rowBottom + SETTINGS_PANEL_LINE_HEIGHT_PX * 1.05f;
            if (y >= rowBottom && y <= rowTop) {
                return i;
            }
        }
        return -1;
    }

    private boolean isInsideSettingsPanel(float x, float y) {
        return settingsPanelState.contains(x, y);
    }

    private void showSettingsStatus(String text) {
        settingsPanelState.setStatus(text, SETTINGS_STATUS_DURATION_SEC);
    }

    private void clearSettingsStatus() {
        settingsPanelState.clearStatus();
    }
}
