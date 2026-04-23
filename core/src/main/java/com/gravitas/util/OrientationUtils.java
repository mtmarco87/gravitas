package com.gravitas.util;

import com.badlogic.gdx.math.Quaternion;

/** Shared quaternion helpers for rigid-body orientation math. */
public final class OrientationUtils {

    private static final double VECTOR_EPSILON = 1e-12;

    private OrientationUtils() {
    }

    public static void setFromAxes(Quaternion out,
            double rightX,
            double rightY,
            double rightZ,
            double spinX,
            double spinY,
            double spinZ,
            double primeX,
            double primeY,
            double primeZ) {
        double m00 = rightX;
        double m01 = spinX;
        double m02 = primeX;
        double m10 = rightY;
        double m11 = spinY;
        double m12 = primeY;
        double m20 = rightZ;
        double m21 = spinZ;
        double m22 = primeZ;

        double trace = m00 + m11 + m22;
        double qx;
        double qy;
        double qz;
        double qw;
        if (trace > 0.0) {
            double scale = Math.sqrt(trace + 1.0) * 2.0;
            qw = 0.25 * scale;
            qx = (m21 - m12) / scale;
            qy = (m02 - m20) / scale;
            qz = (m10 - m01) / scale;
        } else if (m00 > m11 && m00 > m22) {
            double scale = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
            qw = (m21 - m12) / scale;
            qx = 0.25 * scale;
            qy = (m01 + m10) / scale;
            qz = (m02 + m20) / scale;
        } else if (m11 > m22) {
            double scale = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
            qw = (m02 - m20) / scale;
            qx = (m01 + m10) / scale;
            qy = 0.25 * scale;
            qz = (m12 + m21) / scale;
        } else {
            double scale = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
            qw = (m10 - m01) / scale;
            qx = (m02 + m20) / scale;
            qy = (m12 + m21) / scale;
            qz = 0.25 * scale;
        }

        double len = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (len <= VECTOR_EPSILON) {
            out.idt();
            return;
        }

        out.set((float) (qx / len),
                (float) (qy / len),
                (float) (qz / len),
                (float) (qw / len));
    }

    public static void rotateLocalUnitAxis(Quaternion orientation,
            double localX,
            double localY,
            double localZ,
            double[] out) {
        double qx = orientation.x;
        double qy = orientation.y;
        double qz = orientation.z;
        double qw = orientation.w;

        double tx = 2.0 * (qy * localZ - qz * localY);
        double ty = 2.0 * (qz * localX - qx * localZ);
        double tz = 2.0 * (qx * localY - qy * localX);

        out[0] = localX + qw * tx + (qy * tz - qz * ty);
        out[1] = localY + qw * ty + (qz * tx - qx * tz);
        out[2] = localZ + qw * tz + (qx * ty - qy * tx);

        if (!GeometryUtils.normalize(out[0], out[1], out[2], out)) {
            out[0] = localX;
            out[1] = localY;
            out[2] = localZ;
        }
    }

    public static boolean resolveCanonicalGuideAxis(double poleX,
            double poleY,
            double poleZ,
            double[] out) {
        return GeometryUtils.projectOntoPlane(0.0, 0.0, 1.0, poleX, poleY, poleZ, out)
                || GeometryUtils.projectOntoPlane(1.0, 0.0, 0.0, poleX, poleY, poleZ, out)
                || GeometryUtils.projectOntoPlane(0.0, 1.0, 0.0, poleX, poleY, poleZ, out);
    }

