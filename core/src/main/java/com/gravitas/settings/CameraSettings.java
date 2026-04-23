package com.gravitas.settings;

import com.gravitas.rendering.core.CameraMode;
import com.gravitas.rendering.core.FollowFrameMode;

public final class CameraSettings {

    private static final CameraMode DEFAULT_CAMERA_MODE = CameraMode.FREE_CAM;
    private static final FollowFrameMode DEFAULT_FOLLOW_FRAME_MODE = FollowFrameMode.FREE_ORBIT;
    private static final boolean DEFAULT_ADAPTIVE_FREE_CAM_FOV_ENABLED = true;
    private static final int DEFAULT_FIXED_FREE_CAM_FOV_PRESET_INDEX = 0;
    private static final int FREE_CAM_FOV_PRESET_COUNT = 2;

    private CameraMode cameraMode;
    private FollowFrameMode followFrameMode;
    private boolean adaptiveFreeCamFovEnabled;
    private int fixedFreeCamFovPresetIndex;

    public CameraSettings() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        cameraMode = DEFAULT_CAMERA_MODE;
        followFrameMode = DEFAULT_FOLLOW_FRAME_MODE;
        adaptiveFreeCamFovEnabled = DEFAULT_ADAPTIVE_FREE_CAM_FOV_ENABLED;
        fixedFreeCamFovPresetIndex = DEFAULT_FIXED_FREE_CAM_FOV_PRESET_INDEX;
    }

    public CameraMode getCameraMode() {
        return cameraMode;
    }

    public void setCameraMode(CameraMode cameraMode) {
        this.cameraMode = cameraMode != null ? cameraMode : DEFAULT_CAMERA_MODE;
    }

    public void toggleCameraMode() {
        cameraMode = cameraMode == CameraMode.FREE_CAM ? CameraMode.TOP_VIEW : CameraMode.FREE_CAM;
    }

    public String hudCameraModeLabel() {
        return cameraMode == CameraMode.FREE_CAM ? "3D" : "2D";
    }

    public FollowFrameMode getFollowFrameMode() {
        return followFrameMode;
    }

    public void setFollowFrameMode(FollowFrameMode followFrameMode) {
        this.followFrameMode = followFrameMode != null ? followFrameMode : DEFAULT_FOLLOW_FRAME_MODE;
    }

    public void cycleFollowFrameMode() {
        followFrameMode = followFrameMode.next();
    }

    public boolean isAdaptiveFreeCamFovEnabled() {
        return adaptiveFreeCamFovEnabled;
    }

    public void setAdaptiveFreeCamFovEnabled(boolean adaptiveFreeCamFovEnabled) {
        this.adaptiveFreeCamFovEnabled = adaptiveFreeCamFovEnabled;
    }

    public int getFixedFreeCamFovPresetIndex() {
        return fixedFreeCamFovPresetIndex;
    }

    public void setFixedFreeCamFovPresetIndex(int fixedFreeCamFovPresetIndex) {
        this.fixedFreeCamFovPresetIndex = Math.max(0,
                Math.min(fixedFreeCamFovPresetIndex, FREE_CAM_FOV_PRESET_COUNT - 1));
    }

    public void cycleFreeCamFovMode() {
        if (adaptiveFreeCamFovEnabled) {
            adaptiveFreeCamFovEnabled = false;
            fixedFreeCamFovPresetIndex = 0;
            return;
        }

        if (fixedFreeCamFovPresetIndex < FREE_CAM_FOV_PRESET_COUNT - 1) {
            fixedFreeCamFovPresetIndex++;
            return;
        }

        adaptiveFreeCamFovEnabled = true;
        fixedFreeCamFovPresetIndex = 0;
    }
}