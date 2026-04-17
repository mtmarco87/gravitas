// 3D ring disc vertex shader.
// Transforms a flat annulus mesh via Model · View · Projection.

attribute vec3 a_position;
attribute vec2 a_texCoord0;

uniform mat4 u_mvp;
uniform float u_logDepthC;

varying vec2 v_texCoord;

void main() {
    v_texCoord = a_texCoord0;
    gl_Position = u_mvp * vec4(a_position, 1.0);
    if (u_logDepthC > 0.0) {
        gl_Position.z = (log2(max(1e-6, gl_Position.w + 1.0)) * u_logDepthC - 1.0) * gl_Position.w;
    }
}
