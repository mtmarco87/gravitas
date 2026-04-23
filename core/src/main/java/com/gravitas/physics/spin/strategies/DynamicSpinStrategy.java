package com.gravitas.physics.spin.strategies;

import com.gravitas.entities.bodies.celestial_body.CelestialBody;
import com.gravitas.settings.PhysicsSettings;
import com.gravitas.util.AngleUtils;
import com.gravitas.util.OrbitUtils;
import com.gravitas.util.GeometryUtils;

import java.util.List;

public final class DynamicSpinStrategy implements SpinModeStrategy {

    private static final double G = 6.674e-11;
    private static final double MIN_AXIS_EVOLUTION_SPEED = 1e-12;
    private static final FaceLockMode FACE_LOCK_MODE = FaceLockMode.ABSOLUTE;
    private static final double FACE_LOCK_SPRING_MULTIPLIER = FaceLockSpringMode.LIGHT.getValue();
    private static final double FACE_LOCK_DAMPING_RATIO = 1.5;
    private static final double FACE_LOCK_ACQUIRE_RATE_TOLERANCE = 0.05;
    private static final double FACE_LOCK_RELEASE_RATE_TOLERANCE = 0.20;

    private final PhysicsSettings settings;

    private final double[] scratchOrbitNormal = new double[3];
    private final double[] scratchOrbitalOmega = new double[3];
    private final double[] scratchSpinOmega = new double[3];
    private final double[] scratchEquilibriumSpin = new double[3];
    private final double[] scratchTidalForcing = new double[3];
    private final double[] scratchFaceLockTargetAxis = new double[3];
    private final double[] scratchPrimeMeridian = new double[3];
    private final double[] scratchSpinAxis = new double[3];

    public DynamicSpinStrategy(PhysicsSettings settings) {
        this.settings = settings;
    }

    @Override
    public void sync(List<CelestialBody> bodies, double absoluteSimulationTime) {
    }

    @Override
    public void update(List<CelestialBody> bodies, double dt, double absoluteSimulationTime) {
        double[] alphaX = new double[bodies.size()];
        double[] alphaY = new double[bodies.size()];
        double[] alphaZ = new double[bodies.size()];

        if (settings.isTidalDynamicsEnabled()) {
            accumulateTidalDynamics(bodies, alphaX, alphaY, alphaZ);
        }
        if (settings.isFaceLockDynamicsEnabled()) {
            accumulateFaceLockDynamics(bodies, alphaX, alphaY, alphaZ);
        }

        for (int i = 0; i < bodies.size(); i++) {
            CelestialBody body = bodies.get(i);
            if (!body.active) {
                continue;
            }
            applySpinAngularAcceleration(body, alphaX[i], alphaY[i], alphaZ[i], dt);
            if (body.getSpinAngularSpeed() != 0.0) {
                body.advanceRotation(body.getSpinAngularSpeed() * dt);
            }
        }
    }

    private void accumulateTidalDynamics(List<CelestialBody> bodies,
            double[] alphaX,
            double[] alphaY,
            double[] alphaZ) {
        for (int i = 0; i < bodies.size(); i++) {
            CelestialBody body = bodies.get(i);
            if (!body.active || !body.hasTidalResponse()) {
                continue;
            }
            for (int j = 0; j < bodies.size(); j++) {
                if (i == j) {
                    continue;
                }
                CelestialBody partner = bodies.get(j);
                if (!partner.active) {
                    continue;
                }
                accumulateTidalAngularAcceleration(body, partner, alphaX, alphaY, alphaZ, i);
            }
        }
    }

    private void accumulateFaceLockDynamics(List<CelestialBody> bodies,
            double[] alphaX,
            double[] alphaY,
            double[] alphaZ) {
        for (int i = 0; i < bodies.size(); i++) {
            CelestialBody body = bodies.get(i);
            if (!body.active) {
                continue;
            }
            accumulateFaceLockAngularAcceleration(body, alphaX, alphaY, alphaZ, i);
        }
    }

