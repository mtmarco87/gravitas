package com.gravitas.settings.enums;

public enum CloudCompositingMode {
    EQUAL_BLEND("BALANCED", 0),
    TEXTURE_DOMINANT("TEXTURE LED", 1),
    ADAPTIVE("ADAPTIVE D/N", 2);

    private final String hudLabel;
    private final int shaderValue;

    CloudCompositingMode(String hudLabel, int shaderValue) {
        this.hudLabel = hudLabel;
        this.shaderValue = shaderValue;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public int shaderValue() {
        return shaderValue;
    }

    public CloudCompositingMode next() {
        CloudCompositingMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
