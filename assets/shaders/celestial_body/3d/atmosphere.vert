// 3D atmosphere vertex shader.
// Uses a camera-facing billboard so the atmosphere can reuse the exact same
// radial profile as the 2D shader while still living in 3D space.

attribute vec2 a_position;
attribute vec2 a_texCoord0;

uniform mat4  u_viewProj;
uniform vec3  u_center;
uniform vec2  u_centerOffset;
uniform vec2  u_axisX;
uniform vec2  u_axisY;
uniform vec2  u_viewport;
uniform float u_logDepthC;

varying vec2 v_texCoord;

void main() {
    v_texCoord = a_texCoord0;

    vec4 clip = u_viewProj * vec4(u_center, 1.0);
    vec2 pixToClip = (2.0 / u_viewport) * clip.w;
    vec2 pixelOffset = u_centerOffset + 2.0 * (a_position.x * u_axisX + a_position.y * u_axisY);
    clip.xy += pixelOffset * pixToClip;

    if (u_logDepthC > 0.0) {
        clip.z = (log2(max(1e-6, clip.w + 1.0)) * u_logDepthC - 1.0) * clip.w;
    }

    gl_Position = clip;
}
