// 3D billboard vertex shader for star glow.
// Projects the star centre into clip space, then warps the billboard to the
// sphere's apparent ellipse so the 2D glow profile stays locked to the 3D star.

attribute vec2 a_position;     // quad corner: (-0.5,-0.5) to (0.5,0.5)
attribute vec2 a_texCoord0;    // UV for fragment shader

uniform mat4  u_viewProj;
uniform vec3  u_center;        // star world pos (floating-origin)
uniform vec2  u_centerOffset;
uniform vec2  u_axisX;
uniform vec2  u_axisY;
uniform vec2  u_viewport;      // screen width, height
uniform float u_logDepthC;

varying vec2 v_texCoord;

void main() {
    v_texCoord = a_texCoord0;

    // Project the star centre into clip space.
    vec4 clip = u_viewProj * vec4(u_center, 1.0);

    // Convert pixel offset to clip-space offset (depends on clip.w).
    vec2 pixToClip = (2.0 / u_viewport) * clip.w;
    vec2 pixelOffset = u_centerOffset + 2.0 * (a_position.x * u_axisX + a_position.y * u_axisY);
    clip.xy += pixelOffset * pixToClip;

    // Logarithmic depth still uses the centre depth so the whole glow layer
    // stays attached to the star in camera space.
    if (u_logDepthC > 0.0) {
        clip.z = (log2(max(1e-6, clip.w + 1.0)) * u_logDepthC - 1.0) * clip.w;
    }

    gl_Position = clip;
}
