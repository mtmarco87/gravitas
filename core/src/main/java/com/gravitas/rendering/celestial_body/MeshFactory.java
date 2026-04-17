package com.gravitas.rendering.celestial_body;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;

/**
 * Pre-builds reusable meshes for 3D celestial body rendering.
 *
 * All meshes are unit-sized (radius 1) and centred at the origin.
 * Callers scale via the model matrix.
 */
public final class MeshFactory {

    private MeshFactory() {
    }

    /**
     * Creates a UV-sphere of radius 1 with the given resolution.
     * Vertex format: position(x,y,z) + normal(nx,ny,nz) + texCoord(u,v).
     *
     * @param latSegments number of latitude bands (e.g. 32)
     * @param lonSegments number of longitude slices (e.g. 64)
     * @return a new Mesh (caller must dispose)
     */
    public static Mesh createSphere(int latSegments, int lonSegments) {
        int vertCount = (latSegments + 1) * (lonSegments + 1);
        int idxCount = latSegments * lonSegments * 6;

        // 8 floats per vertex: px, py, pz, nx, ny, nz, u, v
        float[] verts = new float[vertCount * 8];
        short[] indices = new short[idxCount];

        int vi = 0;
        for (int lat = 0; lat <= latSegments; lat++) {
            float theta = (float) (lat * Math.PI / latSegments); // 0..PI
            float sinT = (float) Math.sin(theta);
            float cosT = (float) Math.cos(theta);

            for (int lon = 0; lon <= lonSegments; lon++) {
                float phi = (float) (lon * 2.0 * Math.PI / lonSegments); // 0..2PI
                float sinP = (float) Math.sin(phi);
                float cosP = (float) Math.cos(phi);

                // Position = normal for unit sphere
                float x = sinT * cosP;
                float y = cosT; // Y = up (pole axis)
                float z = sinT * sinP;

                // UV: u wraps longitude [0,1], v goes top-to-bottom [0,1]
                float u = (float) lon / lonSegments;
                float v = (float) lat / latSegments;

                verts[vi++] = x; // position
                verts[vi++] = y;
                verts[vi++] = z;
                verts[vi++] = x; // normal (same as position for unit sphere)
                verts[vi++] = y;
                verts[vi++] = z;
                verts[vi++] = u; // texcoord
                verts[vi++] = v;
            }
        }

        int ii = 0;
        for (int lat = 0; lat < latSegments; lat++) {
            for (int lon = 0; lon < lonSegments; lon++) {
                int a = lat * (lonSegments + 1) + lon;
                int b = a + lonSegments + 1;

                indices[ii++] = (short) a;
                indices[ii++] = (short) (a + 1);
                indices[ii++] = (short) b;

                indices[ii++] = (short) (a + 1);
                indices[ii++] = (short) (b + 1);
                indices[ii++] = (short) b;
            }
        }

        Mesh mesh = new Mesh(true, vertCount, idxCount,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        mesh.setVertices(verts);
        mesh.setIndices(indices);
        return mesh;
    }

    /**
     * Creates a flat disc annulus (ring) in the XZ plane, centred at origin.
     * Inner radius = {@code innerFrac}, outer radius = 1.0.
     * Vertex format: position(x,y,z) + texCoord(u,v).
     * u maps radially [0=inner, 1=outer], v maps angularly [0,1].
     *
     * @param innerFrac inner radius as fraction of outer [0..1)
     * @param segments  number of angular segments (e.g. 128)
     * @return a new Mesh (caller must dispose)
     */
    public static Mesh createRingDisc(float innerFrac, int segments) {
        int vertCount = (segments + 1) * 2;
        int idxCount = segments * 6;

        // 5 floats per vertex: px, py, pz, u, v
        float[] verts = new float[vertCount * 5];
        short[] indices = new short[idxCount];

        int vi = 0;
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (i * 2.0 * Math.PI / segments);
            float ca = (float) Math.cos(angle);
            float sa = (float) Math.sin(angle);
            float vCoord = (float) i / segments;

            // Inner vertex
            verts[vi++] = innerFrac * ca; // x
            verts[vi++] = 0; // y (flat in XZ)
            verts[vi++] = innerFrac * sa; // z
            verts[vi++] = 0; // u = inner
            verts[vi++] = vCoord; // v = angular

            // Outer vertex
            verts[vi++] = ca; // x
            verts[vi++] = 0; // y
            verts[vi++] = sa; // z
            verts[vi++] = 1; // u = outer
            verts[vi++] = vCoord; // v = angular
        }

        int ii = 0;
        for (int i = 0; i < segments; i++) {
            int a = i * 2;
            int b = a + 1;
            int c = a + 2;
            int d = a + 3;
            indices[ii++] = (short) a;
            indices[ii++] = (short) b;
            indices[ii++] = (short) c;
            indices[ii++] = (short) c;
            indices[ii++] = (short) b;
            indices[ii++] = (short) d;
        }

        Mesh mesh = new Mesh(true, vertCount, idxCount,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        mesh.setVertices(verts);
        mesh.setIndices(indices);
        return mesh;
    }
}
