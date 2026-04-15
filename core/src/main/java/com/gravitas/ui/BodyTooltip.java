package com.gravitas.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.gravitas.entities.BeltData;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.physics.PhysicsEngine;
import com.gravitas.rendering.FontManager;
import com.gravitas.rendering.WorldCamera;

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
    private static final Color BG_COLOR = new Color(0.08f, 0.08f, 0.10f, 0.85f);
    private static final Color TEXT_COLOR = new Color(0.95f, 0.95f, 0.95f, 1f);
    private static final Color TITLE_COLOR = new Color(0.5f, 0.85f, 1.0f, 1f);

    private static final double AU = 1.496e11;

    private final BitmapFont font;
    private final WorldCamera camera;
    private final PhysicsEngine physics;
    private final ShapeRenderer shapeRenderer;
    private final GlyphLayout layout = new GlyphLayout();
    private List<BeltData> belts = List.of();

    public BodyTooltip(FontManager fontManager, WorldCamera camera, PhysicsEngine physics,
            ShapeRenderer shapeRenderer) {
        this.font = fontManager.labelFont;
        this.camera = camera;
        this.physics = physics;
        this.shapeRenderer = shapeRenderer;
    }

    public void setBelts(List<BeltData> belts) {
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
        String[] lines;
        if (hovered != null) {
            lines = buildLines(hovered);
        } else {
            BeltData belt = findHoveredBelt(mouseX, mouseYFlipped);
            if (belt == null)
                return;
            lines = buildBeltLines(belt);
        }

        // Split each line into [label, value] on the tab character.
        // Title row (index 0) has no tab — it spans full width.
        String[] labels = new String[lines.length];
        String[] values = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
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
        float boxH = lines.length * LINE_H + PAD * 2;

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
        shapeRenderer.setColor(BG_COLOR);
        shapeRenderer.rect(tx, ty, boxW, boxH);
        shapeRenderer.end();
        batch.begin();

        // Draw text rows.
        float lineY = ty + boxH - PAD - LINE_H * 0.1f;
        for (int i = 0; i < lines.length; i++) {
            if (values[i] == null) {
                // Title or full-width row.
                font.setColor(i == 0 ? TITLE_COLOR : TEXT_COLOR);
                font.draw(batch, labels[i], tx + PAD, lineY);
            } else {
                font.setColor(TEXT_COLOR);
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
        CelestialBody best = null;
        float bestDist = Float.MAX_VALUE;

        for (SimObject obj : objects) {
            if (!obj.active || !(obj instanceof CelestialBody cb))
                continue;
            com.badlogic.gdx.math.Vector2 sc = camera.worldToScreen(cb.x, cb.y);
            float screenR = camera.worldRadiusToScreen(cb.radius) + HOVER_EXTRA_PX;
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

    private String[] buildLines(SimObject obj) {
        if (obj instanceof CelestialBody cb) {
            String typeName = cb.bodyType == null ? "Body" : capitalize(cb.bodyType.name());
            double distAU = Math.sqrt(cb.x * cb.x + cb.y * cb.y) / AU;
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

            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(cb.name + "  [" + typeName + "]");
            lines.add("Mass\t" + formatSci(cb.mass) + " kg");
            lines.add("Radius\t" + String.format("%.0f km", cb.radius / 1000.0));
            lines.add("Dist/Sun\t" + String.format("%.4f AU", distAU));
            lines.add("Speed\t" + String.format("%.2f km/s", speedKms));
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
            return lines.toArray(new String[0]);
        }
        return new String[] { obj.name, "Speed\t" + String.format("%.2f km/s", obj.speed() / 1000.0) };
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

    private BeltData findHoveredBelt(int mouseX, int mouseYFlipped) {
        double[] worldPos = camera.screenToWorld(mouseX, mouseYFlipped);
        double mx = worldPos[0];
        double my = worldPos[1];

        for (BeltData belt : belts) {
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

    private String[] buildBeltLines(BeltData belt) {
        String displayName;
        if (belt.name.equals("AsteroidBelt"))
            displayName = "Main Asteroid Belt";
        else if (belt.name.equals("KuiperBelt"))
            displayName = "Kuiper Belt";
        else
            displayName = belt.name;

        double innerAU = belt.innerRadius / AU;
        double outerAU = belt.outerRadius / AU;
        double widthAU = outerAU - innerAU;
        return new String[] {
                displayName + "  [Belt]",
                "Inner edge\t" + String.format("%.2f AU", innerAU),
                "Outer edge\t" + String.format("%.2f AU", outerAU),
                "Width\t" + String.format("%.2f AU", widthAU),
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
    }
}
