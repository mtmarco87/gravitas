package com.gravitas.util;

/** Shared helpers for wrapping angular phases. */
public final class AngleUtils {

    /** Prevents instantiation of this static utility holder. */
    private AngleUtils() {
    }

    /** Wraps an angle into the positive [0, wrap) interval. */
    public static double wrapAngle(double angle, double wrap) {
        double wrapped = angle % wrap;
        return wrapped < 0.0 ? wrapped + wrap : wrapped;
    }

    /** Wraps an angle into the signed [-pi, pi] interval. */
    public static double wrapSignedAngle(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    /** Reconstructs the equivalent wrapped angle nearest to a reference value. */
    public static double unwrapNearReference(double referenceAngle, double wrappedAngle) {
        return referenceAngle + wrapSignedAngle(wrappedAngle - referenceAngle);
    }
}