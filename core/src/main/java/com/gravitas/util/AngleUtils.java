package com.gravitas.util;

/** Shared helpers for wrapping angular phases into a positive interval. */
public final class AngleUtils {

    private AngleUtils() {
    }

    public static double wrapAngle(double angle, double wrap) {
        double wrapped = angle % wrap;
        return wrapped < 0.0 ? wrapped + wrap : wrapped;
    }
}