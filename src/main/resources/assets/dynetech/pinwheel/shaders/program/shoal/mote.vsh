// Vertex-pulling billboard draw for the Shoal. No vertex buffer: each mote is six vertices of
// two triangles, indexed straight out of the sim SSBO by gl_VertexID. The manager draws the
// first lodCount * 6 vertices, so distance LOD is just a smaller draw call over the same pool.

#include dynetech:shoal

layout(std430, binding = 3) readonly buffer ShoalMotes {
    ShoalMote motes[];
};

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;
uniform vec3 AnchorCamPos; // anchor position minus camera position
uniform float SizeScale;

out vec2 vUv;
out float vAlpha;
out vec3 vTint;

const vec2 CORNERS[6] = vec2[](
        vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
        vec2(1.0, 1.0), vec2(-1.0, 1.0), vec2(-1.0, -1.0));

void main() {
    uint mi = uint(gl_VertexID) / 6u;
    ShoalMote m = motes[mi];
    vec2 corner = CORNERS[gl_VertexID % 6];
    vUv = corner * 0.5 + 0.5;

    // Fast fade in over the first 8% of life, fast fade out over the last 8%, full alpha in
    // between. Dead motes (age past life, or parked by the collapse) fall out here.
    float t = m.posAge.w / max(1.0, m.velLife.w);
    float alpha = clamp(min(t, 1.0 - t) / 0.08, 0.0, 1.0);
    if (alpha <= 0.001) {
        vAlpha = 0.0;
        vTint = vec3(1.0);
        gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
        return;
    }

    // Stable per-mote character rolled from the pool index and swarm seed: a slight warm or
    // cool lean inside the blue palette, and each mote's own twinkle rate and phase. Flat
    // uniform dots read as a texture; this is what makes the cloud look like ten thousand
    // individuals.
    uint h = shoalHash(mi ^ shoalHash(uint(StateFlags.z)));
    float h1 = float(h & 1023u) * (1.0 / 1023.0);
    float h2 = float((h >> 10u) & 1023u) * (1.0 / 1023.0);
    float h3 = float((h >> 20u) & 1023u) * (1.0 / 1023.0);
    vTint = mix(vec3(0.80, 0.93, 1.12), vec3(1.15, 1.06, 0.94), h3);
    // Excitement quickens the shimmer across the whole cloud.
    float twinkleTime = AnchorWorld.w * (1.0 + Tuning.x * 0.8);
    alpha *= 0.82 + 0.18 * sin(twinkleTime * (0.25 + 0.35 * h1) + h2 * SHOAL_TAU);

    // Camera basis from the view rotation, so every mote shares one set of axes.
    vec3 right = vec3(ModelViewMat[0][0], ModelViewMat[1][0], ModelViewMat[2][0]);
    vec3 up = vec3(ModelViewMat[0][1], ModelViewMat[1][1], ModelViewMat[2][1]);
    vec3 fwd = vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);

    // Fast motes stretch into streaks along their screen-space motion, dimming as they
    // lengthen so the streak carries the same light as the dot it replaces. Bursts, the
    // vacuum, and quick flow turns read as motion instead of teleporting dots.
    float halfSize = m.shape.z * SizeScale;
    vec3 velPlane = m.velLife.xyz - fwd * dot(m.velLife.xyz, fwd);
    float sp = length(velPlane);
    vec3 offset;
    if (sp > 0.035) {
        vec3 dirS = velPlane / sp;
        float s = min(sp * 7.0, 2.5);
        vec3 upS = normalize(cross(fwd, dirS));
        offset = (dirS * corner.x * (1.0 + s) + upS * corner.y) * halfSize;
        alpha /= 1.0 + s * 0.45;
    } else {
        offset = (right * corner.x + up * corner.y) * halfSize;
    }

    vec3 pos = AnchorCamPos + m.posAge.xyz + offset;
    // Fade out right at the camera so walking through the cloud dissolves around the player
    // instead of slapping full-screen quads across the view.
    alpha *= smoothstep(0.25, 1.0, length(pos));

    vAlpha = alpha;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
}
