package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.entities.Belt;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.core.WorldCamera;
import com.gravitas.util.FormatUtils;

import java.util.List;

/**
 * Displays a floating tooltip near the cursor when hovering over a celestial
 * body.
 *
 * The tooltip shows:
 * Name, Body type
 * Mass (kg, scientific notation)
 * Radius (km)
 * Distance from Sun (AU)
 * Speed relative to Sun (km/s)
 *
 * Hover threshold: body screen radius + 8 px (so it activates even on small
 * dots).
 *
 * Does NOT pause the simulation.
 */
public class BodyTooltip {

    private static final float HOVER_EXTRA_PX = 8f;
    private static final float PAD = 8f;
    private static final float LINE_H = 17f;
    private static final float LOCK_RELATION_GAP = LINE_H * 0.45f;
    private static final Color BG_COLOR = new Color(0.08f, 0.08f, 0.10f, 0.85f);
    private static final Color LOCKED_BG_COLOR = new Color(0.08f, 0.12f, 0.10f, 0.88f);
    private static final Color TEXT_COLOR = new Color(0.95f, 0.95f, 0.95f, 1f);
    private static final Color TITLE_COLOR = new Color(0.5f, 0.85f, 1.0f, 1f);
    private static final Color LOCKED_TITLE_COLOR = new Color(0.72f, 0.98f, 0.80f, 1f);
    private static final String LOCK_RELATION_MARKER = "__lock_relation_marker__";

    private final BitmapFont font;
    private final WorldCamera camera;
    private final PhysicsEngine physics;
    private final ShapeRenderer shapeRenderer;
    private final GlyphLayout layout = new GlyphLayout();
    private List<Belt> belts = List.of();

    public BodyTooltip(FontManager fontManager, WorldCamera camera, PhysicsEngine physics,
            ShapeRenderer shapeRenderer) {
        this.font = fontManager.labelFont;
        this.camera = camera;
        this.physics = physics;
        this.shapeRenderer = shapeRenderer;
    }

    public void setBelts(List<Belt> belts) {
        this.belts = belts;
    }

    /**
     * Find the nearest body under/near the cursor and draw its tooltip.
     * Call with spriteBatch.begin() already active.
     */
    public void render(SpriteBatch batch, int screenWidth, int screenHeight) {
        int mouseX = Gdx.input.getX();
        int mouseY = Gdx.input.getY(); // top-origin (LWJGL3)

        // Convert to bottom-left origin for our worldToScreen comparisons
        int mouseYFlipped = screenHeight - mouseY;

        SimObject hovered = findHovered(mouseX, mouseYFlipped, physics.getObjects());
        boolean lockedTargetHovered = hovered != null && hovered == camera.getFollowTarget();
        String[] lines;
        if (hovered != null) {
            lines = buildLines(hovered, lockedTargetHovered);
        } else {
            Belt belt = findHoveredBelt(mouseX, mouseYFlipped);
            if (belt == null)
                return;
            lines = buildBeltLines(belt);
        }

        // Split each line into [label, value] on the tab character.
        // Title row (index 0) has no tab — it spans full width.
        String[] labels = new String[lines.length];
        String[] values = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            if (LOCK_RELATION_MARKER.equals(lines[i])) {
                labels[i] = LOCK_RELATION_MARKER;
                values[i] = null;
                continue;
            }
            int tab = lines[i].indexOf('\t');
            if (tab >= 0) {
                labels[i] = lines[i].substring(0, tab);
                values[i] = lines[i].substring(tab + 1);
            } else {
                labels[i] = lines[i];
                values[i] = null;
            }
        }

        // Measure label column width (excluding title row).
        float labelColW = 0;
        for (int i = 1; i < labels.length; i++) {
            if (LOCK_RELATION_MARKER.equals(labels[i])) {
                continue;
            }
            layout.setText(font, labels[i]);
            if (layout.width > labelColW)
                labelColW = layout.width;
        }
        float colGap = 8f;
        float valueColX = PAD + labelColW + colGap;

