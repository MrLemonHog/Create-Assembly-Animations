uniform sampler2D Fire;
uniform sampler2D FireAlt;

const float RESCALE = 1.0823922;
const float TILT = 0.39269908;
const float FLAME = 1.4;

vec4 fireTexture(vec2 uv, float variant) {
    float index = mod(floor(Time * 20.0), 32.0);
    if (variant < 0.5)
        return frame(Fire, 32.0, index, vec2(uv.x, 1.0 - uv.y));
    return frame(FireAlt, 32.0, index, vec2(uv.x, 1.0 - uv.y));
}

float igniteAt(vec3 block) {
    float height = max(BoundsMax.y - BoundsMin.y, 1.0);
    return (block.y - BoundsMin.y) / height * 0.6 + hash(block) * 0.1;
}

float wallFire(vec3 block) {
    if (Stage != ASSEMBLY)
        return 0.0;
    return step(igniteAt(block), Progress) * (1.0 - smoothstep(0.86 + hash(block) * 0.06, 0.98, Progress));
}

float topFire(vec3 block) {
    float dying = 1.0 - smoothstep(0.78 + hash(block) * 0.12, 1.0, Progress);
    if (Stage == ALIGNMENT)
        return 1.0;
    if (Stage == DISASSEMBLY)
        return dying;
    return step(igniteAt(block), Progress) * dying;
}

void element(vec3 from, vec3 direction, bool alongX, float base, float angle, float variant, inout float nearest,
             inout vec4 colour) {
    vec3 e = from - vec3(0.5);
    vec3 d = direction;
    float c = cos(angle);
    float s = sin(angle);
    vec3 p;
    float t;

    if (alongX) {
        e.yz /= RESCALE;
        d.yz /= RESCALE;
        e = vec3(e.x, e.y * c + e.z * s, -e.y * s + e.z * c) + vec3(0.5);
        d = vec3(d.x, d.y * c + d.z * s, -d.y * s + d.z * c);
        if (abs(d.z) < 1.0e-6)
            return;
        t = (base - e.z) / d.z;
        p = e + d * t;
        if (t <= 0.0 || t >= nearest || p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > FLAME)
            return;
        vec4 flame = fireTexture(vec2(p.x, p.y / FLAME), variant);
        if (flame.a < 0.5)
            return;
        nearest = t;
        colour = flame;
    } else {
        e.xy /= RESCALE;
        d.xy /= RESCALE;
        e = vec3(e.x * c + e.y * s, -e.x * s + e.y * c, e.z) + vec3(0.5);
        d = vec3(d.x * c + d.y * s, -d.x * s + d.y * c, d.z);
        if (abs(d.x) < 1.0e-6)
            return;
        t = (base - e.x) / d.x;
        p = e + d * t;
        if (t <= 0.0 || t >= nearest || p.z < 0.0 || p.z > 1.0 || p.y < 0.0 || p.y > FLAME)
            return;
        vec4 flame = fireTexture(vec2(p.z, p.y / FLAME), variant);
        if (flame.a < 0.5)
            return;
        nearest = t;
        colour = flame;
    }
}

void side(vec3 from, vec3 direction, bool alongX, float base, float seed, inout float nearest, inout vec4 colour) {
    float start = alongX ? from.z : from.x;
    float speed = alongX ? direction.z : direction.x;
    if (abs(speed) < 1.0e-6)
        return;
    float t = (base - start) / speed;
    if (t <= 0.0 || t >= nearest)
        return;
    vec3 p = from + direction * t;
    float across = alongX ? p.x : p.z;
    if (across < 0.0 || across > 1.0 || p.y < 0.0 || p.y > FLAME)
        return;
    bool mirrored = hash(vec3(seed, 3.1, 7.7)) > 0.5;
    vec4 flame = fireTexture(vec2(mirrored ? 1.0 - across : across, p.y / FLAME), step(0.5, hash(vec3(seed, 9.3, 1.9))));
    if (flame.a < 0.5)
        return;
    nearest = t;
    colour = flame;
}

