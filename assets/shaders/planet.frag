#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
precision mediump float;
#endif

varying vec2 v_texCoord;
varying vec4 v_color;

uniform sampler2D u_texture;
uniform float u_rotation;      // radians, current axial rotation
uniform float u_isStar;        // 1.0 for stars (no limb darkening)
uniform vec3  u_baseColor;     // fallback colour for near-black texture regions

const float PI = 3.14159265;

void main() {
    // Map quad UV [0,1] to [-1,1] centred on disk
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r2 = dot(uv, uv);

    // Discard pixels outside the unit circle (the disk)
    if (r2 > 1.0) discard;

    // Sphere normal at this fragment (unit sphere)
    float z = sqrt(1.0 - r2);
    vec3 normal = vec3(uv.x, uv.y, z);

    // Equirectangular projection: compute longitude and latitude from
    // the sphere normal, apply axial rotation offset
    float lon = atan(normal.x, normal.z) - u_rotation;
    float lat = asin(clamp(normal.y, -1.0, 1.0));

    // Map to texture coordinates [0,1]
    vec2 texUV;
    texUV.x = fract(lon / (2.0 * PI) + 0.5);
    texUV.y = lat / PI + 0.5;

    // Fix the texture seam: at the longitude wrap-around point (atan
    // discontinuity), the screen-space derivative of texUV.x spikes from
    // ~1.0 to ~0.0 in one pixel.  The GPU interprets this as a huge
    // gradient and selects a very low-resolution mipmap, creating a
    // visible stripe.  Detect via fwidth and apply a negative LOD bias
    // to force higher-res sampling at the seam.
    float seamGrad = fwidth(texUV.x);
    float lodBias  = -smoothstep(0.05, 0.4, seamGrad) * 10.0;

    vec4 texColor = texture2D(u_texture, texUV, lodBias);

    // Partial-texture fill: when a texture region is near-black (unmapped),
    // blend in the body's base colour so it doesn't render as a dark void.
    float lum = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));
    float fill = 1.0 - smoothstep(0.01, 0.06, lum);
    texColor.rgb = mix(texColor.rgb, u_baseColor, fill);

    // Limb darkening: darken edges based on angle to viewer.
    // Stars skip this for a bright uniform look.
    float limb = 1.0;
    if (u_isStar < 0.5) {
        // Smooth limb darkening: using z (dot with view direction)
        limb = mix(0.3, 1.0, pow(z, 0.6));
    }

    // Soft anti-aliased edge using the last ~2% of the radius
    float edgeSoftness = 1.0 - smoothstep(0.96, 1.0, sqrt(r2));

    gl_FragColor = vec4(texColor.rgb * limb, texColor.a * edgeSoftness);
}