        // Measure value column width (superscript-aware).
        float valueColW = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] == null)
                continue;
            float w = measureValue(values[i]);
            if (w > valueColW)
                valueColW = w;
        }

        // Title width.
        layout.setText(font, labels[0]);
        float titleW = layout.width;

        float boxW = Math.max(titleW, valueColX + valueColW) + PAD * 2;
        float boxH = PAD * 2;
        for (String line : lines) {
            boxH += LOCK_RELATION_MARKER.equals(line) ? LOCK_RELATION_GAP : LINE_H;
        }

        // Position tooltip: offset from cursor, clamped to screen.
        float tx = mouseX + 14;
        float ty = mouseYFlipped + 14;
        if (tx + boxW > screenWidth - 4)
            tx = mouseX - boxW - 8;
        if (ty + boxH > screenHeight - 4)
            ty = mouseYFlipped - boxH - 8;

        // Draw background.
        batch.end();
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(lockedTargetHovered ? LOCKED_BG_COLOR : BG_COLOR);
        shapeRenderer.rect(tx, ty, boxW, boxH);
        shapeRenderer.end();
        batch.begin();

        // Draw text rows.
        float lineY = ty + boxH - PAD - LINE_H * 0.1f;
        for (int i = 0; i < lines.length; i++) {
            if (LOCK_RELATION_MARKER.equals(labels[i])) {
                lineY -= LOCK_RELATION_GAP;
                continue;
            }
            if (values[i] == null) {
                // Title or full-width row.
                font.setColor(i == 0 ? (lockedTargetHovered ? LOCKED_TITLE_COLOR : TITLE_COLOR) : TEXT_COLOR);
                font.draw(batch, labels[i], tx + PAD, lineY);
            } else {
                font.setColor(isLockRelationLabel(labels[i]) ? LOCKED_TITLE_COLOR : TEXT_COLOR);
                font.draw(batch, labels[i], tx + PAD, lineY);
                drawValue(batch, values[i], tx + PAD + labelColW + colGap, lineY);
            }
            lineY -= LINE_H;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SimObject findHovered(int mouseX, int mouseYFlipped, List<SimObject> objects) {
        boolean freeCam = camera.getMode() == WorldCamera.CameraMode.FREE_CAM;
        if (freeCam) {
            return findHovered3D(mouseX, mouseYFlipped, objects);
        }
        return findHovered2D(mouseX, mouseYFlipped, objects);
    }

    /**
     * FREE_CAM: ancestor wins over descendant when descendant is a small dot
     * (screen radius < DOT_THRESHOLD_PX) AND both have comparable screen size
     * (ratio > DOT_RATIO_MIN). Otherwise closest to camera wins.
     */
    private static final float DOT_THRESHOLD_PX = 15f;
    private static final float DOT_RATIO_MIN = 0.3f;

    private SimObject findHovered3D(int mouseX, int mouseYFlipped, List<SimObject> objects) {
        CelestialBody best = null;
        double bestDepth = Double.MAX_VALUE;
        float bestScreenR = 0;

        for (SimObject obj : objects) {
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            com.badlogic.gdx.math.Vector2 sc = camera.worldToScreen(cb.x, cb.y, cb.z);
            float screenR = camera.worldSphereRadiusToScreen(cb.radius, cb.x, cb.y, cb.z);
            float dx = mouseX - sc.x;
            float dy = mouseYFlipped - sc.y;
            if (dx * dx + dy * dy > (screenR + HOVER_EXTRA_PX) * (screenR + HOVER_EXTRA_PX))
                continue;
            double depth = camera.depthOf(cb.x, cb.y, cb.z);
            if (best == null) {
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            } else if (bestScreenR < DOT_THRESHOLD_PX && isAncestor(cb, best)
                    && bestScreenR / (screenR + 1e-6f) > DOT_RATIO_MIN) {
                // best is a small dot comparable in size to its ancestor → ancestor wins
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            } else if (screenR < DOT_THRESHOLD_PX && isAncestor(best, cb)
                    && screenR / (bestScreenR + 1e-6f) > DOT_RATIO_MIN) {
                // cb is a small dot comparable in size to its ancestor (best) → keep best
            } else if (depth < bestDepth) {
                best = cb;
                bestDepth = depth;
                bestScreenR = screenR;
            }
        }
        return best;
    }

    /**
     * TOP_VIEW: ancestor wins over descendant; otherwise nearest on screen wins.
     */
    private SimObject findHovered2D(int mouseX, int mouseYFlipped, List<SimObject> objects) {
        CelestialBody best = null;
        float bestDist = Float.MAX_VALUE;

        for (SimObject obj : objects) {
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            com.badlogic.gdx.math.Vector2 sc = camera.worldToScreen(cb.x, cb.y, cb.z);
            float screenR = camera.worldSphereRadiusToScreen(cb.radius, cb.x, cb.y, cb.z) + HOVER_EXTRA_PX;
            float dx = mouseX - sc.x;
            float dy = mouseYFlipped - sc.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > screenR)
                continue;
            if (best == null) {
                best = cb;
                bestDist = dist;
            } else if (isAncestor(cb, best)) {
                // cb is a parent of current best → cb wins
                best = cb;
                bestDist = dist;
            } else if (!isAncestor(best, cb) && dist < bestDist) {
                best = cb;
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean isAncestor(CelestialBody ancestor, CelestialBody body) {
        CelestialBody cur = body.parent;
        while (cur != null) {
            if (cur == ancestor)
                return true;
            cur = cur.parent;
        }
        return false;
    }

    private String[] buildLines(SimObject obj, boolean lockedTarget) {
        if (obj instanceof CelestialBody cb) {
            String typeName = cb.bodyType == null ? "Body" : humanizeEnumLabel(cb.bodyType.name());
            double speedKms = cb.speed() / 1000.0;

            // Collect direct moons (bodies whose parent == this body).
            java.util.List<String> moonNames = new java.util.ArrayList<>();
            for (var o : physics.getObjects()) {
                if (o instanceof CelestialBody m && m.active
                        && m.bodyType == CelestialBody.BodyType.MOON
                        && m.parent == cb) {
                    moonNames.add(m.name);
                }
            }

            // Find the root star for distance calculation.
            CelestialBody star = cb;
            while (star.parent != null)
                star = star.parent;

            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(formatTitle(cb.name, typeName, lockedTarget));
            lines.add("Mass\t" + formatSci(cb.mass) + " kg");
            lines.add("Radius\t" + String.format("%.0f km", cb.radius / 1000.0));

            // Distance from parent star (skip for the star itself).
            if (cb != star) {
                double dx = cb.x - star.x, dy = cb.y - star.y, dz = cb.z - star.z;
                double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
                lines.add("Dist.Star\t" + FormatUtils.formatDistance(distM));
            } else {
                // Star: show barycentric drift from origin in adaptive SI units.
                double driftM = Math.sqrt(cb.x * cb.x + cb.y * cb.y + cb.z * cb.z);
                lines.add("Drift\t" + FormatUtils.formatDistance(driftM));
            }

            lines.add("Speed\t" + String.format("%.2f km/s", speedKms));

            // Orbital period from vis-viva (osculant semi-major axis).
            if (cb.parent != null && cb.semiMajorAxis > 0) {
                double G = 6.674e-11;
                double dx = cb.x - cb.parent.x, dy = cb.y - cb.parent.y, dz = cb.z - cb.parent.z;
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double dvx = cb.vx - cb.parent.vx, dvy = cb.vy - cb.parent.vy, dvz = cb.vz - cb.parent.vz;
                double v2 = dvx * dvx + dvy * dvy + dvz * dvz;
                double mu = G * cb.parent.mass;
                double a_osc = 1.0 / (2.0 / r - v2 / mu); // vis-viva for a
                if (a_osc > 0) {
                    double T_sec = 2.0 * Math.PI * Math.sqrt(a_osc * a_osc * a_osc / mu);
                    lines.add("Orb.Period\t" + formatPeriod(T_sec));
                }
                lines.add("Orb.Incl.\t" + formatDegrees(cb.inclination));
            }

            // Rotation: equatorial surface speed + direction.
            if (cb.rotationPeriod != 0) {
                double absPeriod = Math.abs(cb.rotationPeriod);
                double circumference = 2.0 * Math.PI * cb.radius; // metres
                double eqSpeedKmh = (circumference / absPeriod) * 3.6; // m/s → km/h
                double degPerHour = 360.0 / (absPeriod / 3600.0); // °/h
                boolean retrograde = cb.rotationPeriod < 0;
                String dir = retrograde ? "CW" : "CCW";
                lines.add("Rotation\t" + String.format("%.1f km/h (%.2f°/h) %s", eqSpeedKmh, degPerHour, dir));
                lines.add("Rot.Period\t" + formatPeriod(absPeriod));
                lines.add("Tilt\t" + formatDegrees(cb.obliquity));
            }

            if (!moonNames.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int show = Math.min(2, moonNames.size());
                for (int i = 0; i < show; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(moonNames.get(i));
                }
                if (moonNames.size() > 2)
                    sb.append(" +").append(moonNames.size() - 2).append(" more");
                lines.add("Moons\t" + sb);
            }
            appendLockedBodyRelationLines(lines, cb);
            return lines.toArray(new String[0]);
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(lockedTarget ? obj.name + "  [LOCK]" : obj.name);
        lines.add("Speed\t" + String.format("%.2f km/s", obj.speed() / 1000.0));
        appendLockedBodyRelationLines(lines, obj);
        return lines.toArray(new String[0]);
    }

    private void appendLockedBodyRelationLines(java.util.List<String> lines, SimObject hoveredObject) {
        SimObject lockedBody = camera.getFollowTarget();
        if (lockedBody == null || !lockedBody.active || lockedBody == hoveredObject) {
            return;
        }

        lines.add(LOCK_RELATION_MARKER);
        lines.add("Lock dist\t" + FormatUtils.formatDistance(hoveredObject.distanceTo(lockedBody)));
        lines.add("Lock dV\t" + formatRelativeSpeed(hoveredObject, lockedBody));
    }

    private boolean isLockRelationLabel(String label) {
        return "Lock dist".equals(label) || "Lock dV".equals(label);
    }

    private String formatTitle(String bodyName, String typeName, boolean lockedTarget) {
        if (lockedTarget) {
            return bodyName + "  [" + typeName + "]  [LOCK]";
        }
        return bodyName + "  [" + typeName + "]";
    }

    private String humanizeEnumLabel(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] parts = text.toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    /**
     * Returns a string like "5.972×10^24" where the part after ^ is drawn
     * as a small superscript by drawValue().
     */
    private String formatSci(double v) {
        if (v == 0)
            return "0";
        int exp = (int) Math.floor(Math.log10(Math.abs(v)));
        double mantissa = v / Math.pow(10, exp);
        return String.format("%.3f\u00d710^%d", mantissa, exp);
    }

    private String formatPeriod(double seconds) {
        double absSeconds = Math.abs(seconds);
        double days = absSeconds / 86400.0;
        if (days >= 365.25) {
            return String.format("%.2f yr", days / 365.25);
        }
        if (days >= 1.0) {
            return String.format("%.2f d", days);
        }
        return String.format("%.2f h", absSeconds / 3600.0);
    }

    private String formatDegrees(double radians) {
        return String.format("%.2f°", Math.toDegrees(radians));
    }

    private String formatRelativeSpeed(SimObject object, SimObject reference) {
        double dvx = object.vx - reference.vx;
        double dvy = object.vy - reference.vy;
        double dvz = object.vz - reference.vz;
        double relativeSpeedKms = Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz) / 1000.0;
        return String.format("%.2f km/s", relativeSpeedKms);
    }

    /**
     * Measures the rendered width of a value string.
     * If the string contains '^', only the digits/minus immediately after it
     * are treated as a superscript; the rest is measured at normal scale.
     */
    private float measureValue(String s) {
        int caret = s.indexOf('^');
        if (caret < 0) {
            layout.setText(font, s);
            return layout.width;
        }
        String base = s.substring(0, caret);
        String after = s.substring(caret + 1);
        int expEnd = 0;
        while (expEnd < after.length()
                && (Character.isDigit(after.charAt(expEnd)) || after.charAt(expEnd) == '-')) {
            expEnd++;
        }
        String expStr = after.substring(0, expEnd);
        String suffix = after.substring(expEnd);

        layout.setText(font, base);
        float w = layout.width;
        font.getData().setScale(0.62f);
        layout.setText(font, expStr);
        w += layout.width;
        font.getData().setScale(1f);
        layout.setText(font, suffix);
        w += layout.width;
        return w;
    }

    /**
     * Draws a value string at (x, y). If the string contains '^', only the
     * digits/minus immediately after it are rendered as a superscript (0.55×
     * scale, shifted up). Any text after the exponent digits (e.g. " kg")
     * returns to normal scale on the baseline.
     */
    private void drawValue(SpriteBatch batch, String s, float x, float y) {
        int caret = s.indexOf('^');
        if (caret < 0) {
            font.draw(batch, s, x, y);
            return;
        }
        String base = s.substring(0, caret);
        String after = s.substring(caret + 1);
        int expEnd = 0;
        while (expEnd < after.length()
                && (Character.isDigit(after.charAt(expEnd)) || after.charAt(expEnd) == '-')) {
            expEnd++;
        }
        String expStr = after.substring(0, expEnd);
        String suffix = after.substring(expEnd);

        // Base text (e.g. "5.972×10")
        font.draw(batch, base, x, y);
        layout.setText(font, base);
        float cx = x + layout.width;

        // Superscript exponent (e.g. "24")
        font.getData().setScale(0.62f);
        font.draw(batch, expStr, cx, y + LINE_H * 0.20f);
        layout.setText(font, expStr);
        cx += layout.width;
        font.getData().setScale(1f);

        // Suffix at normal scale (e.g. " kg")
        if (!suffix.isEmpty()) {
            font.draw(batch, suffix, cx, y);
        }
    }

    private Belt findHoveredBelt(int mouseX, int mouseYFlipped) {
        double[] worldPos = camera.screenToWorld(mouseX, mouseYFlipped);
        double mx = worldPos[0];
        double my = worldPos[1];

        for (Belt belt : belts) {
            // Find parent body position.
            double px = 0, py = 0;
            for (SimObject obj : physics.getObjects()) {
                if (obj.name.equals(belt.parentName)) {
                    px = obj.x;
                    py = obj.y;
                    break;
                }
            }
            double dx = mx - px;
            double dy = my - py;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist >= belt.innerRadius && dist <= belt.outerRadius) {
                return belt;
            }
        }
        return null;
    }

    private String[] buildBeltLines(Belt belt) {
        String displayName;
        if (belt.name.equals("AsteroidBelt"))
            displayName = "Main Asteroid Belt";
        else if (belt.name.equals("KuiperBelt"))
            displayName = "Kuiper Belt";
        else
            displayName = belt.name;

        double innerAU = belt.innerRadius / FormatUtils.AU;
        double outerAU = belt.outerRadius / FormatUtils.AU;
        double widthAU = outerAU - innerAU;
        return new String[] {
                displayName + "  [Belt]",
                "Inner edge\t" + String.format("%.2f AU", innerAU),
                "Outer edge\t" + String.format("%.2f AU", outerAU),
                "Width\t" + String.format("%.2f AU", widthAU),
        };
    }
}
