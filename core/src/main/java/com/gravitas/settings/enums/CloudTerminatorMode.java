package com.gravitas.settings.enums;

public enum CloudTerminatorMode {
    NORMAL("NORMAL", 0),
    EXTENDED("EXTENDED", 1),
    SHARP("SHARP", 2);

    private final String hudLabel;
    private final int shaderValue;

    CloudTerminatorMode(String hudLabel, int shaderValue) {
        this.hudLabel = hudLabel;
        this.shaderValue = shaderValue;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public int shaderValue() {
        return shaderValue;
    }

    public CloudTerminatorMode next() {
        CloudTerminatorMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}