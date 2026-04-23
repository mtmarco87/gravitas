package com.gravitas.settings.enums;

public enum CloudFxMode {
    ALL("ALL", true, true),
    TEXTURE_ONLY("TEXTURE ONLY", true, false),
    OFF("OFF", false, false);

    private final String hudLabel;
    private final boolean texturesEnabled;
    private final boolean proceduralEnabled;

    CloudFxMode(String hudLabel, boolean texturesEnabled, boolean proceduralEnabled) {
        this.hudLabel = hudLabel;
        this.texturesEnabled = texturesEnabled;
        this.proceduralEnabled = proceduralEnabled;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public boolean texturesEnabled() {
        return texturesEnabled;
    }

    public boolean proceduralEnabled() {
        return proceduralEnabled;
    }

    public boolean isOff() {
        return this == OFF;
    }

    public CloudFxMode next() {
        CloudFxMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}