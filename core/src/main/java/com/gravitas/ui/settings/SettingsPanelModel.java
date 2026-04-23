package com.gravitas.ui.settings;

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
import com.gravitas.state.UiState;

import java.util.Locale;

public final class SettingsPanelModel {

    private static final int SETTINGS_MAIN_OPTION_COUNT = 6;
    private static final int SETTINGS_SIMULATION_OPTION_COUNT = 7;
    private static final int SETTINGS_CAMERA_OPTION_COUNT = 6;
    private static final int SETTINGS_FX_OPTION_COUNT = 5;
    private static final int SETTINGS_PHYSICS_BASE_OPTION_COUNT = 2;
    private static final int SETTINGS_PHYSICS_FULL_OPTION_COUNT = 3;
    private static final int SETTINGS_OVERLAY_OPTION_COUNT = 5;
    private static final int SETTINGS_CLOUD_OPTION_COUNT = 8;
    private static final int SETTINGS_LIGHTING_OPTION_COUNT = 3;
    private static final int SETTINGS_ATMOSPHERE_OPTION_COUNT = 6;

    private final SettingsPanelState settingsPanelState;
    private final CameraState cameraState;
    private final SimulationState simulationState;
    private final CameraSettings cameraSettings;
    private final OverlaySettings overlaySettings;
    private final SimulationSettings simulationSettings;
    private final PhysicsSettings physicsSettings;
    private final FxSettings fxSettings;

    public SettingsPanelModel(AppSettings settings, AppState appState) {
        UiState uiState = appState.getUi();
        this.settingsPanelState = uiState.getSettingsPanel();
        this.cameraState = appState.getCamera();
        this.simulationState = appState.getSimulation();
        this.cameraSettings = settings.getCameraSettings();
        this.overlaySettings = settings.getOverlaySettings();
        this.simulationSettings = settings.getSimulationSettings();
        this.physicsSettings = settings.getPhysicsSettings();
        this.fxSettings = settings.getFxSettings();
    }

    public String getTitle() {
        return settingsPanelState.getPage().title();
    }

    public String getHint() {
        if (settingsPanelState.isEditMode()) {
            if (settingsPanelState.getPage() == SettingsMenuPage.SIMULATION
                    && settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0) {
                return "Type value, Enter apply, Esc/Left cancel";
            }
            return "Type value, Up/Down nudge, Enter apply, Esc/Left cancel";
        }
        return switch (settingsPanelState.getPage()) {
            case MAIN -> "Up/Down select, Enter open, 1-6 quick open, X/Esc close";
            case SIMULATION -> "Up/Down select, Enter edit/open, ,/. preset, Left back, X/Esc close";
            case FX, PHYSICS, CAMERA, OVERLAYS, CLOUD, LIGHTING, ATMOSPHERE ->
                "Up/Down select, Enter change, Left back, X/Esc close";
        };
    }

    public int getOptionCount() {
        return switch (settingsPanelState.getPage()) {
            case MAIN -> SETTINGS_MAIN_OPTION_COUNT;
            case SIMULATION -> SETTINGS_SIMULATION_OPTION_COUNT;
            case CAMERA -> SETTINGS_CAMERA_OPTION_COUNT;
            case FX -> SETTINGS_FX_OPTION_COUNT;
            case PHYSICS -> getPhysicsOptionCount();
            case OVERLAYS -> SETTINGS_OVERLAY_OPTION_COUNT;
            case CLOUD -> SETTINGS_CLOUD_OPTION_COUNT;
            case LIGHTING -> SETTINGS_LIGHTING_OPTION_COUNT;
            case ATMOSPHERE -> SETTINGS_ATMOSPHERE_OPTION_COUNT;
        };
    }

    public String getOptionLine(int index) {
        return switch (settingsPanelState.getPage()) {
            case MAIN -> mainSettingsOptionLine(index);
            case SIMULATION -> simulationOptionLine(index);
            case CAMERA -> cameraOptionLine(index);
            case FX -> fxOptionLine(index);
            case PHYSICS -> physicsOptionLine(index);
            case OVERLAYS -> overlayOptionLine(index);
            case CLOUD -> cloudSettingsOptionLine(index);
            case LIGHTING -> lightingSettingsOptionLine(index);
            case ATMOSPHERE -> atmosphereSettingsOptionLine(index);
        };
    }

