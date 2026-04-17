attribute vec4 a_position;
attribute vec2 a_texCoord0;

uniform mat4 u_projTrans;

varying vec2 v_texCoord;
varying vec2 v_screenPos;

void main() {
    v_texCoord = a_texCoord0;
    v_screenPos = a_position.xy;
    gl_Position = u_projTrans * a_position;
}
