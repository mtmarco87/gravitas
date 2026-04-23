package com.gravitas.entities.bodies.celestial_body.runtime;

/** Runtime face-lock controller state plus persistent lock reference data. */
public final class FaceLockState {

    public String status;
    public String target;
    public double orbitalRate;
    public double rateError;
    public double gateOrbitalRate;
    public double gateRateError;
    public double phaseError;
    public double syncTolerance;
    public double axialAcceleration;
    public boolean engaged;
    public String referenceTarget;
    public double phaseBias;

    public void record(String status,
            String targetName,
            double orbitalRate,
            double rateError,
            double gateOrbitalRate,
            double gateRateError,
            double phaseError,
            double syncTolerance,
            double axialAcceleration) {
        this.status = status;
        this.target = targetName;
        this.orbitalRate = orbitalRate;
        this.rateError = rateError;
        this.gateOrbitalRate = gateOrbitalRate;
        this.gateRateError = gateRateError;
        this.phaseError = phaseError;
        this.syncTolerance = syncTolerance;
        this.axialAcceleration = axialAcceleration;
    }

    public void captureReference(String targetName, double phaseBias) {
        engaged = true;
        referenceTarget = targetName;
        this.phaseBias = phaseBias;
    }

    public void clearReference() {
        engaged = false;
        referenceTarget = null;
        phaseBias = 0.0;
    }

    public void reset() {
        status = null;
        target = null;
        orbitalRate = 0.0;
        rateError = 0.0;
        gateOrbitalRate = 0.0;
        gateRateError = 0.0;
        phaseError = 0.0;
        syncTolerance = 0.0;
        axialAcceleration = 0.0;
    }
}