// The Usher void. Each fragment casts a ray and shades nested shells inside one huge
// sphere: deep smoke folds, hanging curtains at the horizon, thin hue-shifting veins,
// a rare wave that rolls across the whole dark, and a sparse field of winking pinpricks.
// Different shell radii mean different parallax rates, so the layers slide over each other
// as the captive moves and the black reads as depth instead of paint. The whole field also
// revolves around the cell far too slowly to watch, but not too slowly to notice.
//
// Deliberately near-black. The campfire must remain the only honest light in the cell; this
// backdrop is what the dark looks like when it is not empty.

in vec2 vNdc;

uniform mat4 InvViewProj;
uniform vec3 CenterCamPos; // sphere center minus camera position
uniform float Radius;
uniform float GameTime;    // ticks

out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
            mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
                mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
            mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
                mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y),
            f.z);
}

float fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * vnoise(p);
        p = p * 2.13 + vec3(7.7);
        a *= 0.5;
    }
    return v;
}

// Unit direction, measured from the sphere's own center, of where the eye ray pierces a
// shell of the given radius. The camera sits inside every shell, so the far root is it.
vec3 shellDir(vec3 dir, float radius) {
    vec3 c = CenterCamPos;
    float b = dot(dir, c);
    float disc = max(b * b - dot(c, c) + radius * radius, 0.0);
    return normalize(dir * (b + sqrt(disc)) - c);
}

void main() {
    vec4 w = InvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(w.xyz / w.w);

    // Imperceptible revolution of the entire void around the cell's vertical axis.
    float ang = GameTime * 0.00022;
    float ca = cos(ang), sa = sin(ang);
    dir = vec3(ca * dir.x - sa * dir.z, dir.y, sa * dir.x + ca * dir.z);

    // Same ~40 second inhale/exhale as the ambience motes and murk.
    float breath = 0.75 + 0.25 * sin(GameTime * 0.008);
    float t1 = GameTime * 0.0028;
    float t2 = GameTime * 0.0016;

    // Base void: barely violet, and darker straight down so the floor gap feels bottomless.
    vec3 col = vec3(0.004, 0.003, 0.010);
    col *= 1.0 - 0.55 * clamp(-dir.y, 0.0, 1.0);

    // Inner shell: vast slow smoke folds, dark indigo. Domain-warped so nothing tiles.
    vec3 pA = shellDir(dir, Radius * 0.55);
    float warp = fbm(pA * 1.7 + vec3(0.0, t2, 0.0));
    float fold = fbm(pA * 2.6 + vec3(warp * 1.4 - t1, 0.0, t1 * 0.6));
    fold = smoothstep(0.52, 0.86, fold);
    col += vec3(0.030, 0.014, 0.062) * fold * breath;

    // Curtains: tall banners hanging around the horizon, stretched hard vertically so they
    // read as hanging cloth rather than cloud, slowly rippling sideways.
    vec3 pD = shellDir(dir, Radius * 0.66);
    float curtain = fbm(pD * vec3(4.6, 0.7, 4.6) + vec3(t1 * 0.8, -t2 * 2.4, warp * 0.6));
    curtain = pow(smoothstep(0.46, 0.84, curtain), 2.0);
    float band = 1.0 - smoothstep(0.10, 0.70, abs(pD.y));
    col += vec3(0.060, 0.024, 0.130) * curtain * band * (0.55 + 0.45 * breath);

    // Middle shell: thin ridged veins that pinch out of the folds, hot cores where two
    // ridges cross. The hue slides between violet and a colder blue across the field.
    vec3 pB = shellDir(dir, Radius * 0.78);
    float nB = fbm(pB * 4.2 + vec3(t1, -t2, warp * 0.8));
    float ridge = 1.0 - abs(nB * 2.0 - 1.0);
    float vein = pow(smoothstep(0.80, 0.985, ridge), 2.0);
    // Mostly violet; the cold blue only takes over in occasional drifting pockets, instead
    // of averaging into a flat blue cast over the whole field.
    float hue = smoothstep(0.60, 0.85, vnoise(pB * 1.1 + vec3(0.0, t2, 0.0)));
    vec3 veinTint = mix(vec3(0.100, 0.034, 0.190), vec3(0.030, 0.070, 0.190), hue);
    col += veinTint * vein * (0.55 + 0.45 * breath);
    col += vec3(0.180, 0.100, 0.300) * pow(vein, 4.0) * fold;

    // Outer shell: every ~45 seconds a circular wave rolls across the whole dark from a
    // random direction, lighting the folds it passes over. Something moved out there.
    vec3 pC = shellDir(dir, Radius);
    float cyc = floor(GameTime / 900.0);
    float ph = fract(GameTime / 900.0);
    vec3 rdir = normalize(vec3(hash(vec3(cyc, 1.7, 9.1)) - 0.5,
                               (hash(vec3(cyc, 4.3, 2.9)) - 0.5) * 0.6,
                               hash(vec3(cyc, 8.2, 5.5)) - 0.5) + vec3(0.001));
    float angTo = acos(clamp(dot(pC, rdir), -1.0, 1.0));
    float ring = 1.0 - smoothstep(0.0, 0.38, abs(angTo - ph * 3.4));
    float ringEnv = sin(min(ph * 4.0, 1.0) * 1.5708) * (1.0 - smoothstep(0.5, 0.9, ph));
    col += vec3(0.095, 0.045, 0.190) * ring * ringEnv * (0.35 + 0.65 * fold);

    // Sparse pinpricks that wink open and shut on their own long phases.
    // Not stars. Too few, too dim, too deliberate.
    vec3 cell = floor(pC * 34.0);
    float g = hash(cell);
    if (g > 0.9965) {
        vec3 f = fract(pC * 34.0) - 0.5;
        float d = length(f);
        float phase = hash(cell + 11.7) * 6.2831;
        float wink = pow(max(sin(GameTime * (0.006 + 0.010 * hash(cell + 3.1)) + phase), 0.0), 6.0);
        col += vec3(0.30, 0.24, 0.42) * wink * smoothstep(0.30, 0.02, d);
    }

    fragColor = vec4(col, 1.0);
}
