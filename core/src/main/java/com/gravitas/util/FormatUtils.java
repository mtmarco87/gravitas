package com.gravitas.util;

/**
 * Shared formatting helpers for distances and physical quantities.
 */
public final class FormatUtils {

    /** IAU definition of the astronomical unit (metres). */
    public static final double AU = 1.496e11;

    /** One light-year in metres (IAU: c × Julian year). */
    public static final double LY = 9.4607304725808e15;

    private FormatUtils() {
    }

    /**
     * Formats a distance in metres using adaptive prefixes:
     * m → km → Mm → Gm → AU → ly → kly → Mly → Bly.
     */
    public static String formatDistance(double metres) {
        double abs = Math.abs(metres);
        if (abs >= LY * 1e9)
            return String.format("%.2f Bly", metres / (LY * 1e9));
        if (abs >= LY * 1e6)
            return String.format("%.2f Mly", metres / (LY * 1e6));
        if (abs >= LY * 1e3)
            return String.format("%.2f kly", metres / (LY * 1e3));
        if (abs >= LY * 0.1)
            return String.format("%.4f ly", metres / LY);
        if (abs >= AU * 0.01)
            return String.format("%.4f AU", metres / AU);
        if (abs >= 1e9)
            return String.format("%.2f Gm", metres / 1e9);
        if (abs >= 1e6)
            return String.format("%.2f Mm", metres / 1e6);
        if (abs >= 1e3)
            return String.format("%.2f km", metres / 1e3);
        return String.format("%.2f m", metres);
    }
}
