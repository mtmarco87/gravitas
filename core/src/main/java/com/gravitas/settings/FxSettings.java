package com.gravitas.settings;

import com.gravitas.settings.enums.CloudCompositingMode;
import com.gravitas.settings.enums.CloudFxMode;
import com.gravitas.settings.enums.CloudTerminatorMode;

public final class FxSettings {

    public static final float VALUE_STEP = 0.01f;

    private static final boolean DEFAULT_MASTER_ENABLED = true;
    private static final CloudFxMode DEFAULT_CLOUD_FX_MODE = CloudFxMode.ALL;
    private static final CloudCompositingMode DEFAULT_CLOUD_COMPOSITING_MODE = CloudCompositingMode.ADAPTIVE;
    private static final CloudTerminatorMode DEFAULT_CLOUD_TERMINATOR_MODE = CloudTerminatorMode.NORMAL;
    private static final float DEFAULT_CLOUD_PROCEDURAL_TEXTURE_COUPLING = 0.0f;
    private static final float DEFAULT_CLOUD_TEXTURE_ALPHA_WEIGHT = 1.05f;
    private static final float DEFAULT_CLOUD_PROCEDURAL_ALPHA_WEIGHT = 0.24f;
    private static final boolean DEFAULT_DAY_NIGHT_ENABLED = true;
    private static final boolean DEFAULT_CLOUD_DAY_NIGHT_ENABLED = true;
    private static final boolean DEFAULT_ATMOSPHERE_DAY_NIGHT_ENABLED = true;
    private static final boolean DEFAULT_RING_SHADOW_ENABLED = true;
    private static final float DEFAULT_ATMOSPHERE_NIGHT_OUTER_FLOOR = 0.16f;
    private static final float DEFAULT_ATMOSPHERE_NIGHT_INNER_FLOOR = 0.02f;
    private static final float DEFAULT_ATMOSPHERE_DENSE_NIGHT_OUTER_FLOOR = 0.25f;
    private static final float DEFAULT_ATMOSPHERE_DENSE_NIGHT_INNER_FLOOR = 0.10f;

    private boolean masterEnabled;
    private CloudFxMode cloudFxMode;
    private CloudCompositingMode cloudCompositingMode;
    private CloudTerminatorMode cloudTerminatorMode;
    private float cloudProceduralTextureCoupling;
    private float cloudTextureAlphaWeight;
    private float cloudProceduralAlphaWeight;
    private boolean cloudDayNightEnabled;
    private boolean dayNightEnabled;
    private boolean ringShadowEnabled;
    private float atmosphereNightOuterFloor;
    private float atmosphereNightInnerFloor;
    private float atmosphereDenseNightOuterFloor;
    private float atmosphereDenseNightInnerFloor;
    private boolean atmosphereDayNightEnabled;

