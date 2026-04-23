package com.gravitas.util;

public final class OrbitUtils {

    private static final double J2000_ECLIPTIC_OBLIQUITY = Math.toRadians(23.43929111);
    private static final double COS_J2000_ECLIPTIC_OBLIQUITY = Math.cos(J2000_ECLIPTIC_OBLIQUITY);
    private static final double SIN_J2000_ECLIPTIC_OBLIQUITY = Math.sin(J2000_ECLIPTIC_OBLIQUITY);

    /** Prevents instantiation of this static utility holder. */
    private OrbitUtils() {
    }

    /**
     * Converts a J2000 right-ascension/declination pole into world-space ecliptic
     * coordinates.
     */
    public static void resolveAbsoluteSpinAxis(double rightAscension,
            double declination,
            double[] outAxis) {
        double cosDec = Math.cos(declination);
        double eqX = cosDec * Math.cos(rightAscension);
        double eqY = cosDec * Math.sin(rightAscension);
        double eqZ = Math.sin(declination);

        outAxis[0] = eqX;
        outAxis[1] = COS_J2000_ECLIPTIC_OBLIQUITY * eqY + SIN_J2000_ECLIPTIC_OBLIQUITY * eqZ;
        outAxis[2] = -SIN_J2000_ECLIPTIC_OBLIQUITY * eqY + COS_J2000_ECLIPTIC_OBLIQUITY * eqZ;
    }

    /**
     * Resolves an axis authored as tilt plus azimuth relative to the orbital plane.
     */
    public static boolean resolveOrbitRelativeAxis(double[] orbitNormal,
            double tilt,
            double azimuth,
            double[] referenceAxis,
            double[] tangentAxis,
            double[] outAxis) {
        if (!computeOrbitRelativeBasis(orbitNormal, referenceAxis, tangentAxis)) {
            return false;
        }

        double cosAzimuth = Math.cos(azimuth);
        double sinAzimuth = Math.sin(azimuth);
        double planeX = cosAzimuth * referenceAxis[0] + sinAzimuth * tangentAxis[0];
        double planeY = cosAzimuth * referenceAxis[1] + sinAzimuth * tangentAxis[1];
        double planeZ = cosAzimuth * referenceAxis[2] + sinAzimuth * tangentAxis[2];

        double cosTilt = Math.cos(tilt);
        double sinTilt = Math.sin(tilt);
        outAxis[0] = cosTilt * orbitNormal[0] + sinTilt * planeX;
        outAxis[1] = cosTilt * orbitNormal[1] + sinTilt * planeY;
        outAxis[2] = cosTilt * orbitNormal[2] + sinTilt * planeZ;
        return true;
    }

    /**
     * Resolves the inverse orbit-relative authoring angles from a world-space
     * spin axis and orbit normal.
     *
     * outTiltAzimuth[0] = tilt
     * outTiltAzimuth[1] = azimuth
     */
    public static boolean resolveOrbitRelativeAngles(double[] orbitNormal,
            double[] spinAxis,
            double[] referenceAxis,
            double[] tangentAxis,
            double[] outTiltAzimuth) {
        if (!computeOrbitRelativeBasis(orbitNormal, referenceAxis, tangentAxis)) {
            return false;
        }

        double dot = GeometryUtils.clamp(
                spinAxis[0] * orbitNormal[0]
                        + spinAxis[1] * orbitNormal[1]
                        + spinAxis[2] * orbitNormal[2],
                -1.0,
                1.0);
        double tilt = Math.acos(dot);

        double projX = spinAxis[0] - dot * orbitNormal[0];
        double projY = spinAxis[1] - dot * orbitNormal[1];
        double projZ = spinAxis[2] - dot * orbitNormal[2];
        double[] projectedAxis = new double[3];
        if (!GeometryUtils.normalize(projX, projY, projZ, projectedAxis)) {
            outTiltAzimuth[0] = tilt;
            outTiltAzimuth[1] = 0.0;
            return true;
        }
        double cosAzimuth = GeometryUtils.clamp(
                projectedAxis[0] * referenceAxis[0]
                        + projectedAxis[1] * referenceAxis[1]
                        + projectedAxis[2] * referenceAxis[2],
                -1.0,
                1.0);
        double sinAzimuth = projectedAxis[0] * tangentAxis[0]
                + projectedAxis[1] * tangentAxis[1]
                + projectedAxis[2] * tangentAxis[2];
        double azimuth = Math.atan2(sinAzimuth, cosAzimuth);
        outTiltAzimuth[0] = tilt;
        outTiltAzimuth[1] = azimuth;
        return true;
    }

    /** Builds an orbital-plane basis from the current orbit normal. */
    public static boolean computeOrbitRelativeBasis(double[] orbitNormal,
            double[] referenceAxis,
            double[] tangentAxis) {
        if (!GeometryUtils.projectOntoPlane(1.0, 0.0, 0.0,
                orbitNormal[0], orbitNormal[1], orbitNormal[2], referenceAxis)
                && !GeometryUtils.projectOntoPlane(0.0, 1.0, 0.0,
                        orbitNormal[0], orbitNormal[1], orbitNormal[2], referenceAxis)) {
            return false;
        }

        tangentAxis[0] = orbitNormal[1] * referenceAxis[2] - orbitNormal[2] * referenceAxis[1];
        tangentAxis[1] = orbitNormal[2] * referenceAxis[0] - orbitNormal[0] * referenceAxis[2];
        tangentAxis[2] = orbitNormal[0] * referenceAxis[1] - orbitNormal[1] * referenceAxis[0];
        return GeometryUtils.normalize(tangentAxis[0], tangentAxis[1], tangentAxis[2], tangentAxis);
    }

    /**
     * Computes the load-time reference orbit normal from classical orbital
     * elements.
     */
    public static void computeReferenceOrbitNormal(double semiMajorAxis,
            double inclination,
            double longitudeOfAscendingNode,
            double[] out) {
        if (semiMajorAxis <= 0.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 1.0;
            return;
        }

        double sinInc = Math.sin(inclination);
        double cosInc = Math.cos(inclination);
        double sinOmega = Math.sin(longitudeOfAscendingNode);
        double cosOmega = Math.cos(longitudeOfAscendingNode);
        out[0] = sinInc * sinOmega;
        out[1] = -sinInc * cosOmega;
        out[2] = cosInc;
    }

    /**
     * Computes instantaneous orbital normal and angular velocity from relative
     * state vectors.
     */
    public static boolean computeOrbitFrame(double relX,
            double relY,
            double relZ,
            double relVx,
            double relVy,
            double relVz,
            double[] outNormal,
            double[] outAngularVelocity) {
        double radiusSq = GeometryUtils.lengthSq(relX, relY, relZ);
        if (radiusSq <= 1e-18) {
            return false;
        }

        double hx = relY * relVz - relZ * relVy;
        double hy = relZ * relVx - relX * relVz;
        double hz = relX * relVy - relY * relVx;
        if (!GeometryUtils.normalize(hx, hy, hz, outNormal)) {
            return false;
        }
        outAngularVelocity[0] = hx / radiusSq;
        outAngularVelocity[1] = hy / radiusSq;
        outAngularVelocity[2] = hz / radiusSq;
        return true;
    }
}
