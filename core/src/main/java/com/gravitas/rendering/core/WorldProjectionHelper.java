package com.gravitas.rendering.core;

import com.badlogic.gdx.math.Vector2;

/**
 * Package-local projection and camera-space math extracted from WorldCamera.
 * Keeps the public WorldCamera API stable while isolating the dense projection
 * logic.
 */
final class WorldProjectionHelper {

    private final WorldCamera cameraState;

    WorldProjectionHelper(WorldCamera cameraState) {
        this.cameraState = cameraState;
    }

    Vector2 worldToScreen(double wx, double wy, double wz) {
        if (cameraState.getMode() == CameraMode.FREE_CAM) {
            return worldToScreenFreeCam(wx, wy, wz);
        }
        return worldToScreenTopView(wx, wy);
    }

    ProjectedEllipse projectSphereEllipse(double worldRadius, double wx, double wy, double wz) {
        Vector2 screenCenter = worldToScreen(wx, wy, wz);
        float fallbackRadius = worldSphereRadiusToScreen(worldRadius, wx, wy, wz);
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            return new ProjectedEllipse(screenCenter.x, screenCenter.y, fallbackRadius, 0f, 0f,
                    fallbackRadius);
        }

        double dx = wx - cameraState.getCamPosX();
        double dy = wy - cameraState.getCamPosY();
        double dz = wz - cameraState.getCamPosZ();

        double cx = dx * cameraState.camRightXInternal() + dy * cameraState.camRightYInternal()
                + dz * cameraState.camRightZInternal();
        double cy = dx * cameraState.camUpXInternal() + dy * cameraState.camUpYInternal()
                + dz * cameraState.camUpZInternal();
        double cz = dx * cameraState.camFwdXInternal() + dy * cameraState.camFwdYInternal()
                + dz * cameraState.camFwdZInternal();

        if (cz <= 0.0) {
            return new ProjectedEllipse(screenCenter.x, screenCenter.y, fallbackRadius, 0f, 0f,
                    fallbackRadius);
        }

        double r2 = worldRadius * worldRadius;
        double d2 = cx * cx + cy * cy + cz * cz;
        if (d2 <= r2) {
            return new ProjectedEllipse(screenCenter.x, screenCenter.y, fallbackRadius, 0f, 0f,
                    fallbackRadius);
        }

        double k2 = d2 - r2;
        double a = cx * cx - k2;
        double b = 2.0 * cx * cy;
        double c = cy * cy - k2;
        double d = 2.0 * cx * cz;
        double e = 2.0 * cy * cz;
        double f = cz * cz - k2;

        double denom = 4.0 * a * c - b * b;
        if (Math.abs(denom) < 1e-12) {
            return new ProjectedEllipse(screenCenter.x, screenCenter.y, fallbackRadius, 0f, 0f,
                    fallbackRadius);
        }

        double u0 = (b * e - 2.0 * c * d) / denom;
        double v0 = (b * d - 2.0 * a * e) / denom;
        double fTranslated = a * u0 * u0 + b * u0 * v0 + c * v0 * v0 + d * u0 + e * v0 + f;

        double trace = a + c;
        double root = Math.sqrt((a - c) * (a - c) + b * b);
        double lambda1 = 0.5 * (trace + root);
        double lambda2 = 0.5 * (trace - root);
        if (Math.abs(lambda1) < 1e-12 || Math.abs(lambda2) < 1e-12) {
            return new ProjectedEllipse(screenCenter.x, screenCenter.y, fallbackRadius, 0f, 0f,
                    fallbackRadius);
        }

        double radius1Sq = -fTranslated / lambda1;
        double radius2Sq = -fTranslated / lambda2;
        if (radius1Sq <= 0.0 || radius2Sq <= 0.0) {
            return new ProjectedEllipse(screenCenter.x, screenCenter.y, fallbackRadius, 0f, 0f,
                    fallbackRadius);
        }

        double theta = 0.5 * Math.atan2(b, a - c);
        double cosTheta = Math.cos(theta);
        double sinTheta = Math.sin(theta);
        double radius1 = Math.sqrt(radius1Sq);
        double radius2 = Math.sqrt(radius2Sq);
        double pixelsPerUnit = cameraState.getCamera().viewportHeight * 0.5 * cameraState.fovScaleInternal();

        float centerPx = (float) (u0 * pixelsPerUnit + cameraState.getCamera().viewportWidth * 0.5);
        float centerPy = (float) (v0 * pixelsPerUnit + cameraState.getCamera().viewportHeight * 0.5);
        float axisXX = (float) (cosTheta * radius1 * pixelsPerUnit);
        float axisXY = (float) (sinTheta * radius1 * pixelsPerUnit);
        float axisYX = (float) (-sinTheta * radius2 * pixelsPerUnit);
        float axisYY = (float) (cosTheta * radius2 * pixelsPerUnit);

