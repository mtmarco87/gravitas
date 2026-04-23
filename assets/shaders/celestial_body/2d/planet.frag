#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
precision mediump float;
#endif

varying vec2 v_texCoord;
varying vec4 v_color;

uniform sampler2D u_texture;
uniform sampler2D u_nightTexture;
uniform float u_hasNightTexture;
uniform float u_enableDayNightMode;
uniform float u_rotation;      // radians, current axial rotation
uniform float u_isStar;        // 1.0 for stars (no limb darkening)
uniform vec3  u_baseColor;     // fallback colour for near-black texture regions
uniform vec3  u_lightDirWorld;
uniform vec3  u_worldToBodyRow0;
uniform vec3  u_worldToBodyRow1;
uniform vec3  u_worldToBodyRow2;

const float PI = 3.14159265;

void main() {
    // Map quad UV [0,1] to [-1,1] centred on disk
    vec2 uv = v_texCoord * 2.0 - 1.0;
    float r2 = dot(uv, uv);

    // Discard pixels outside the unit circle (the disk)
    if (r2 > 1.0) discard;

    // Sphere normal at this fragment (unit sphere)
    float z = sqrt(1.0 - r2);
    vec3 worldNormal = vec3(uv.x, -uv.y, z);
    vec3 normal = normalize(vec3(
        dot(u_worldToBodyRow0, worldNormal),
        dot(u_worldToBodyRow1, worldNormal),
        dot(u_worldToBodyRow2, worldNormal)
    ));

    // Equirectangular projection: compute longitude and latitude from
    // the sphere normal, apply axial rotation offset
    float lon = atan(normal.z, normal.x);
    float lat = asin(clamp(normal.y, -1.0, 1.0));

    // Map to texture coordinates [0,1]
    vec2 texUV;
    texUV.x = fract(0.75 - lon / (2.0 * PI) - u_rotation / (2.0 * PI));
    texUV.y = 0.5 - lat / PI;

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

    if (u_enableDayNightMode > 0.5 && u_isStar < 0.5) {
        float daylight = smoothstep(-0.10, 0.10, dot(worldNormal, normalize(u_lightDirWorld)));
        vec3 litBase = texColor.rgb * mix(0.14, 1.0, daylight);
        if (u_hasNightTexture > 0.5) {
            vec3 nightColor = texture2D(u_nightTexture, texUV, lodBias).rgb;
            texColor.rgb = mix(nightColor, litBase, daylight);
        } else {
            texColor.rgb = litBase;
        }
    }

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
