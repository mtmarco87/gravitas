package com.gravitas.settings;

import com.gravitas.settings.enums.OrbitOverlayMode;
import com.gravitas.settings.enums.OrbitPredictorScope;
import com.gravitas.settings.enums.OrbitRenderMode;
import com.gravitas.settings.enums.OrientationOverlayMode;

public final class OverlaySettings {

    private static final boolean DEFAULT_VISUAL_SCALE_MODE = true;
    private static final OrbitOverlayMode DEFAULT_ORBIT_OVERLAY_MODE = OrbitOverlayMode.TRAILS_ONLY;
    private static final OrbitRenderMode DEFAULT_ORBIT_RENDER_MODE = OrbitRenderMode.CPU_DASHED_SIMPLE;
    private static final OrbitPredictorScope DEFAULT_ORBIT_PREDICTOR_SCOPE = OrbitPredictorScope.LOCAL;
    private static final OrientationOverlayMode DEFAULT_ORIENTATION_OVERLAY_MODE = OrientationOverlayMode.NONE;

    private boolean visualScaleMode;
    private OrbitOverlayMode orbitOverlayMode;
    private OrbitRenderMode orbitRenderMode;
    private OrbitPredictorScope orbitPredictorScope;
    private OrientationOverlayMode orientationOverlayMode;

    public OverlaySettings() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        visualScaleMode = DEFAULT_VISUAL_SCALE_MODE;
        orbitOverlayMode = DEFAULT_ORBIT_OVERLAY_MODE;
        orbitRenderMode = OrbitRenderMode.resolveEnabled(DEFAULT_ORBIT_RENDER_MODE);
        orbitPredictorScope = DEFAULT_ORBIT_PREDICTOR_SCOPE;
        orientationOverlayMode = DEFAULT_ORIENTATION_OVERLAY_MODE;
    }

    public boolean isVisualScaleMode() {
        return visualScaleMode;
    }

    public void setVisualScaleMode(boolean visualScaleMode) {
        this.visualScaleMode = visualScaleMode;
    }

    public void toggleVisualScaleMode() {
        visualScaleMode = !visualScaleMode;
    }

    public OrbitOverlayMode getOrbitOverlayMode() {
        return orbitOverlayMode;
    }

    public void setOrbitOverlayMode(OrbitOverlayMode orbitOverlayMode) {
        this.orbitOverlayMode = orbitOverlayMode;
    }

    public void cycleOrbitOverlayMode() {
        orbitOverlayMode = orbitOverlayMode.next();
    }

    public boolean isShowTrails() {
        return orbitOverlayMode.showTrails();
    }

    public boolean isShowOrbitPredictors() {
        return orbitOverlayMode.showOrbitPredictors();
    }

    public OrbitRenderMode getOrbitRenderMode() {
        return orbitRenderMode;
    }

    public void setOrbitRenderMode(OrbitRenderMode orbitRenderMode) {
        OrbitRenderMode resolved = OrbitRenderMode.resolveEnabled(orbitRenderMode);
        if (resolved != null) {
            this.orbitRenderMode = resolved;
        }
    }

    public void cycleOrbitRenderMode() {
        OrbitRenderMode next = orbitRenderMode != null
                ? orbitRenderMode.nextEnabled()
                : OrbitRenderMode.resolveEnabled(DEFAULT_ORBIT_RENDER_MODE);
        if (next != null) {
            orbitRenderMode = next;
        }
    }

    public OrbitPredictorScope getOrbitPredictorScope() {
        return orbitPredictorScope;
    }

    public void setOrbitPredictorScope(OrbitPredictorScope orbitPredictorScope) {
        this.orbitPredictorScope = orbitPredictorScope != null
                ? orbitPredictorScope
                : DEFAULT_ORBIT_PREDICTOR_SCOPE;
    }

    public void cycleOrbitPredictorScope() {
        orbitPredictorScope = (orbitPredictorScope != null ? orbitPredictorScope : DEFAULT_ORBIT_PREDICTOR_SCOPE)
                .next();
    }

    public OrientationOverlayMode getOrientationOverlayMode() {
        return orientationOverlayMode;
    }

    public void setOrientationOverlayMode(OrientationOverlayMode orientationOverlayMode) {
        this.orientationOverlayMode = orientationOverlayMode != null
                ? orientationOverlayMode
                : DEFAULT_ORIENTATION_OVERLAY_MODE;
    }

    public void cycleOrientationOverlayMode() {
        orientationOverlayMode = (orientationOverlayMode != null ? orientationOverlayMode
                : DEFAULT_ORIENTATION_OVERLAY_MODE)
                .next();
    }

    public boolean isShowOrbitNormal() {
        return orientationOverlayMode != null && orientationOverlayMode.showOrbitNormal();
    }

    public boolean isShowSpinAxis() {
        return orientationOverlayMode != null && orientationOverlayMode.showSpinAxis();
    }

    public boolean isShowPrimeMeridian() {
        return orientationOverlayMode != null && orientationOverlayMode.showPrimeMeridian();
    }
}
