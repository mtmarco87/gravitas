package com.gravitas.ui.settings;

import java.util.Locale;

public final class WarpPresets {

    private static final double[] VALUES = {
            1, 10, 1_000, 10_000, 100_000, 500_000,
            1_000_000, 10_000_000, 100_000_000, 1_000_000_000
    };

    private static final String[] LABELS = {
            "1x [1]", "10x [2]", "1000x [3]", "10000x [4]", "100000x [5]",
            "500000x [6]", "1000000x [7]", "10000000x [8]", "100000000x [9]", "1000000000x [0]"
    };

    private WarpPresets() {
    }

    public static double get(int index) {
        return VALUES[index];
    }

    public static int size() {
        return VALUES.length;
    }

    public static int nearestIndex(double warp) {
        double safeWarp = Math.max(1.0, warp);
        double warpLog10 = Math.log10(safeWarp);
        int bestIndex = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < VALUES.length; i++) {
            double distance = Math.abs(warpLog10 - Math.log10(VALUES[i]));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    public static String formatPreset(double warp) {
        return LABELS[nearestIndex(warp)];
    }

    public static String formatDisplayLabel(double warp) {
        int presetIndex = nearestIndex(warp);
        if (Math.abs(warp - VALUES[presetIndex]) < 1e-6) {
            return LABELS[presetIndex];
        }
        return formatCustomValue(warp) + "x";
    }

    private static String formatCustomValue(double warp) {
        if (Math.abs(warp - Math.rint(warp)) < 1e-6) {
            return String.format(Locale.US, "%.0f", warp);
        }
        if (warp >= 1000.0) {
            return String.format(Locale.US, "%.0f", warp);
        }
        if (warp >= 100.0) {
            return String.format(Locale.US, "%.1f", warp);
        }
        if (warp >= 10.0) {
            return String.format(Locale.US, "%.2f", warp);
        }
        return String.format(Locale.US, "%.3f", warp);
    }
}