    private void accumulateTidalAngularAcceleration(CelestialBody body,
            CelestialBody partner,
            double[] alphaX,
            double[] alphaY,
            double[] alphaZ,
            int bodyIndex) {
        double spinAxisInertia = body.getSpinAxisMomentOfInertia();
        if (!body.hasTidalResponse()
                || body.mass <= 0.0
                || body.radius <= 0.0
                || partner.mass <= 0.0) {
            return;
        }

        double relX = partner.x - body.x;
        double relY = partner.y - body.y;
        double relZ = partner.z - body.z;
        double relVx = partner.vx - body.vx;
        double relVy = partner.vy - body.vy;
        double relVz = partner.vz - body.vz;

        double distanceSq = relX * relX + relY * relY + relZ * relZ;
        double minDistance = body.radius + partner.radius;
        if (distanceSq <= minDistance * minDistance) {
            return;
        }

        if (!OrbitUtils.computeOrbitFrame(relX, relY, relZ, relVx, relVy, relVz,
                scratchOrbitNormal, scratchOrbitalOmega)) {
            return;
        }

        computeSpinAngularVelocity(body, scratchSpinOmega);
        computeEquilibriumSpin(scratchOrbitalOmega, scratchEquilibriumSpin);
        computeTidalForcing(scratchSpinOmega, scratchEquilibriumSpin, scratchTidalForcing);

        double distance6 = distanceSq * distanceSq * distanceSq;
        double spinAxisInertiaOverRadiusSq = spinAxisInertia / (body.radius * body.radius);
        double coeff = 3.0 * G * partner.mass * partner.mass * body.spinPhysics.k2OverQ
                * body.radius * body.radius * body.radius
                / (spinAxisInertiaOverRadiusSq * distance6);
        if (!Double.isFinite(coeff) || coeff == 0.0) {
            return;
        }

        double contributionX = -coeff * scratchTidalForcing[0];
        double contributionY = -coeff * scratchTidalForcing[1];
        double contributionZ = -coeff * scratchTidalForcing[2];
        double contributionMagnitude = Math.sqrt(
                contributionX * contributionX + contributionY * contributionY + contributionZ * contributionZ);
        if (contributionMagnitude == 0.0) {
            return;
        }

        alphaX[bodyIndex] += contributionX;
        alphaY[bodyIndex] += contributionY;
        alphaZ[bodyIndex] += contributionZ;
        body.recordTidalContribution(partner.name, contributionMagnitude);
    }

