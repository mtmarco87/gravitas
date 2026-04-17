package com.gravitas.rendering.orbit;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Matrix4;
import com.gravitas.entities.CelestialBody;
import com.gravitas.entities.SimObject;
import com.gravitas.rendering.core.ProjectedEllipse;
import com.gravitas.rendering.core.WorldCamera;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests whether an orbit overlay sample is hidden behind a rendered celestial
 * body in FREE_CAM.
 */
public class OrbitOcclusionMask {

    private static final double OCCLUSION_EPSILON_METERS = 1.0;
    private static final int RING_BOUNDS_SAMPLES = 24;

    private static final class Occluder {
        final double cx;
        final double cy;
        final double cz;
        final double radius;
        final float centerX;
        final float centerY;
        final float inv00;
        final float inv01;
        final float inv10;
        final float inv11;
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;

        Occluder(double cx, double cy, double cz, double radius, ProjectedEllipse ellipse) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radius = radius;

            this.centerX = ellipse.centerX;
            this.centerY = ellipse.centerY;

            float det = ellipse.axisXX * ellipse.axisYY - ellipse.axisXY * ellipse.axisYX;
            if (Math.abs(det) < 1e-6f) {
                float safeRadius = Math.max(1f,
                        (float) Math.hypot(ellipse.axisXX + ellipse.axisYX, ellipse.axisXY + ellipse.axisYY));
                this.inv00 = 1f / safeRadius;
                this.inv01 = 0f;
                this.inv10 = 0f;
                this.inv11 = 1f / safeRadius;
            } else {
                this.inv00 = ellipse.axisYY / det;
                this.inv01 = -ellipse.axisYX / det;
                this.inv10 = -ellipse.axisXY / det;
                this.inv11 = ellipse.axisXX / det;
            }

            float extentX = (float) Math.sqrt(ellipse.axisXX * ellipse.axisXX + ellipse.axisYX * ellipse.axisYX);
            float extentY = (float) Math.sqrt(ellipse.axisXY * ellipse.axisXY + ellipse.axisYY * ellipse.axisYY);
            this.minX = ellipse.centerX - extentX;
            this.maxX = ellipse.centerX + extentX;
            this.minY = ellipse.centerY - extentY;
            this.maxY = ellipse.centerY + extentY;
        }

        boolean containsScreenPoint(float sx, float sy) {
            if (sx < minX || sx > maxX || sy < minY || sy > maxY) {
                return false;
            }
            float dx = sx - centerX;
            float dy = sy - centerY;
            float u = inv00 * dx + inv01 * dy;
            float v = inv10 * dx + inv11 * dy;
            return u * u + v * v <= 1.0f;
        }