    private String mainSettingsOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Overlays >";
            case 1 -> "2. Simulation >";
            case 2 -> "3. Physics >";
            case 3 -> "4. Camera >";
            case 4 -> "5. FX >";
            case 5 -> "6. Restore Defaults";
            default -> "?";
        };
    }

    private String simulationOptionLine(int index) {
        String edit = editSuffix(index, settingsPanelState.getSelection(SettingsMenuPage.SIMULATION));
        return switch (index) {
            case 0 -> "1. Time Warp [" + currentWarpMenuLabel() + "]" + edit;
            case 1 -> "2. Orbits Dimensions [" + orbitsDimensionsLabel() + "]";
            case 2 ->
                "3. Advanced Tooltip Data [" + onOffLabel(simulationSettings.isAdvancedTooltipDataEnabled()) + "]";
            case 3 -> "4. Load State [TODO]";
            case 4 -> "5. Save State [TODO]";
            case 5 -> "6. Reset State";
            case 6 -> "7. < Back";
            default -> "?";
        };
    }

    private String fxOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Global FX [" + (fxSettings.isMasterEnabled() ? "ON" : "OFF") + "]";
            case 1 -> "2. Lighting >";
            case 2 -> "3. Clouds >";
            case 3 -> "4. Atmosphere >";
            case 4 -> "5. < Back";
            default -> "?";
        };
    }

    private String cameraOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Camera [" + cameraModeLabel() + "]";
            case 1 -> "2. FOV [" + currentFovLabel() + "]";
            case 2 -> "3. Follow Mode [" + cameraSettings.getFollowFrameMode().hudLabel() + "]";
            case 3 -> "4. Clear Follow [" + followTargetLabel() + "]";
            case 4 -> "5. Reset Camera";
            case 5 -> "6. < Back";
            default -> "?";
        };
    }

    private String physicsOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Spin Mode [" + physicsSettings.getSpinMode().hudLabel() + "]";
            case 1 -> physicsSettings.getSpinMode() == SpinMode.DYNAMIC
                    ? "2. Dynamic Systems [" + physicsSettings.getDynamicSystemsMode().hudLabel() + "]"
                    : "2. < Back";
            case 2 -> "3. < Back";
            default -> "?";
        };
    }

    private String overlayOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Orbit Vectors [" + overlaySettings.getOrientationOverlayMode().hudLabel() + "]";
            case 1 -> "2. Orbit Overlays [" + overlaySettings.getOrbitOverlayMode().hudLabel() + "]";
            case 2 -> "3. Orbit Style [" + overlaySettings.getOrbitRenderMode().hudLabel() + "]";
            case 3 -> "4. Visual Scale [" + (overlaySettings.isVisualScaleMode() ? "ON" : "OFF") + "]";
            case 4 -> "5. < Back";
            default -> "?";
        };
    }

    private int getPhysicsOptionCount() {
        return physicsSettings.getSpinMode() == SpinMode.DYNAMIC
                ? SETTINGS_PHYSICS_FULL_OPTION_COUNT
                : SETTINGS_PHYSICS_BASE_OPTION_COUNT;
    }

    private String cloudSettingsOptionLine(int index) {
        String edit = editSuffix(index, settingsPanelState.getSelection(SettingsMenuPage.CLOUD));
        return switch (index) {
            case 0 -> "1. Mode [" + fxSettings.getCloudFxMode().hudLabel() + "]";
            case 1 -> "2. Day/Night [" + subordinateDayNightLabel(fxSettings.isCloudDayNightEnabled()) + "]";
            case 2 -> "3. Terminator [" + fxSettings.getCloudTerminatorMode().hudLabel() + "]";
            case 3 -> "4. Compositing [" + fxSettings.getCloudCompositingMode().hudLabel() + "]";
            case 4 -> "5. Coupling [" + formatFxValue(fxSettings.getCloudProceduralTextureCoupling()) + "]" + edit;
            case 5 -> "6. Texture Alpha [" + formatFxValue(fxSettings.getCloudTextureAlphaWeight()) + "]" + edit;
            case 6 -> "7. Procedural Alpha [" + formatFxValue(fxSettings.getCloudProceduralAlphaWeight()) + "]" + edit;
            case 7 -> "8. < Back";
            default -> "?";
        };
    }

    private String lightingSettingsOptionLine(int index) {
        return switch (index) {
            case 0 -> "1. Global Day/Night [" + lightingDayNightLabel() + "]";
            case 1 -> "2. Ring Shadows [" + (fxSettings.isRingShadowEnabled() ? "ON" : "OFF") + "]";
            case 2 -> "3. < Back";
            default -> "?";
        };
    }

    private String atmosphereSettingsOptionLine(int index) {
        String edit = editSuffix(index, settingsPanelState.getSelection(SettingsMenuPage.ATMOSPHERE));
        return switch (index) {
            case 0 -> "1. Day/Night [" + subordinateDayNightLabel(fxSettings.isAtmosphereDayNightEnabled()) + "]";
            case 1 -> "2. Thin Night Outer [" + formatFxValue(fxSettings.getAtmosphereNightOuterFloor()) + "]" + edit;
            case 2 -> "3. Thin Night Inner [" + formatFxValue(fxSettings.getAtmosphereNightInnerFloor()) + "]" + edit;
            case 3 ->
                "4. Dense Night Outer [" + formatFxValue(fxSettings.getAtmosphereDenseNightOuterFloor()) + "]" + edit;
            case 4 ->
                "5. Dense Night Inner [" + formatFxValue(fxSettings.getAtmosphereDenseNightInnerFloor()) + "]" + edit;
            case 5 -> "6. < Back";
            default -> "?";
        };
    }

    private String subordinateDayNightLabel(boolean localEnabled) {
        String localLabel = localEnabled ? "ON" : "OFF";
        if (!fxSettings.isMasterEnabled()) {
            return localLabel + ", master OFF";
        }
        if (fxSettings.isDayNightEnabled()) {
            return localLabel;
        }
        return localLabel + ", global OFF";
    }

    private String lightingDayNightLabel() {
        String localLabel = fxSettings.isDayNightEnabled() ? "ON" : "OFF";
        if (!fxSettings.isMasterEnabled()) {
            return localLabel + ", master OFF";
        }
        return localLabel;
    }

    private String onOffLabel(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private String formatFxValue(float value) {
        return String.format("%.2f", value);
    }

    private String formatSettingsValue(double value) {
        if (settingsPanelState.getPage() == SettingsMenuPage.SIMULATION
                && settingsPanelState.getSelection(SettingsMenuPage.SIMULATION) == 0) {
            return currentWarpMenuLabel();
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private String editSuffix(int index, int selection) {
        if (!settingsPanelState.isEditMode() || selection != index) {
            return "";
        }
        if (!settingsPanelState.getManualInputBuffer().isEmpty()) {
            return " <TYPE " + settingsPanelState.getManualInputBuffer() + ">";
        }
        return " <EDIT " + formatSettingsValue(settingsPanelState.getEditStartValue()) + ">";
    }

    private String currentWarpMenuLabel() {
        String label = WarpPresets.formatDisplayLabel(simulationSettings.getTimeWarp());
        return simulationState.isPaused() ? label + ", PAUSED" : label;
    }

    private String cameraModeLabel() {
        return cameraSettings.hudCameraModeLabel();
    }

    private String currentFovLabel() {
        return cameraSettings.isAdaptiveFreeCamFovEnabled()
                ? "AUTO " + Math.round(cameraState.getCurrentFreeCamFov()) + "°"
                : "FIX " + fixedFreeCamFovLabel() + "°";
    }

    private String followTargetLabel() {
        return cameraState.getFollowTarget() != null
                ? cameraState.getFollowTarget().name.toUpperCase(Locale.US)
                : "NONE";
    }

    private int fixedFreeCamFovLabel() {
        return cameraSettings.getFixedFreeCamFovPresetIndex() == 0 ? 5 : 60;
    }

    private String orbitsDimensionsLabel() {
        return simulationSettings.isOrbitsDimensions3D() ? "3D" : "FLAT 2D";
    }
}