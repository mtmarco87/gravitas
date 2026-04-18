// Dashed-line vertex shader.
// Each vertex carries screen-space position, packed colour, and an accumulated
// distance attribute used by the fragment shader to produce the dash pattern.
attribute vec4 a_position;
attribute vec4 a_color;
attribute float a_dist;

uniform mat4 u_projTrans;

varying vec4 v_color;
varying float v_dist;

void main() {
    v_color = a_color;
    v_dist  = a_dist;
    gl_Position = u_projTrans * a_position;
}
