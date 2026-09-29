
uint mixBits(uint x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    x ^= x >> 16;
    return x;
}

float latticeHash(ivec3 cell) {
    uint h = mixBits(uint(cell.x) * 0x9E3779B1u ^ mixBits(uint(cell.y) + 0x85EBCA77u)
            ^ mixBits(uint(cell.z) * 0xC2B2AE3Du + 0x27D4EB2Fu));
    return float(h >> 8) / 16777216.0;
}

float valueNoise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = p - cell;
    vec3 s = f * f * (3.0 - 2.0 * f);
    ivec3 c = ivec3(cell);
    float x00 = mix(latticeHash(c), latticeHash(c + ivec3(1, 0, 0)), s.x);
    float x10 = mix(latticeHash(c + ivec3(0, 1, 0)), latticeHash(c + ivec3(1, 1, 0)), s.x);
    float x01 = mix(latticeHash(c + ivec3(0, 0, 1)), latticeHash(c + ivec3(1, 0, 1)), s.x);
    float x11 = mix(latticeHash(c + ivec3(0, 1, 1)), latticeHash(c + ivec3(1, 1, 1)), s.x);
    return mix(mix(x00, x10, s.y), mix(x01, x11, s.y), s.z);
}

float fbm(vec3 p) {
    return valueNoise(p) * 0.65 + valueNoise(p * 2.3 + vec3(17.1)) * 0.35;
}

float faceEdge(vec3 inBlock) {
    vec3 gap = min(inBlock, 1.0 - inBlock);
    float smallest = min(gap.x, min(gap.y, gap.z));
    float largest = max(gap.x, max(gap.y, gap.z));
    float middle = gap.x + gap.y + gap.z - smallest - largest;
    return middle;
}

vec3 desaturate(vec3 color, float amount) {
    return mix(color, vec3(luminance(color)), amount);
}
