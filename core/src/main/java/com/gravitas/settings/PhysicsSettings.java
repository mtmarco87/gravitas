package com.gravitas.settings;

import com.gravitas.settings.enums.DynamicSystemsMode;
import com.gravitas.settings.enums.SpinMode;

public final class PhysicsSettings {

    private static final SpinMode DEFAULT_SPIN_MODE = SpinMode.DYNAMIC;
    private static final DynamicSystemsMode DEFAULT_DYNAMIC_SYSTEMS_MODE = DynamicSystemsMode.ALL;

    private SpinMode spinMode;
    private DynamicSystemsMode dynamicSystemsMode;

    public PhysicsSettings() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        spinMode = DEFAULT_SPIN_MODE;
        dynamicSystemsMode = DEFAULT_DYNAMIC_SYSTEMS_MODE;
    }

    public SpinMode getSpinMode() {
        return spinMode;
    }

    public void setSpinMode(SpinMode spinMode) {
        if (spinMode != null) {
            this.spinMode = spinMode;
        }
    }

    public void cycleSpinMode() {
        spinMode = spinMode.next();
    }

    public DynamicSystemsMode getDynamicSystemsMode() {
        return dynamicSystemsMode;
    }

    public void setDynamicSystemsMode(DynamicSystemsMode dynamicSystemsMode) {
        if (dynamicSystemsMode != null) {
            this.dynamicSystemsMode = dynamicSystemsMode;
        }
    }

    public void cycleDynamicSystemsMode() {
        dynamicSystemsMode = dynamicSystemsMode.next();
    }

    public boolean isTidalDynamicsEnabled() {
        return dynamicSystemsMode.isTidalEnabled();
    }

    public boolean isFaceLockDynamicsEnabled() {
        return dynamicSystemsMode.isFaceLockEnabled();
    }

    public void copyFrom(PhysicsSettings other) {
        if (other == null) {
            return;
        }
        spinMode = other.spinMode;
        dynamicSystemsMode = other.dynamicSystemsMode;
    }
}