    public FxSettings() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        masterEnabled = DEFAULT_MASTER_ENABLED;
        cloudFxMode = DEFAULT_CLOUD_FX_MODE;
        cloudCompositingMode = DEFAULT_CLOUD_COMPOSITING_MODE;
        cloudTerminatorMode = DEFAULT_CLOUD_TERMINATOR_MODE;
        cloudProceduralTextureCoupling = DEFAULT_CLOUD_PROCEDURAL_TEXTURE_COUPLING;
        cloudTextureAlphaWeight = DEFAULT_CLOUD_TEXTURE_ALPHA_WEIGHT;
        cloudProceduralAlphaWeight = DEFAULT_CLOUD_PROCEDURAL_ALPHA_WEIGHT;
        cloudDayNightEnabled = DEFAULT_CLOUD_DAY_NIGHT_ENABLED;
        dayNightEnabled = DEFAULT_DAY_NIGHT_ENABLED;
        ringShadowEnabled = DEFAULT_RING_SHADOW_ENABLED;
        atmosphereNightOuterFloor = DEFAULT_ATMOSPHERE_NIGHT_OUTER_FLOOR;
        atmosphereNightInnerFloor = DEFAULT_ATMOSPHERE_NIGHT_INNER_FLOOR;
        atmosphereDenseNightOuterFloor = DEFAULT_ATMOSPHERE_DENSE_NIGHT_OUTER_FLOOR;
        atmosphereDenseNightInnerFloor = DEFAULT_ATMOSPHERE_DENSE_NIGHT_INNER_FLOOR;
        atmosphereDayNightEnabled = DEFAULT_ATMOSPHERE_DAY_NIGHT_ENABLED;
    }

    public boolean isMasterEnabled() {
        return masterEnabled;
    }

    public void toggleMasterEnabled() {
        masterEnabled = !masterEnabled;
    }

    public CloudFxMode getCloudFxMode() {
        return cloudFxMode;
    }

    public CloudFxMode getEffectiveCloudFxMode() {
        return masterEnabled ? cloudFxMode : CloudFxMode.OFF;
    }

    public void cycleCloudFxMode() {
        cloudFxMode = cloudFxMode.next();
    }

    public boolean isCloudDayNightEnabled() {
        return cloudDayNightEnabled;
    }

    public boolean isCloudDayNightActive() {
        return isSurfaceDayNightEnabled() && cloudDayNightEnabled;
    }

    public void toggleCloudDayNightEnabled() {
        cloudDayNightEnabled = !cloudDayNightEnabled;
    }

    public boolean isDayNightEnabled() {
        return dayNightEnabled;
    }

    public boolean isSurfaceDayNightEnabled() {
        return masterEnabled && dayNightEnabled;
    }

    public void toggleDayNightEnabled() {
        dayNightEnabled = !dayNightEnabled;
    }

    public boolean isRingShadowEnabled() {
        return ringShadowEnabled;
    }

    public void toggleRingShadowEnabled() {
        ringShadowEnabled = !ringShadowEnabled;
    }

    public CloudCompositingMode getCloudCompositingMode() {
        return cloudCompositingMode;
    }

    public void cycleCloudCompositingMode() {
        cloudCompositingMode = cloudCompositingMode.next();
    }

    public CloudTerminatorMode getCloudTerminatorMode() {
        return cloudTerminatorMode;
    }

    public void cycleCloudTerminatorMode() {
        cloudTerminatorMode = cloudTerminatorMode.next();
    }

    public float getCloudProceduralTextureCoupling() {
        return cloudProceduralTextureCoupling;
    }

    public void addCloudProceduralTextureCoupling(float delta) {
        cloudProceduralTextureCoupling = clamp(cloudProceduralTextureCoupling + delta, 0.0f, 1.0f);
    }

    public void setCloudProceduralTextureCoupling(float value) {
        cloudProceduralTextureCoupling = clamp(value, 0.0f, 1.0f);
    }

    public float getCloudTextureAlphaWeight() {
        return cloudTextureAlphaWeight;
    }

    public void addCloudTextureAlphaWeight(float delta) {
        cloudTextureAlphaWeight = clamp(cloudTextureAlphaWeight + delta, 0.0f, 2.0f);
    }

    public void setCloudTextureAlphaWeight(float value) {
        cloudTextureAlphaWeight = clamp(value, 0.0f, 2.0f);
    }

    public float getCloudProceduralAlphaWeight() {
        return cloudProceduralAlphaWeight;
    }

    public void addCloudProceduralAlphaWeight(float delta) {
        cloudProceduralAlphaWeight = clamp(cloudProceduralAlphaWeight + delta, 0.0f, 2.0f);
    }

    public void setCloudProceduralAlphaWeight(float value) {
        cloudProceduralAlphaWeight = clamp(value, 0.0f, 2.0f);
    }

    public float getAtmosphereNightOuterFloor() {
        return atmosphereNightOuterFloor;
    }

    public void addAtmosphereNightOuterFloor(float delta) {
        atmosphereNightOuterFloor = clamp(atmosphereNightOuterFloor + delta, 0.0f, 1.0f);
    }

    public void setAtmosphereNightOuterFloor(float value) {
        atmosphereNightOuterFloor = clamp(value, 0.0f, 1.0f);
    }

    public float getAtmosphereNightInnerFloor() {
        return atmosphereNightInnerFloor;
    }

    public void addAtmosphereNightInnerFloor(float delta) {
        atmosphereNightInnerFloor = clamp(atmosphereNightInnerFloor + delta, 0.0f, 1.0f);
    }

    public void setAtmosphereNightInnerFloor(float value) {
        atmosphereNightInnerFloor = clamp(value, 0.0f, 1.0f);
    }

    public float getAtmosphereDenseNightOuterFloor() {
        return atmosphereDenseNightOuterFloor;
    }

    public void addAtmosphereDenseNightOuterFloor(float delta) {
        atmosphereDenseNightOuterFloor = clamp(atmosphereDenseNightOuterFloor + delta, 0.0f, 1.0f);
    }

    public void setAtmosphereDenseNightOuterFloor(float value) {
        atmosphereDenseNightOuterFloor = clamp(value, 0.0f, 1.0f);
    }

    public float getAtmosphereDenseNightInnerFloor() {
        return atmosphereDenseNightInnerFloor;
    }

    public void addAtmosphereDenseNightInnerFloor(float delta) {
        atmosphereDenseNightInnerFloor = clamp(atmosphereDenseNightInnerFloor + delta, 0.0f, 1.0f);
    }

    public void setAtmosphereDenseNightInnerFloor(float value) {
        atmosphereDenseNightInnerFloor = clamp(value, 0.0f, 1.0f);
    }

    public boolean isAtmosphereDayNightEnabled() {
        return atmosphereDayNightEnabled;
    }

    public boolean isAtmosphereDayNightActive() {
        return isSurfaceDayNightEnabled() && atmosphereDayNightEnabled;
    }

    public void toggleAtmosphereDayNightEnabled() {
        atmosphereDayNightEnabled = !atmosphereDayNightEnabled;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}