        return new ProjectedEllipse(centerPx, centerPy, axisXX, axisXY, axisYX, axisYY);
    }

    double[] screenToWorld(float screenX, float screenY) {
        double wx = (screenX - cameraState.getCamera().viewportWidth * 0.5) * cameraState.metersPerPixelInternal()
                + cameraState.getFocusX();
        double wy = (screenY - cameraState.getCamera().viewportHeight * 0.5) * cameraState.metersPerPixelInternal()
                + cameraState.getFocusY();
        return new double[] { wx, wy };
    }

    double[] screenToWorldOnFocusPlane(float screenX, float screenY) {
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            double[] world = screenToWorld(screenX, screenY);
            return new double[] { world[0], world[1], cameraState.getFocusZ() };
        }

        double depth = depthOf(cameraState.getFocusX(), cameraState.getFocusY(), cameraState.getFocusZ());
        if (depth <= 0.0) {
            return new double[] { cameraState.getFocusX(), cameraState.getFocusY(), cameraState.getFocusZ() };
        }

        double halfH = cameraState.getCamera().viewportHeight * 0.5;
        double xFactor = (screenX - cameraState.getCamera().viewportWidth * 0.5)
                / (cameraState.fovScaleInternal() * halfH);
        double yFactor = (screenY - cameraState.getCamera().viewportHeight * 0.5)
                / (cameraState.fovScaleInternal() * halfH);

        double dirX = cameraState.camFwdXInternal()
                + xFactor * cameraState.camRightXInternal()
                + yFactor * cameraState.camUpXInternal();
        double dirY = cameraState.camFwdYInternal()
                + xFactor * cameraState.camRightYInternal()
                + yFactor * cameraState.camUpYInternal();
        double dirZ = cameraState.camFwdZInternal()
                + xFactor * cameraState.camRightZInternal()
                + yFactor * cameraState.camUpZInternal();

        return new double[] {
                cameraState.getCamPosX() + dirX * depth,
                cameraState.getCamPosY() + dirY * depth,
                cameraState.getCamPosZ() + dirZ * depth
        };
    }

    float worldRadiusToScreen(double worldRadius) {
        return Math.max(2.0f, (float) (worldRadius / cameraState.metersPerPixelInternal()));
    }

    float worldSphereRadiusToScreen(double worldRadius, double wx, double wy, double wz) {
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            return worldRadiusToScreen(worldRadius);
        }

        double dist = distanceFromCamera(wx, wy, wz);
        if (dist <= 0) {
            return 2.0f;
        }
        if (worldRadius >= dist) {
            return Math.max(2.0f, cameraState.getCamera().viewportHeight * 0.5f);
        }

        double halfH = cameraState.getCamera().viewportHeight * 0.5;
        double projected = (worldRadius / Math.sqrt(dist * dist - worldRadius * worldRadius))
                * cameraState.fovScaleInternal() * halfH;
        return Math.max(2.0f, (float) projected);
    }

    double screenToWorldSphereRadius(float screenPixels, double wx, double wy, double wz) {
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            return screenPixels * cameraState.metersPerPixelInternal();
        }

        double dist = distanceFromCamera(wx, wy, wz);
        if (dist <= 0) {
            return 1.0;
        }

        double halfH = cameraState.getCamera().viewportHeight * 0.5;
        double t = screenPixels / (cameraState.fovScaleInternal() * halfH);
        return dist * t / Math.sqrt(1.0 + t * t);
    }

    double screenToWorldLengthAtDepth(float screenPixels, double depth) {
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            return screenPixels * cameraState.metersPerPixelInternal();
        }
        if (depth <= 0.0) {
            return screenPixels * cameraState.metersPerPixelInternal();
        }

        double halfH = cameraState.getCamera().viewportHeight * 0.5;
        return screenPixels * depth / (cameraState.fovScaleInternal() * halfH);
    }

    double orbitDistanceForSphereScreenRadius(double worldRadius, float screenRadiusPx, double maxOrbitDistance) {
        double halfH = cameraState.getCamera().viewportHeight * 0.5;
        double t = screenRadiusPx / (cameraState.fovScaleInternal() * halfH);
        if (t <= 0.0) {
            return maxOrbitDistance;
        }
        return worldRadius * Math.sqrt(1.0 + t * t) / t;
    }

    double depthOf(double wx, double wy, double wz) {
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            return 1.0;
        }
        return (wx - cameraState.getCamPosX()) * cameraState.camFwdXInternal()
                + (wy - cameraState.getCamPosY()) * cameraState.camFwdYInternal()
                + (wz - cameraState.getCamPosZ()) * cameraState.camFwdZInternal();
    }

    double distanceFromCamera(double wx, double wy, double wz) {
        if (cameraState.getMode() != CameraMode.FREE_CAM) {
            return 1.0;
        }
        double dx = wx - cameraState.getCamPosX();
        double dy = wy - cameraState.getCamPosY();
        double dz = wz - cameraState.getCamPosZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Vector2 worldToScreenTopView(double wx, double wy) {
        float sx = (float) ((wx - cameraState.getFocusX()) / cameraState.metersPerPixelInternal())
                + cameraState.getCamera().viewportWidth * 0.5f;
        float sy = (float) ((wy - cameraState.getFocusY()) / cameraState.metersPerPixelInternal())
                + cameraState.getCamera().viewportHeight * 0.5f;
        return new Vector2(sx, sy);
    }

    private Vector2 worldToScreenFreeCam(double wx, double wy, double wz) {
        double dx = wx - cameraState.getCamPosX();
        double dy = wy - cameraState.getCamPosY();
        double dz = wz - cameraState.getCamPosZ();

        double depth = dx * cameraState.camFwdXInternal() + dy * cameraState.camFwdYInternal()
                + dz * cameraState.camFwdZInternal();
        if (depth <= 0) {
            return new Vector2(-1e5f, -1e5f);
        }

        double localRight = dx * cameraState.camRightXInternal() + dy * cameraState.camRightYInternal()
                + dz * cameraState.camRightZInternal();
        double localUp = dx * cameraState.camUpXInternal() + dy * cameraState.camUpYInternal()
                + dz * cameraState.camUpZInternal();

        double halfH = cameraState.getCamera().viewportHeight * 0.5;
        double invDepth = cameraState.fovScaleInternal() / depth;
        float sx = (float) (localRight * invDepth * halfH + cameraState.getCamera().viewportWidth * 0.5);
        float sy = (float) (localUp * invDepth * halfH + halfH);
        return new Vector2(sx, sy);
    }
}