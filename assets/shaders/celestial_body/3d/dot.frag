// Fragment shader for billboard-dot rendering.
// Clips to a circle using UV and outputs a solid colour.

#ifdef GL_ES
precision mediump float;
#endif

uniform vec3 u_color;

varying vec2 v_uv;

void main() {
    vec2 coord = v_uv - vec2(0.5);
    if (dot(coord, coord) > 0.25) discard;
    gl_FragColor = vec4(u_color, 1.0);
}