        boolean segmentBoxIntersects(float x0, float y0, float x1, float y1) {
            float segMinX = Math.min(x0, x1);
            float segMaxX = Math.max(x0, x1);
            float segMinY = Math.min(y0, y1);
            float segMaxY = Math.max(y0, y1);
            return segMaxX >= minX && segMinX <= maxX && segMaxY >= minY && segMinY <= maxY;
        }
    }

    private static final class RingOccluder {
        final double cx;
        final double cy;
        final double cz;
        final double ux;
        final double uy;
        final double uz;
        final double vx;
        final double vy;
        final double vz;
        final double nx;
        final double ny;
        final double nz;
        final double innerRadius;
        final double outerRadius;
        final float minX;
        final float maxX;
        final float minY;
        final float maxY;

        RingOccluder(double cx, double cy, double cz,
                double ux, double uy, double uz,
                double vx, double vy, double vz,
                double nx, double ny, double nz,
                double innerRadius, double outerRadius,
                float minX, float maxX, float minY, float maxY) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.ux = ux;
            this.uy = uy;
            this.uz = uz;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.innerRadius = innerRadius;
            this.outerRadius = outerRadius;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        boolean containsScreenPoint(float sx, float sy) {
            return sx >= minX && sx <= maxX && sy >= minY && sy <= maxY;
        }

        boolean segmentBoxIntersects(float x0, float y0, float x1, float y1) {
            float segMinX = Math.min(x0, x1);
            float segMaxX = Math.max(x0, x1);
            float segMinY = Math.min(y0, y1);
            float segMaxY = Math.max(y0, y1);
            return segMaxX >= minX && segMinX <= maxX && segMaxY >= minY && segMinY <= maxY;
        }
    }

    private final WorldCamera camera;
    private final boolean enabled;
    private final List<Occluder> occluders = new ArrayList<>();
    private final List<RingOccluder> ringOccluders = new ArrayList<>();
    private final Matrix4 bodyRotationMatrix = new Matrix4();

    public OrbitOcclusionMask(WorldCamera camera, List<SimObject> objects, float[] screenRadii) {
        this.camera = camera;
        this.enabled = camera.getMode() == WorldCamera.CameraMode.FREE_CAM;
        if (!enabled) {
            return;
        }

        int count = Math.min(objects.size(), screenRadii.length);
        for (int i = 0; i < count; i++) {
            SimObject obj = objects.get(i);
            if (!obj.active || !(obj instanceof CelestialBody body)) {
                continue;
            }

            double radius = inflatedRadius(body, screenRadii[i]);
            if (radius <= 0.0) {
                continue;
            }

            ProjectedEllipse ellipse = camera.projectSphereEllipse(radius, body.x, body.y, body.z);
            occluders.add(new Occluder(body.x, body.y, body.z, radius, ellipse));

            if (body.hasRings()) {
                RingOccluder ringOccluder = buildRingOccluder(body, screenRadii[i]);
                if (ringOccluder != null) {
                    ringOccluders.add(ringOccluder);
                }
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPointVisible(double wx, double wy, double wz) {
        if (!enabled) {
            return true;
        }
        if (camera.depthOf(wx, wy, wz) <= 0.0) {
            return false;
        }

        Vector2 screen = camera.worldToScreen(wx, wy, wz);
        for (Occluder occluder : occluders) {
            if (!occluder.containsScreenPoint(screen.x, screen.y)) {
                continue;
            }
            if (isOccludedBy(occluder, wx, wy, wz)) {
                return false;
            }
        }

        for (RingOccluder ringOccluder : ringOccluders) {
            if (!ringOccluder.containsScreenPoint(screen.x, screen.y)) {
                continue;
            }
            if (isOccludedBy(ringOccluder, wx, wy, wz)) {
                return false;
            }
        }

        return true;
    }

    public boolean segmentMayBeOccluded(float x0, float y0, float x1, float y1) {
        if (!enabled) {
            return false;
        }
        for (Occluder occluder : occluders) {
            if (occluder.segmentBoxIntersects(x0, y0, x1, y1)) {
                return true;
            }
        }
        for (RingOccluder ringOccluder : ringOccluders) {
            if (ringOccluder.segmentBoxIntersects(x0, y0, x1, y1)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOccludedBy(Occluder occluder, double wx, double wy, double wz) {
        double ox = camera.getCamPosX();
        double oy = camera.getCamPosY();
        double oz = camera.getCamPosZ();

        double dx = wx - ox;
        double dy = wy - oy;
        double dz = wz - oz;
        double pointDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (pointDistance <= OCCLUSION_EPSILON_METERS) {
            return false;
        }

        dx /= pointDistance;
        dy /= pointDistance;
        dz /= pointDistance;

        double mx = ox - occluder.cx;
        double my = oy - occluder.cy;
        double mz = oz - occluder.cz;

        double b = mx * dx + my * dy + mz * dz;
        double c = mx * mx + my * my + mz * mz - occluder.radius * occluder.radius;
        if (c <= 0.0) {
            return false;
        }

        double discriminant = b * b - c;
        if (discriminant < 0.0) {
            return false;
        }

        double sqrtDiscriminant = Math.sqrt(discriminant);
        double nearT = -b - sqrtDiscriminant;
        double farT = -b + sqrtDiscriminant;
        double hitDistance = nearT > OCCLUSION_EPSILON_METERS ? nearT : farT;

        return hitDistance > OCCLUSION_EPSILON_METERS
                && hitDistance < pointDistance - OCCLUSION_EPSILON_METERS;
    }

    private double inflatedRadius(CelestialBody body, float screenRadius) {
        return inflatedRadius(body, body.radius, screenRadius);
    }

    private double inflatedRadius(CelestialBody body, double baseRadius, float screenRadius) {
        if (screenRadius <= 0f) {
            return baseRadius;
        }
        double desired = camera.screenToWorldSphereRadius(screenRadius, body.x, body.y, body.z);
        double ratio = body.radius > 0.0 ? baseRadius / body.radius : 1.0;
        return Math.max(baseRadius, desired * ratio);
    }

    private RingOccluder buildRingOccluder(CelestialBody body, float bodyScreenRadius) {
        double outerRadius = inflatedRadius(body, body.ring.outerRadius, bodyScreenRadius);
        double innerRadius = outerRadius * (body.ring.innerRadius / body.ring.outerRadius);
        if (outerRadius <= innerRadius) {
            return null;
        }

        camera.buildBodyRotationMatrix(bodyRotationMatrix,
                body,
                0.0);

        float[] m = bodyRotationMatrix.val;
        double ux = m[Matrix4.M00];
        double uy = m[Matrix4.M10];
        double uz = m[Matrix4.M20];
        double vx = m[Matrix4.M02];
        double vy = m[Matrix4.M12];
        double vz = m[Matrix4.M22];
        double nx = m[Matrix4.M01];
        double ny = m[Matrix4.M11];
        double nz = m[Matrix4.M21];

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        boolean anyVisible = false;

        for (int i = 0; i < RING_BOUNDS_SAMPLES; i++) {
            double angle = (2.0 * Math.PI * i) / RING_BOUNDS_SAMPLES;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double wx = body.x + outerRadius * (ux * cos + vx * sin);
            double wy = body.y + outerRadius * (uy * cos + vy * sin);
            double wz = body.z + outerRadius * (uz * cos + vz * sin);
            if (camera.depthOf(wx, wy, wz) <= 0.0) {
                continue;
            }

            Vector2 screen = camera.worldToScreen(wx, wy, wz);
            minX = Math.min(minX, screen.x);
            maxX = Math.max(maxX, screen.x);
            minY = Math.min(minY, screen.y);
            maxY = Math.max(maxY, screen.y);
            anyVisible = true;
        }

        if (!anyVisible) {
            return null;
        }

        return new RingOccluder(body.x, body.y, body.z,
                ux, uy, uz,
                vx, vy, vz,
                nx, ny, nz,
                innerRadius, outerRadius,
                minX, maxX, minY, maxY);
    }

    private boolean isOccludedBy(RingOccluder ringOccluder, double wx, double wy, double wz) {
        double ox = camera.getCamPosX();
        double oy = camera.getCamPosY();
        double oz = camera.getCamPosZ();

        double dirX = wx - ox;
        double dirY = wy - oy;
        double dirZ = wz - oz;
        double pointDistance = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (pointDistance <= OCCLUSION_EPSILON_METERS) {
            return false;
        }

        dirX /= pointDistance;
        dirY /= pointDistance;
        dirZ /= pointDistance;

        double denom = dirX * ringOccluder.nx + dirY * ringOccluder.ny + dirZ * ringOccluder.nz;
        if (Math.abs(denom) < 1e-9) {
            return false;
        }

        double t = ((ringOccluder.cx - ox) * ringOccluder.nx
                + (ringOccluder.cy - oy) * ringOccluder.ny
                + (ringOccluder.cz - oz) * ringOccluder.nz) / denom;
        if (t <= OCCLUSION_EPSILON_METERS || t >= pointDistance - OCCLUSION_EPSILON_METERS) {
            return false;
        }

        double hitX = ox + dirX * t - ringOccluder.cx;
        double hitY = oy + dirY * t - ringOccluder.cy;
        double hitZ = oz + dirZ * t - ringOccluder.cz;
        double localU = hitX * ringOccluder.ux + hitY * ringOccluder.uy + hitZ * ringOccluder.uz;
        double localV = hitX * ringOccluder.vx + hitY * ringOccluder.vy + hitZ * ringOccluder.vz;
        double radial = Math.sqrt(localU * localU + localV * localV);
        return radial >= ringOccluder.innerRadius && radial <= ringOccluder.outerRadius;
    }
}