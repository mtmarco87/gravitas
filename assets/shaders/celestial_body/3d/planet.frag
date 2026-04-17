// 3D sphere fragment shader for celestial body rendering.
// Uses mesh UVs for texture sampling (same approach as 2D shader) to avoid
// atan2 seam artifacts. Rotation is applied as a uniform offset on UV.x.

#ifdef GL_ES
#extension GL_OES_standard_derivatives : enable
precision mediump float;
#endif

varying vec3 v_normal;
varying vec2 v_texCoord;
varying vec3 v_localPos;
varying vec3 v_worldPos;

uniform sampler2D u_texture;
uniform float u_isStar;        // 1.0 for stars (no limb darkening)
uniform vec3  u_baseColor;     // fallback colour for near-black regions
uniform float u_rotation;      // axial rotation in radians

const float PI = 3.14159265;

void main() {
    vec2 texUV;
    texUV.x = fract(0.75 - v_texCoord.x - u_rotation / (2.0 * PI));
    texUV.y = v_texCoord.y;

    // Seam fix: same fwidth trick as the 2D shader.
    // At the mesh UV seam (u wraps from 1→0), the GPU sees a huge gradient
    // and picks a low mip level. Detect and force high-res sampling.
    float seamGrad = fwidth(texUV.x);
    float lodBias  = -smoothstep(0.05, 0.4, seamGrad) * 10.0;

    vec4 texColor = texture2D(u_texture, texUV, lodBias);

    // Partial-texture fill
    float lum = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));
    float fill = 1.0 - smoothstep(0.01, 0.06, lum);
    texColor.rgb = mix(texColor.rgb, u_baseColor, fill);

    // Limb darkening: dot(normal, viewDir) gives cosine of view angle.
    float limb = 1.0;
    float edgeSoftness = 1.0;
    if (u_isStar < 0.5) {
        vec3 worldN = normalize(v_normal);
        vec3 viewDir = normalize(-v_worldPos);
        float NdotV = max(0.0, dot(worldN, viewDir));
        limb = mix(0.28, 1.0, pow(NdotV, 0.68));

        // Keep a thin darker band just inside the silhouette. This mimics the
        // 2D disk's scenic "eyeliner" rim without making the whole edge hazy.
        float innerRimBand = smoothstep(0.02, 0.09, NdotV) * (1.0 - smoothstep(0.12, 0.24, NdotV));
        limb *= 1.0 - 0.10 * innerRimBand;

        // Match the 2D disk's slightly softened silhouette. In the 2D shader
        // the last ~4% of the radius fades out via smoothstep(0.96, 1.0, r).
        // On a real sphere, that corresponds to NdotV dropping into roughly
        // the [0.0, 0.28] range near the limb.
        float edgeWidth = max(fwidth(NdotV) * 0.9, 0.0010);
        edgeSoftness = smoothstep(0.0, 0.22 + edgeWidth, NdotV);
    }

    gl_FragColor = vec4(texColor.rgb * limb, texColor.a * edgeSoftness);
}
