#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;
varying vec2 v_screenPos;

uniform sampler2D u_texture;
uniform float u_innerRadius;   // inner ring as fraction of quad half-size [0,1]
uniform float u_outerRadius;   // outer ring as fraction of quad half-size [0,1]
uniform float u_opacity;       // base opacity multiplier
uniform float u_hasTexture;    // 1.0 = sample texture, 0.0 = use u_ringColor
uniform vec3  u_ringColor;     // fallback solid colour for procedural rings
uniform vec2  u_center;
uniform vec2  u_invAxisX;
uniform vec2  u_invAxisY;
uniform float u_bodyRadius;
uniform vec2  u_localViewZ;

void main() {
    vec2 delta = v_screenPos - u_center;
    vec2 local = vec2(dot(delta, u_invAxisX), dot(delta, u_invAxisY));
    float r = length(local);

    // Discard pixels outside the ring annulus
    if (r < u_innerRadius || r > u_outerRadius) discard;

    float viewDepth = dot(local, u_localViewZ);
    if (r < u_bodyRadius && viewDepth < 0.0) discard;

    // Normalise radial position to [0,1] for texture sampling
    float t = (r - u_innerRadius) / (u_outerRadius - u_innerRadius);

    vec4 ringCol;
    if (u_hasTexture > 0.5) {
        // Sample the 1D radial strip texture (left = inner, right = outer)
        ringCol = texture2D(u_texture, vec2(t, 0.5));
    } else {
        // Procedural: thin bands with varying opacity
        float band = 0.5 + 0.5 * sin(t * 40.0);
        float detail = 0.7 + 0.3 * sin(t * 120.0);
        float alpha = band * detail * 0.8;
        ringCol = vec4(u_ringColor, alpha);
    }

    // Soft anti-aliased edges at inner and outer boundaries
    float edgeWidth = 0.02;
    float edgeInner = smoothstep(u_innerRadius, u_innerRadius + edgeWidth, r);
    float edgeOuter = smoothstep(u_outerRadius, u_outerRadius - edgeWidth, r);

    gl_FragColor = vec4(ringCol.rgb, ringCol.a * u_opacity * edgeInner * edgeOuter);
}
