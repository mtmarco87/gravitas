// 3D ring disc fragment shader.
// u maps radially [0=inner, 1=outer], the ring annulus is already in the mesh.
// Supports textured (1D radial strip) and procedural band rendering.

#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

uniform sampler2D u_texture;
uniform float u_opacity;       // base opacity multiplier
uniform float u_hasTexture;    // 1.0 = sample texture, 0.0 = use u_ringColor
uniform vec3  u_ringColor;     // procedural ring colour

void main() {
    float t = v_texCoord.x;    // 0 = inner edge, 1 = outer edge

    vec4 ringCol;
    if (u_hasTexture > 0.5) {
        ringCol = texture2D(u_texture, vec2(t, 0.5));
    } else {
        // Procedural bands
        float band = 0.5 + 0.5 * sin(t * 40.0);
        float detail = 0.7 + 0.3 * sin(t * 120.0);
        float alpha = band * detail * 0.8;
        ringCol = vec4(u_ringColor, alpha);
    }

    // Soft AA at inner and outer edges
    float edgeInner = smoothstep(0.0, 0.02, t);
    float edgeOuter = smoothstep(1.0, 0.98, t);

    gl_FragColor = vec4(ringCol.rgb, ringCol.a * u_opacity * edgeInner * edgeOuter);
}