    private void accumulateFaceLockAngularAcceleration(CelestialBody body,
            double[] alphaX,
            double[] alphaY,
            double[] alphaZ,
            int bodyIndex) {
        CelestialBody partner = body.parent;
        if (partner == null || !partner.active) {
            body.clearFaceLockReference();
            body.recordFaceLockState("NO_PARENT", null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            return;
        }

        if (body.faceLockState.referenceTarget != null && !body.faceLockState.referenceTarget.equals(partner.name)) {
            body.clearFaceLockReference();
        }

        double orbitalRate = computeParentOrbitalRateAlongSpin(body);
        if (!Double.isFinite(orbitalRate) || Math.abs(orbitalRate) <= 1e-12) {
            body.clearFaceLockReference();
            body.recordFaceLockState("NO_RATE", partner.name, orbitalRate, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            return;
        }

        double gateOrbitalRate = computeFaceLockGateRateAlongSpin(body, partner, orbitalRate);
        if (!Double.isFinite(gateOrbitalRate) || Math.abs(gateOrbitalRate) <= 1e-12) {
            body.clearFaceLockReference();
            body.recordFaceLockState("NO_RATE", partner.name, orbitalRate, 0.0,
                    gateOrbitalRate, 0.0, 0.0, 0.0, 0.0);
            return;
        }

        double spinAngularSpeed = body.getSpinAngularSpeed();
        double rateError = spinAngularSpeed - orbitalRate;
        double gateRateError = spinAngularSpeed - gateOrbitalRate;
        boolean engaged = body.faceLockState.engaged;
        double syncReferenceRate = engaged ? orbitalRate : gateOrbitalRate;
        double syncReferenceError = engaged ? rateError : gateRateError;
        double syncTolerance = faceLockSyncTolerance(syncReferenceRate, engaged);
        if (!isFaceLockEligible(syncReferenceError, syncReferenceRate, engaged)) {
            boolean released = engaged;
            body.clearFaceLockReference();
            body.recordFaceLockState(released ? "RELEASED" : "OUT_OF_SYNC",
                    partner.name,
                    orbitalRate,
                    rateError,
                    gateOrbitalRate,
                    gateRateError,
                    0.0,
                    syncTolerance, 0.0);
            return;
        }

        if (!computeFaceLockTargetAxis(body, partner, scratchFaceLockTargetAxis)) {
            body.clearFaceLockReference();
            body.recordFaceLockState("NO_TARGET", partner.name, orbitalRate, rateError,
                    gateOrbitalRate, gateRateError, 0.0,
                    syncTolerance, 0.0);
            return;
        }

        body.getPrimeMeridianAxis(scratchPrimeMeridian);
        double currentPhaseDelta = AngleUtils.wrapSignedAngle(
                GeometryUtils.signedAngleAroundAxis(
                        scratchSpinAxis[0],
                        scratchSpinAxis[1],
                        scratchSpinAxis[2],
                        scratchFaceLockTargetAxis[0],
                        scratchFaceLockTargetAxis[1],
                        scratchFaceLockTargetAxis[2],
                        scratchPrimeMeridian[0],
                        scratchPrimeMeridian[1],
                        scratchPrimeMeridian[2])
                        + body.spinPhysics.preferredLockPhase);
        if (!body.faceLockState.engaged) {
            double phaseBias = FACE_LOCK_MODE == FaceLockMode.DYNAMIC ? currentPhaseDelta : 0f;
            body.captureFaceLockReference(partner.name, phaseBias);
        }
        double phaseError = AngleUtils.wrapSignedAngle(currentPhaseDelta - body.faceLockState.phaseBias);

        double orbitalRateAbs = Math.abs(orbitalRate);
        double springStrength = FACE_LOCK_SPRING_MULTIPLIER * orbitalRateAbs * orbitalRateAbs;
        if (!Double.isFinite(springStrength) || springStrength <= 0.0) {
            body.recordFaceLockState("NO_SPRING", partner.name, orbitalRate, rateError,
                    gateOrbitalRate, gateRateError, phaseError,
                    syncTolerance, 0.0);
            return;
        }
        double dampingStrength = 2.0 * FACE_LOCK_DAMPING_RATIO * Math.sqrt(springStrength);
        double axialAcceleration = -springStrength * phaseError - dampingStrength * rateError;
        if (!Double.isFinite(axialAcceleration)) {
            body.recordFaceLockState("INVALID", partner.name, orbitalRate, rateError,
                    gateOrbitalRate, gateRateError, phaseError,
                    syncTolerance, axialAcceleration);
            return;
        }
        if (axialAcceleration == 0.0) {
            body.recordFaceLockState("ALIGNED", partner.name, orbitalRate, rateError,
                    gateOrbitalRate, gateRateError, phaseError,
                    syncTolerance, 0.0);
            return;
        }

        body.recordFaceLockState("ACTIVE", partner.name, orbitalRate, rateError,
                gateOrbitalRate, gateRateError, phaseError,
                syncTolerance, axialAcceleration);

        alphaX[bodyIndex] += axialAcceleration * scratchSpinAxis[0];
        alphaY[bodyIndex] += axialAcceleration * scratchSpinAxis[1];
        alphaZ[bodyIndex] += axialAcceleration * scratchSpinAxis[2];
    }

    private void applySpinAngularAcceleration(CelestialBody body,
            double alphaX,
            double alphaY,
            double alphaZ,
            double dt) {
        body.getSpinAxis(scratchSpinAxis);
        double poleX = scratchSpinAxis[0];
        double poleY = scratchSpinAxis[1];
        double poleZ = scratchSpinAxis[2];

        double alphaParallel = alphaX * poleX + alphaY * poleY + alphaZ * poleZ;
        double alphaPerpX = alphaX - alphaParallel * poleX;
        double alphaPerpY = alphaY - alphaParallel * poleY;
        double alphaPerpZ = alphaZ - alphaParallel * poleZ;

        double currentSpinSpeed = body.getSpinAngularSpeed();
        if (Math.abs(currentSpinSpeed) > MIN_AXIS_EVOLUTION_SPEED) {
            double invSpinSpeed = 1.0 / currentSpinSpeed;
            poleX += alphaPerpX * invSpinSpeed * dt;
            poleY += alphaPerpY * invSpinSpeed * dt;
            poleZ += alphaPerpZ * invSpinSpeed * dt;
            body.setSpinAxisDirection(poleX, poleY, poleZ);
        }

        body.setSpinAngularSpeed(currentSpinSpeed + alphaParallel * dt);
    }

    private void computeSpinAngularVelocity(CelestialBody body, double[] out) {
        out[0] = body.angularVelocityX;
        out[1] = body.angularVelocityY;
        out[2] = body.angularVelocityZ;
    }

    private double computeParentOrbitalRateAlongSpin(CelestialBody body) {
        body.getSpinAxis(scratchSpinAxis);
        return body.orbitFrame.angularVelocityX * scratchSpinAxis[0]
                + body.orbitFrame.angularVelocityY * scratchSpinAxis[1]
                + body.orbitFrame.angularVelocityZ * scratchSpinAxis[2];
    }

    private double computeFaceLockGateRateAlongSpin(CelestialBody body,
            CelestialBody partner,
            double fallbackOrbitalRate) {
        double relX = body.x - partner.x;
        double relY = body.y - partner.y;
        double relZ = body.z - partner.z;
        double relVx = body.vx - partner.vx;
        double relVy = body.vy - partner.vy;
        double relVz = body.vz - partner.vz;

        double radiusSq = relX * relX + relY * relY + relZ * relZ;
        if (radiusSq <= 0.0) {
            return fallbackOrbitalRate;
        }

        double mu = G * (body.mass + partner.mass);
        if (!Double.isFinite(mu) || mu <= 0.0) {
            return fallbackOrbitalRate;
        }

        double radius = Math.sqrt(radiusSq);
        double speedSq = relVx * relVx + relVy * relVy + relVz * relVz;
        double inverseSemiMajorAxis = 2.0 / radius - speedSq / mu;
        if (!Double.isFinite(inverseSemiMajorAxis) || inverseSemiMajorAxis <= 0.0) {
            return fallbackOrbitalRate;
        }

        double semiMajorAxis = 1.0 / inverseSemiMajorAxis;
        double meanMotion = Math.sqrt(mu / (semiMajorAxis * semiMajorAxis * semiMajorAxis));
        if (!Double.isFinite(meanMotion) || meanMotion <= 0.0) {
            return fallbackOrbitalRate;
        }

        body.getSpinAxis(scratchSpinAxis);
        double orbitSpinAlignment = body.orbitFrame.normalX * scratchSpinAxis[0]
                + body.orbitFrame.normalY * scratchSpinAxis[1]
                + body.orbitFrame.normalZ * scratchSpinAxis[2];
        return meanMotion * orbitSpinAlignment;
    }

    private boolean isFaceLockEligible(double rateError, double orbitalRate, boolean engaged) {
        if (!Double.isFinite(orbitalRate) || Math.abs(orbitalRate) <= 1e-12) {
            return false;
        }
        return Math.abs(rateError) <= faceLockSyncTolerance(orbitalRate, engaged);
    }

    private double faceLockSyncTolerance(double orbitalRate, boolean engaged) {
        double toleranceRatio = engaged
                ? FACE_LOCK_RELEASE_RATE_TOLERANCE
                : FACE_LOCK_ACQUIRE_RATE_TOLERANCE;
        return Math.abs(orbitalRate) * toleranceRatio;
    }

    private boolean computeFaceLockTargetAxis(CelestialBody body,
            CelestialBody partner,
            double[] outTargetAxis) {
        body.getSpinAxis(scratchSpinAxis);
        double poleX = scratchSpinAxis[0];
        double poleY = scratchSpinAxis[1];
        double poleZ = scratchSpinAxis[2];

        return GeometryUtils.projectOntoPlane(
                partner.x - body.x,
                partner.y - body.y,
                partner.z - body.z,
                poleX,
                poleY,
                poleZ,
                outTargetAxis);
    }

    private void computeEquilibriumSpin(double[] orbitalOmega, double[] out) {
        out[0] = orbitalOmega[0];
        out[1] = orbitalOmega[1];
        out[2] = orbitalOmega[2];
    }

    private void computeTidalForcing(double[] spinOmega, double[] equilibriumSpin, double[] out) {
        out[0] = spinOmega[0] - equilibriumSpin[0];
        out[1] = spinOmega[1] - equilibriumSpin[1];
        out[2] = spinOmega[2] - equilibriumSpin[2];
    }

    private enum FaceLockMode {
        ABSOLUTE,
        DYNAMIC;
    }

    private enum FaceLockSpringMode {
        LIGHT(0.01),
        STRONG(16.0);

        double value;

        FaceLockSpringMode(Double value) {
            this.value = value;
        }

        public double getValue() {
            return value;
        }
    }
}