    /**
     * Transports the prime meridian from an old spin axis to a new one with rigid
     * frame continuity.
     *
     * @return true when a non-trivial axis update was applied, false when axes are
     *         effectively unchanged
     */
    public static boolean transportPrimeMeridianForAxisChange(double oldAxisX,
            double oldAxisY,
            double oldAxisZ,
            double oldRightX,
            double oldRightY,
            double oldRightZ,
            double oldPrimeX,
            double oldPrimeY,
            double oldPrimeZ,
            double newAxisX,
            double newAxisY,
            double newAxisZ,
            double[] outPrime) {
        double dot = GeometryUtils.clamp(
                oldAxisX * newAxisX + oldAxisY * newAxisY + oldAxisZ * newAxisZ,
                -1.0,
                1.0);
        if (dot >= 1.0 - VECTOR_EPSILON) {
            return false;
        }

        if (dot <= -1.0 + VECTOR_EPSILON) {
            rotateAroundAxis(
                    oldPrimeX,
                    oldPrimeY,
                    oldPrimeZ,
                    oldRightX,
                    oldRightY,
                    oldRightZ,
                    Math.PI,
                    outPrime);
            return true;
        }

        double transportAxisX = oldAxisY * newAxisZ - oldAxisZ * newAxisY;
        double transportAxisY = oldAxisZ * newAxisX - oldAxisX * newAxisZ;
        double transportAxisZ = oldAxisX * newAxisY - oldAxisY * newAxisX;
        if (!GeometryUtils.normalize(transportAxisX, transportAxisY, transportAxisZ, outPrime)) {
            return false;
        }
        rotateAroundAxis(
                oldPrimeX,
                oldPrimeY,
                oldPrimeZ,
                outPrime[0],
                outPrime[1],
                outPrime[2],
                Math.acos(dot),
                outPrime);
        return true;
    }

    public static void buildOrthonormalFrame(double axisX,
            double axisY,
            double axisZ,
            double primeX,
            double primeY,
            double primeZ,
            double[] outRight,
            double[] outPrime) {
        double rightX = axisY * primeZ - axisZ * primeY;
        double rightY = axisZ * primeX - axisX * primeZ;
        double rightZ = axisX * primeY - axisY * primeX;
        if (!GeometryUtils.normalize(rightX, rightY, rightZ, outRight)) {
            resolveCanonicalGuideAxis(axisX, axisY, axisZ, outPrime);
            rightX = axisY * outPrime[2] - axisZ * outPrime[1];
            rightY = axisZ * outPrime[0] - axisX * outPrime[2];
            rightZ = axisX * outPrime[1] - axisY * outPrime[0];
            GeometryUtils.normalize(rightX, rightY, rightZ, outRight);
        }

        outPrime[0] = outRight[1] * axisZ - outRight[2] * axisY;
        outPrime[1] = outRight[2] * axisX - outRight[0] * axisZ;
        outPrime[2] = outRight[0] * axisY - outRight[1] * axisX;
    }

    public static void rotateAroundAxis(double vectorX,
            double vectorY,
            double vectorZ,
            double axisX,
            double axisY,
            double axisZ,
            double angle,
            double[] out) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double dot = vectorX * axisX + vectorY * axisY + vectorZ * axisZ;

        out[0] = vectorX * cos
                + (axisY * vectorZ - axisZ * vectorY) * sin
                + axisX * dot * (1.0 - cos);
        out[1] = vectorY * cos
                + (axisZ * vectorX - axisX * vectorZ) * sin
                + axisY * dot * (1.0 - cos);
        out[2] = vectorZ * cos
                + (axisX * vectorY - axisY * vectorX) * sin
                + axisZ * dot * (1.0 - cos);

        GeometryUtils.normalize(out[0], out[1], out[2], out);
    }

    public static double computeCanonicalRotationAngle(double poleX,
            double poleY,
            double poleZ,
            double primeX,
            double primeY,
            double primeZ,
            double[] scratchGuideAxis) {
        if (!resolveCanonicalGuideAxis(poleX, poleY, poleZ, scratchGuideAxis)) {
            return 0.0;
        }
        return GeometryUtils.signedAngleAroundAxis(
                poleX,
                poleY,
                poleZ,
                scratchGuideAxis[0],
                scratchGuideAxis[1],
                scratchGuideAxis[2],
                primeX,
                primeY,
                primeZ);
    }
}
