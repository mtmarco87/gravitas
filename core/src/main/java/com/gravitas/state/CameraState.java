package com.gravitas.state;

import com.gravitas.entities.core.SimObject;

public final class CameraState {

    private SimObject followTarget;
    private float currentFreeCamFov = 60f;

    public SimObject getFollowTarget() {
        return followTarget;
    }

    public void setFollowTarget(SimObject followTarget) {
        this.followTarget = followTarget;
    }

    public void clearFollowTarget() {
        followTarget = null;
    }

    public float getCurrentFreeCamFov() {
        return currentFreeCamFov;
    }

    public void setCurrentFreeCamFov(float currentFreeCamFov) {
        this.currentFreeCamFov = currentFreeCamFov;
    }
}