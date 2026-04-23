package com.gravitas.physics.spin.strategies;

import java.util.List;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;

public final class InertialSpinStrategy implements SpinModeStrategy {

    @Override
    public void sync(List<CelestialBody> bodies, double absoluteSimulationTime) {
        applyAll(bodies, absoluteSimulationTime);
    }

    @Override
    public void update(List<CelestialBody> bodies, double dt, double absoluteSimulationTime) {
        applyAll(bodies, absoluteSimulationTime);
    }

    public void apply(CelestialBody body, double absoluteSimulationTime) {
        if (!body.active) {
            return;
        }

        body.setSpinAxisDirection(
                body.spinReference.inertialAxisX,
                body.spinReference.inertialAxisY,
                body.spinReference.inertialAxisZ);
        body.setSpinAngularSpeed(body.spinReference.baseSpinAngularSpeed);
        body.setRotationAngle(
                body.spinReference.baseRotationAngle
                        + body.spinReference.baseSpinAngularSpeed * absoluteSimulationTime);
    }

    private void applyAll(List<CelestialBody> bodies, double absoluteSimulationTime) {
        for (CelestialBody body : bodies) {
            apply(body, absoluteSimulationTime);
        }
    }
}