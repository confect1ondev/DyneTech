// Procedural mote dot, replacing the runtime-generated texture on the GPU path. The same
// program draws both passes; the manager flips GlowPass and the blend state between them.
// Core pass: crisp alpha-blended dot. Glow pass: wide premultiplied falloff written with
// alpha 0 so Veil's bloom composite treats it as glow only (its step(0.9, a) gate skips the
// raw-color add and just blooms it).

in vec2 vUv;
in float vAlpha;
in vec3 vTint;

uniform vec4 ColorMod; // rgb tint, a intensity scale
uniform float GlowPass;

out vec4 fragColor;

void main() {
    float d = length(vUv * 2.0 - 1.0);
    if (GlowPass > 0.5) {
        float fall = 1.0 - smoothstep(0.0, 1.0, d);
        vec3 rgb = ColorMod.rgb * vTint * (fall * fall) * (ColorMod.a * vAlpha);
        if (max(rgb.r, max(rgb.g, rgb.b)) < 0.004) discard;
        fragColor = vec4(rgb, 0.0);
    } else {
        float a = vAlpha * ColorMod.a * (1.0 - smoothstep(0.55, 0.9, d));
        if (a < 0.05) discard;
        // Hot nucleus: the middle of each dot pushes toward white so close-up motes read as
        // points of light instead of flat colored discs.
        float core = 1.0 - smoothstep(0.0, 0.35, d);
        fragColor = vec4(mix(ColorMod.rgb * vTint, vec3(1.0), core * 0.55), a);
    }
}
