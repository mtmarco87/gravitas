package com.gravitas.rendering;

import com.badlogic.gdx.math.Vector2;

/**
 * Circular buffer that stores the recent world-space positions of a SimObject
 * for rendering as an orbit trail.
 *
 * - Fixed-size ring buffer: no GC pressure during simulation.
 * - Positions stored as double pairs (wx, wy) for precision.
 * - Renderer samples the buffer and converts each point to screen space.
 */
public class OrbitTrail {

    /**
     * Maximum number of trail points per object. ~5 min at 60s physics step = 5
     * points.
     */
    public static final int DEFAULT_CAPACITY = 1024;

    private final double[] wx;
    private final double[] wy;
    private int head; // index of the next write slot
    private int size; // current number of valid points
    private final int capacity;

    public OrbitTrail() {
        this(DEFAULT_CAPACITY);
    }

    public OrbitTrail(int capacity) {
        this.capacity = capacity;
        this.wx = new double[capacity];
        this.wy = new double[capacity];
    }

    /** Records a new position. Overwrites the oldest entry when full. */
    public void record(double x, double y) {
        wx[head] = x;
        wy[head] = y;
        head = (head + 1) % capacity;
        if (size < capacity)
            size++;
    }

    /** Clears all recorded points. */
    public void clear() {
        size = 0;
        head = 0;
    }

    /**
     * Fills the provided screen-space float array (x0,y0,x1,y1,...) for rendering.
     * Points are ordered oldest→newest.
     *
     * @param camera the WorldCamera for coordinate conversion
     * @param out    pre-allocated float array of size ≥ 2 * pointCount()
     * @return number of points written (≡ pointCount())
     */
    public int toScreenCoords(WorldCamera camera, float[] out) {
        if (size == 0)
            return 0;
        int start = (head - size + capacity) % capacity;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % capacity;
            Vector2 sc = camera.worldToScreen(wx[idx], wy[idx]);
            out[i * 2] = sc.x;
            out[i * 2 + 1] = sc.y;
        }
        return size;
    }

    public int pointCount() {
        return size;
    }

    public int capacity() {
        return capacity;
    }
}
