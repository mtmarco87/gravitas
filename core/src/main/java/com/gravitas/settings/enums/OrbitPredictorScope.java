package com.gravitas.settings.enums;

public enum OrbitPredictorScope {
    LOCAL("Local"),
    ALL("All"),
    ADAPTIVE("Adaptive"),
    ;

    private final String hudLabel;

    OrbitPredictorScope(String hudLabel) {
        this.hudLabel = hudLabel;
    }

    public String hudLabel() {
        return hudLabel;
    }

    public OrbitPredictorScope next() {
        OrbitPredictorScope[] scopes = values();
        return scopes[(ordinal() + 1) % scopes.length];
    }
}
