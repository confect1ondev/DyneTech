// Shared between shoal/sim.comp and shoal/mote.vsh. The ShoalParams block layout must match
// the std140 serialization in ShoalSwarmState.write field for field, and the ShoalMote struct
// must match the 64-byte stride the manager allocates.

#define SHOAL_TRAIL_LEN 32

#define SHOAL_STATE_HOLD 0
#define SHOAL_STATE_DRIFT 1
#define SHOAL_STATE_FLOW 2
#define SHOAL_STATE_COLLAPSE 3
#define SHOAL_STATE_ORBIT 4
#define SHOAL_STATE_INSPECT 5
#define SHOAL_STATE_TORNADO 6

#define SHOAL_FLAG_BURST 1
#define SHOAL_FLAG_STILL 2
#define SHOAL_FLAG_FIRST 4
#define SHOAL_FLAG_PRIMARY 8

#define SHOAL_TAU 6.2831853

struct ShoalMote {
    vec4 posAge;   // xyz anchor-relative position, w age in ticks
    vec4 velLife;  // xyz velocity per tick, w lifetime in ticks
    vec4 shape;    // x home shell fraction, y trail slot, z half-size, w branch index
    vec4 personal; // xyz personal lateral offset from the strand center
};

layout(std140, binding = 2) uniform ShoalParams {
    vec4 AnchorDelta;    // xyz anchor movement since last frame, w tempo-scaled dt in ticks
    vec4 AnchorWorld;    // xyz absolute anchor, only for noise sampling, w tempo-scaled clock
    vec4 Interest;       // xyz interest point relative to anchor, w active flag
    vec4 Tuning;         // x excitement, y branchOpen, z noiseTime
    ivec4 StateFlags;    // x state, y flags, z seed, w frame
    ivec4 TrailInfo;     // x head index, y written count
    vec4 Trail[SHOAL_TRAIL_LEN];      // xyz anchor-relative trail point, w clearance to solids
    vec4 TrailAvoid[SHOAL_TRAIL_LEN]; // xy shove on the xz plane, z widen factor, w vertical shove
};

uint shoalHash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}
