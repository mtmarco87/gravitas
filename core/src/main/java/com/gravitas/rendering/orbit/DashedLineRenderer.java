package com.gravitas.rendering.orbit;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

/**
 * GPU-driven dashed line renderer.
 * <p>
 * Usage pattern each frame:
 * 
 * <pre>
 * dashedLineRenderer.begin(projectionMatrix);
 * dashedLineRenderer.setColor(r, g, b, a);
 * dashedLineRenderer.resetDistance(); // per orbit
 * dashedLineRenderer.line(sx0, sy0, sx1, sy1); // repeats
 * dashedLineRenderer.end();
 * </pre>
 * <p>
 * The dash/gap pattern is resolved entirely on the GPU fragment shader via
 * {@code discard} — CPU cost is identical to drawing solid lines.
 */
public class DashedLineRenderer implements Disposable {

    private static final float DASH_SIZE_PX = 8f;
    private static final float GAP_SIZE_PX = 5f;
    private static final float EDGE_FEATHER_PX = 0.85f;
    private static final float DASH_CYCLE_PX = DASH_SIZE_PX + GAP_SIZE_PX;

    /** Floats per vertex: x, y, color(packed), dist = 4 */
    private static final int VERTEX_SIZE = 4;
    /** Max vertices in the buffer before an automatic flush. */
    private static final int MAX_VERTICES = 8192;

    private final ShaderProgram shader;
    private final Mesh mesh;
    private final float[] vertices = new float[MAX_VERTICES * VERTEX_SIZE];
    private int vertexCount = 0;

    private float colorPacked = Color.WHITE.toFloatBits();
    private float phaseAcc = 0f;
    private boolean drawing = false;

    private final Matrix4 projectionMatrix = new Matrix4();

    public DashedLineRenderer() {
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(
                Gdx.files.internal("shaders/orbit/dash_line.vert"),
                Gdx.files.internal("shaders/orbit/dash_line.frag"));
        if (!shader.isCompiled()) {
            Gdx.app.error("DashedLineRenderer", "Shader compile failed:\n" + shader.getLog());
        }

        mesh = new Mesh(false, MAX_VERTICES, 0,
                new VertexAttribute(Usage.Position, 2, "a_position"),
                new VertexAttribute(Usage.ColorPacked, 4, "a_color"),
                new VertexAttribute(Usage.Generic, 1, "a_dist"));
    }

    // ---------------------------------------------------------------------
    // Frame lifecycle
    // ---------------------------------------------------------------------

    public void begin(Matrix4 projection) {
        if (drawing)
            throw new IllegalStateException("DashedLineRenderer.begin() called while already drawing");
        drawing = true;
        vertexCount = 0;
        projectionMatrix.set(projection);
    }

    public void end() {
        if (!drawing)
            throw new IllegalStateException("DashedLineRenderer.end() called without begin()");
        flush();
        drawing = false;
    }

    // ---------------------------------------------------------------------
    // Per-orbit state
    // ---------------------------------------------------------------------

    /** Reset the accumulated dash distance — call once before each orbit. */
    public void resetDistance() {
        phaseAcc = 0f;
    }

    public void setColor(float r, float g, float b, float a) {
        colorPacked = Color.toFloatBits(r, g, b, a);
    }

    // ---------------------------------------------------------------------
    // Segment submission
    // ---------------------------------------------------------------------

    /**
     * Submit one line segment in screen-space. The accumulated distance is
     * advanced automatically by the screen-pixel length of the segment.
     */
    public void line(float x0, float y0, float x1, float y1) {
        if (vertexCount + 2 > MAX_VERTICES) {
            flush();
        }

        float dx = x1 - x0;
        float dy = y1 - y0;
        float segLen = (float) Math.sqrt(dx * dx + dy * dy);
        if (segLen <= 0f) {
            return;
        }

        // Store per-segment local phase instead of an ever-growing absolute
        // orbit distance. This avoids float precision loss on very large orbits
        // where absolute pixel distance can grow enough to collapse the dash.
        float startDist = phaseAcc;
        float endDist = startDist + segLen;

        int base = vertexCount * VERTEX_SIZE;
        vertices[base] = x0;
        vertices[base + 1] = y0;
        vertices[base + 2] = colorPacked;
        vertices[base + 3] = startDist;
        vertices[base + 4] = x1;
        vertices[base + 5] = y1;
        vertices[base + 6] = colorPacked;
        vertices[base + 7] = endDist;

        vertexCount += 2;
        phaseAcc = endDist % DASH_CYCLE_PX;
    }

    // ---------------------------------------------------------------------
    // Flush / GPU upload
    // ---------------------------------------------------------------------

    private void flush() {
        if (vertexCount == 0)
            return;

        mesh.setVertices(vertices, 0, vertexCount * VERTEX_SIZE);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setUniformMatrix("u_projTrans", projectionMatrix);
        shader.setUniformf("u_dashSize", DASH_SIZE_PX);
        shader.setUniformf("u_gapSize", GAP_SIZE_PX);
        shader.setUniformf("u_feather", EDGE_FEATHER_PX);

        mesh.render(shader, GL20.GL_LINES, 0, vertexCount);

        vertexCount = 0;
    }

    // ---------------------------------------------------------------------
    // Disposable
    // ---------------------------------------------------------------------

    @Override
    public void dispose() {
        shader.dispose();
        mesh.dispose();
    }
}
