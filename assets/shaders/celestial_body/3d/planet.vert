// 3D sphere vertex shader for celestial body rendering.
// Transforms a unit sphere mesh via Model · View · Projection.
// Passes world-space normal and UV to the fragment shader.

attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec2 a_texCoord0;

uniform mat4 u_mvp;           // model * view * projection
uniform mat4 u_model;         // model matrix (for normal transform)
uniform float u_logDepthC;    // = 2.0 / log2(farClip + 1.0)

varying vec3 v_normal;
varying vec2 v_texCoord;
varying vec3 v_localPos;      // position on unit sphere (before model transform)
varying vec3 v_worldPos;      // camera-relative world position (camera at origin)

void main() {
    v_localPos = a_position;
    v_normal = normalize((u_model * vec4(a_normal, 0.0)).xyz);
    v_worldPos = (u_model * vec4(a_position, 1.0)).xyz;
    v_texCoord = a_texCoord0;
    gl_Position = u_mvp * vec4(a_position, 1.0);
    // Logarithmic depth: remaps z for uniform precision across huge near/far range.
    if (u_logDepthC > 0.0) {
        gl_Position.z = (log2(max(1e-6, gl_Position.w + 1.0)) * u_logDepthC - 1.0) * gl_Position.w;
    }
}
