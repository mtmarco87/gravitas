package com.gravitas.util;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Shared scalar and vector helpers for small 2D/3D geometry operations. */
public final class GeometryUtils {

    /** Prevents instantiation of this static utility holder. */
    private GeometryUtils() {
    }

    /** Clamps a scalar into the inclusive [min, max] interval. */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Returns the squared Euclidean length of a 2D vector. */
    public static double lengthSq(double x, double y) {
        return x * x + y * y;
    }

    /** Returns the squared Euclidean length of a 3D vector. */
    public static double lengthSq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    /** Returns the squared Euclidean distance between two 2D points. */
    public static double distanceSq(double ax, double ay, double bx, double by) {
        return lengthSq(bx - ax, by - ay);
    }

    /** Returns the squared Euclidean distance between two 3D points. */
    public static double distanceSq(double ax, double ay, double az,
            double bx, double by, double bz) {
        return lengthSq(bx - ax, by - ay, bz - az);
    }

    /** Returns the squared distance from a 2D point to a 2D line segment. */
    public static float pointToSegmentDistSq(float px, float py,
            float ax, float ay, float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float lenSq = (float) lengthSq(abx, aby);
        if (lenSq <= 1e-6f) {
            return (float) distanceSq(px, py, ax, ay);
        }

        float t = ((px - ax) * abx + (py - ay) * aby) / lenSq;
        t = (float) clamp(t, 0.0, 1.0);
        float cx = ax + t * abx;
        float cy = ay + t * aby;
        return (float) distanceSq(px, py, cx, cy);
    }

    /** Normalizes a 3D vector. Returns false for near-zero vectors. */
    public static boolean normalize(double x, double y, double z, double[] out) {
        double lenSq = lengthSq(x, y, z);
        if (lenSq <= 1e-24) {
            return false;
        }
        double invLen = 1.0 / Math.sqrt(lenSq);
        out[0] = x * invLen;
        out[1] = y * invLen;
        out[2] = z * invLen;
        return true;
    }

    /** Normalizes a 3D vector into a float destination. Returns false for near-zero vectors. */
    public static boolean normalize(double x, double y, double z, float[] out) {
        double lenSq = lengthSq(x, y, z);
        if (lenSq <= 1e-24) {
            return false;
        }
        double invLen = 1.0 / Math.sqrt(lenSq);
        out[0] = (float) (x * invLen);
        out[1] = (float) (y * invLen);
        out[2] = (float) (z * invLen);
        return true;
    }

    /** Normalizes a 3D vector or writes a provided fallback direction. */
    public static void normalizeOrDefault(double x,
            double y,
            double z,
            double fallbackX,
            double fallbackY,
            double fallbackZ,
            double[] out) {
        if (!normalize(x, y, z, out)) {
            out[0] = fallbackX;
            out[1] = fallbackY;
            out[2] = fallbackZ;
        }
    }

    /** Normalizes a 3D vector with canonical +Z fallback. */
    public static void normalizeOrCanonicalZ(double x, double y, double z, double[] out) {
        normalizeOrDefault(x, y, z, 0.0, 0.0, 1.0, out);
    }

    /**
     * Finds the nearest candidate to a world-space point among elements that pass
     * the provided predicate.
     */
    public static <T> T findNearest(Iterable<T> candidates,
            Predicate<T> predicate,
            ToDoubleFunction<T> xGetter,
            ToDoubleFunction<T> yGetter,
            ToDoubleFunction<T> zGetter,
            double x,
            double y,
            double z) {
        T nearest = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (T candidate : candidates) {
            if (!predicate.test(candidate)) {
                continue;
            }

            double distSq = distanceSq(x, y, z,
                    xGetter.applyAsDouble(candidate),
                    yGetter.applyAsDouble(candidate),
                    zGetter.applyAsDouble(candidate));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /**
     * Projects a vector onto the plane orthogonal to the given normal and
     * normalizes the result.
     */
    public static boolean projectOntoPlane(double vectorX,
            double vectorY,
            double vectorZ,
            double normalX,
            double normalY,
            double normalZ,
            double[] out) {
        double normalLenSq = lengthSq(normalX, normalY, normalZ);
        if (normalLenSq <= 1e-18) {
            return false;
        }

        double parallel = (vectorX * normalX + vectorY * normalY + vectorZ * normalZ) / normalLenSq;
        double projectedX = vectorX - parallel * normalX;
        double projectedY = vectorY - parallel * normalY;
        double projectedZ = vectorZ - parallel * normalZ;
        if (!normalize(projectedX, projectedY, projectedZ, out)) {
            return false;
        }
        return true;
    }

    /**
     * Measures the signed angle from one vector to another around a normalized
     * axis.
     */
    public static double signedAngleAroundAxis(double axisX,
            double axisY,
            double axisZ,
            double fromX,
            double fromY,
            double fromZ,
            double toX,
            double toY,
            double toZ) {
        double crossX = fromY * toZ - fromZ * toY;
        double crossY = fromZ * toX - fromX * toZ;
        double crossZ = fromX * toY - fromY * toX;
        double sin = axisX * crossX + axisY * crossY + axisZ * crossZ;
        double cos = fromX * toX + fromY * toY + fromZ * toZ;
        return Math.atan2(sin, cos);
    }
}
