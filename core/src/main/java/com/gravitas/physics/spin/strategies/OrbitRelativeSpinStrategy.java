package com.gravitas.physics.spin.strategies;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.util.OrbitUtils;

import java.util.List;

public final class OrbitRelativeSpinStrategy implements SpinModeStrategy {

    private final InertialSpinStrategy inertialSpinStrategy;

    private final double[] scratchOrbitNormal = new double[3];
    private final double[] scratchOrbitReferenceAxis = new double[3];
    private final double[] scratchOrbitTangentAxis = new double[3];
    private final double[] scratchResolvedSpinAxis = new double[3];

    public OrbitRelativeSpinStrategy(InertialSpinStrategy inertialSpinStrategy) {
        this.inertialSpinStrategy = inertialSpinStrategy;
    }

    @Override
    public void sync(List<CelestialBody> bodies, double absoluteSimulationTime) {
        applyAll(bodies, absoluteSimulationTime);
    }

    @Override
    public void update(List<CelestialBody> bodies, double dt, double absoluteSimulationTime) {
        applyAll(bodies, absoluteSimulationTime);
    }

    private void applyAll(List<CelestialBody> bodies, double absoluteSimulationTime) {
        for (CelestialBody body : bodies) {
            apply(body, absoluteSimulationTime);
        }
    }

    private void apply(CelestialBody body, double absoluteSimulationTime) {
        if (!body.active) {
            return;
        }

        if (!resolveOrbitRelativeSpinAxis(body,
                scratchOrbitNormal,
                scratchOrbitReferenceAxis,
                scratchOrbitTangentAxis,
                scratchResolvedSpinAxis)) {
            inertialSpinStrategy.apply(body, absoluteSimulationTime);
            return;
        }

        body.setSpinAxisDirection(
                scratchResolvedSpinAxis[0],
                scratchResolvedSpinAxis[1],
                scratchResolvedSpinAxis[2]);
        body.setSpinAngularSpeed(body.spinReference.baseSpinAngularSpeed);
        body.setRotationAngle(
                body.spinReference.baseRotationAngle
                        + body.spinReference.baseSpinAngularSpeed * absoluteSimulationTime);
    }

    private boolean resolveOrbitRelativeSpinAxis(CelestialBody body,
            double[] orbitNormal,
            double[] referenceAxis,
            double[] tangentAxis,
            double[] outAxis) {
        orbitNormal[0] = body.orbitFrame.normalX;
        orbitNormal[1] = body.orbitFrame.normalY;
        orbitNormal[2] = body.orbitFrame.normalZ;
        return OrbitUtils.resolveOrbitRelativeAxis(
                orbitNormal,
                body.spinReference.orbitTilt,
                body.spinReference.orbitAzimuth,
                referenceAxis,
                tangentAxis,
                outAxis);
    }
}