vec4 flamesOnTop(Pixel pixel, out float nearest) {
    nearest = pixel.depth;
    vec4 colour = vec4(0.0);

    vec3 low = vec3(BoundsMin.x - 0.5, BoundsMin.y, BoundsMin.z - 0.5);
    vec3 high = vec3(BoundsMax.x + 0.5, BoundsMax.y + FLAME + 0.2, BoundsMax.z + 0.5);
    vec3 inverse = 1.0 / max(abs(pixel.ray), vec3(1.0e-6)) * sign(pixel.ray + vec3(1.0e-9));
    vec3 a = (low - pixel.eye) * inverse;
    vec3 b = (high - pixel.eye) * inverse;
    float enter = max(max(min(a, b).x, min(a, b).y), min(a, b).z);
    float leave = min(min(max(a, b).x, max(a, b).y), max(a, b).z);
    enter = max(enter, 0.0);
    leave = min(leave, pixel.depth);
    if (enter >= leave)
        return colour;

    vec3 start = pixel.eye + pixel.ray * (enter + 1.0e-4);
    ivec2 cell = ivec2(floor(start.xz));
    ivec2 stride = ivec2(sign(pixel.ray.xz));
    vec2 across = abs(1.0 / max(abs(pixel.ray.xz), vec2(1.0e-6)));
    vec2 edge = vec2(cell) + max(vec2(stride), vec2(0.0));
    vec2 next = (edge - pixel.eye.xz) * inverse.xz;
    float reached = enter;

    for (int walked = 0; walked < 96; walked++) {
        if (reached > leave || reached > nearest)
            break;

        for (int beside = -1; beside <= 1; beside++) {
            ivec2 column = cell + (abs(pixel.ray.x) > abs(pixel.ray.z) ? ivec2(0, beside) : ivec2(beside, 0));
            float top = topAt(vec2(column) + 0.5);
            if (top < -1.0e5)
                continue;
            vec3 block = vec3(float(column.x), top - 1.0, float(column.y));
            if (topFire(block) <= 0.0)
                continue;

            vec3 from = pixel.eye - vec3(float(column.x), top, float(column.y));
            float variant = step(0.5, hash(block + vec3(0.0, 17.0, 0.0)));
            element(from, pixel.ray, true, 0.55, -TILT, variant, nearest, colour);
            element(from, pixel.ray, true, 0.45, TILT, variant, nearest, colour);
            element(from, pixel.ray, false, 0.55, -TILT, variant, nearest, colour);
            element(from, pixel.ray, false, 0.45, TILT, variant, nearest, colour);

            float seed = hash(block) * 97.0;
            side(from, pixel.ray, true, 0.01, seed, nearest, colour);
            side(from, pixel.ray, false, 0.99, seed + 1.0, nearest, colour);
            side(from, pixel.ray, true, 0.99, seed + 2.0, nearest, colour);
            side(from, pixel.ray, false, 0.01, seed + 3.0, nearest, colour);
        }

        if (next.x < next.y) {
            reached = next.x;
            next.x += across.x;
            cell.x += stride.x;
        } else {
            reached = next.y;
            next.y += across.y;
            cell.y += stride.y;
        }
    }
    return colour;
}

vec4 animate(Pixel pixel) {
    float nearest;
    vec4 flames = flamesOnTop(pixel, nearest);
    if (flames.a > 0.5)
        return vec4(flames.rgb * 1.15, 1.0);

    if (pixel.inside < 0.5)
        return vec4(pixel.color, 0.0);

    float burning = wallFire(pixel.block);
    float charred = Stage == ASSEMBLY
            ? smoothstep(igniteAt(pixel.block), igniteAt(pixel.block) + 0.12, Progress)
                    * (1.0 - smoothstep(0.88, 1.0, Progress))
            : 0.0;
    vec3 colour = pixel.color * (1.0 - 0.55 * charred);

    vec3 gap = min(pixel.inBlock, vec3(1.0) - pixel.inBlock);
    bool onSide = gap.y > min(gap.x, gap.z);
    if (burning > 0.0 && onSide) {
        float across = gap.x < gap.z ? pixel.inBlock.z : pixel.inBlock.x;
        vec4 flame = fireTexture(vec2(across, pixel.inBlock.y / FLAME), step(0.5, pixel.random));
        if (flame.a > 0.5)
            return vec4(flame.rgb * 1.15, 1.0);
    }

    float glow = max(burning, topFire(pixel.block) * step(0.5, pixel.inBlock.y) * 0.4);
    return vec4(colour + vec3(1.0, 0.45, 0.12) * glow * 0.25, max(charred, glow * 0.6));
}
