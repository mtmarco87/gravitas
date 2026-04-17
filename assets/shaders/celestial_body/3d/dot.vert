// Billboard-quad vertex shader for rendering celestial bodies as small dots.
// Projects the body centre, then offsets the quad corners in clip space so the
// dot has a fixed pixel size regardless of distance. Logarithmic depth keeps
// correct sorting with the rest of the 3D scene.

attribute vec2 a_position;     // quad corner: (-0.5,-0.5) to (0.5,0.5)
attribute vec2 a_texCoord0;    // UV for circle clipping in frag shader

uniform mat4  u_viewProj;
uniform vec3  u_center;        // body world pos (floating-origin)
uniform float u_size;          // diameter in pixels
uniform vec2  u_viewport;      // screen width, height
uniform float u_logDepthC;

varying vec2 v_uv;

void main() {
    // Project the body centre into clip space.
    vec4 clip = u_viewProj * vec4(u_center, 1.0);

    // Convert pixel offset to clip-space offset (depends on clip.w).
    vec2 pixToClip = (2.0 / u_viewport) * clip.w;
    clip.xy += a_position * u_size * pixToClip;

    // Logarithmic depth (uses centre's w for correct sorting).
    if (u_logDepthC > 0.0) {
        clip.z = (log2(max(1e-6, clip.w + 1.0)) * u_logDepthC - 1.0) * clip.w;
    }

    gl_Position = clip;
    v_uv = a_texCoord0;
}
