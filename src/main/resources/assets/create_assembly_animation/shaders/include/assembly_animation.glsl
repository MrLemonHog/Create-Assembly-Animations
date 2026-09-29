uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D Mask;
uniform sampler2D Heights;
uniform sampler2D Solids;
uniform float SolidsGrid;

uniform mat4 InvViewProj;
uniform mat4 SceneToLocal;
uniform mat4 LocalToClip;

uniform float Progress;
uniform float Time;
uniform float Duration;
uniform int Stage;
uniform float Brightness;
uniform float Fade;
uniform float Daylight;
uniform vec3 BoundsMin;
uniform vec3 BoundsMax;
uniform float Reach;
uniform vec2 ScreenSize;
uniform int Area;
uniform float Margin;
uniform vec3 StartRight;

#define ASSEMBLY 0
#define ALIGNMENT 1
#define DISASSEMBLY 2

in vec2 screenUv;

out vec4 fragColor;

struct Pixel {
    vec3 color;
    vec3 pos;
    vec3 normal;
    vec3 block;
    vec3 inBlock;
    vec3 bounds;
    float distance;
    float random;
    vec2 screen;
    float inside;
    vec3 eye;
    vec3 ray;
    float depth;
};

float hash(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

vec2 toScreen(vec3 blockSpace) {
    vec4 clip = LocalToClip * vec4(blockSpace, 1.0);
    return (clip.xy / clip.w * 0.5 + 0.5) * vec2(ScreenSize.x / ScreenSize.y, 1.0);
}

vec4 frame(sampler2D sheet, float frames, float index, vec2 uv) {
    float picked = clamp(floor(index), 0.0, max(frames - 1.0, 0.0));
    return texture(sheet, vec2(uv.x, (picked + clamp(uv.y, 0.001, 0.999)) / max(frames, 1.0)));
}

float luminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

vec3 blockSpaceAt(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    vec4 world = InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return (SceneToLocal * vec4(world.xyz / world.w, 1.0)).xyz;
}

float topAt(vec2 column) {
    ivec2 cell = ivec2(floor(column - BoundsMin.xz));
    ivec2 size = ivec2(BoundsMax.xz - BoundsMin.xz + 0.5);
    if (cell.x < 0 || cell.y < 0 || cell.x >= size.x || cell.y >= size.y)
        return -1.0e6;
    vec4 stored = texelFetch(Heights, cell, 0);
    float code = floor(stored.r * 255.0 + 0.5) + floor(stored.g * 255.0 + 0.5) * 256.0;
    return code < 0.5 ? -1.0e6 : BoundsMin.y + code;
}

float cellAt(vec3 block) {
    ivec3 cell = ivec3(floor(block - BoundsMin));
    ivec3 size = ivec3(BoundsMax - BoundsMin + 0.5);
    if (cell.x < 0 || cell.y < 0 || cell.z < 0 || cell.x >= size.x || cell.y >= size.y || cell.z >= size.z)
        return 0.0;
    int columns = max(int(SolidsGrid + 0.5), 1);
    ivec2 texel = ivec2(cell.x + (cell.y % columns) * size.x, cell.z + (cell.y / columns) * size.z);
    return texelFetch(Solids, texel, 0).r;
}

bool fullCubeAt(vec3 block) {
    return cellAt(block) > 0.75;
}

bool occupiedAt(vec3 block) {
    return cellAt(block) > 0.25;
}

bool onCubeFace(Pixel pixel, out vec3 cube) {
    vec3 inside = pixel.pos - floor(pixel.pos);
    vec3 gap = min(inside, 1.0 - inside);
    vec3 axis = gap.x <= gap.y && gap.x <= gap.z ? vec3(1.0, 0.0, 0.0)
            : gap.y <= gap.z ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    cube = floor(pixel.pos);
    if (dot(gap, axis) > 0.04)
        return false;
    vec3 after = floor(pixel.pos) * (1.0 - axis) + floor(pixel.pos + 0.5) * axis;
    vec3 before = after - axis;
    bool solidBefore = fullCubeAt(before);
    if (solidBefore == fullCubeAt(after))
        return false;
    cube = solidBefore ? before : after;
    return true;
}

bool buildAt(vec2 uv) {
    vec4 mask = texture(Mask, uv);
    return mask.r - mask.g >= 0.5 / 255.0;
}

float along(Pixel pixel, float height) {
    if (abs(pixel.ray.y) < 1.0e-5)
        return -1.0;
    float distance = (height - pixel.eye.y) / pixel.ray.y;
    return distance > 0.0 && distance < pixel.depth ? distance : -1.0;
}

bool crossesBox(vec3 from, vec3 direction, vec3 low, vec3 high) {
    vec3 inverse = 1.0 / max(abs(direction), vec3(1.0e-6)) * sign(direction + vec3(1.0e-9));
    vec3 a = (low - from) * inverse;
    vec3 b = (high - from) * inverse;
    vec3 near = min(a, b);
    vec3 far = max(a, b);
    float enter = max(max(near.x, near.y), near.z);
    float leave = min(min(far.x, far.y), far.z);
    return enter <= leave && leave >= 0.0;
}

vec3 behind(vec2 uv) {
    vec2 texel = 1.0 / ScreenSize;
    float spin = fract(52.9829189 * fract(dot(floor(uv * ScreenSize), vec2(0.06711056, 0.00583715))));
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 16; i++) {
        float angle = (float(i) + spin) * 0.39269908;
        vec2 direction = vec2(cos(angle), sin(angle)) * texel;
        float inner = 0.0;
        float outer = 2.0;
        float stride = 2.0;
        bool found = false;
        for (int s = 0; s < 16 && !found; s++) {
            vec2 at = uv + direction * outer;
            if (at.x < 0.0 || at.y < 0.0 || at.x > 1.0 || at.y > 1.0)
                break;
            if (buildAt(at)) {
                inner = outer;
                stride *= 1.45;
                outer += stride;
            } else {
                found = true;
            }
        }
        if (!found)
            continue;
        for (int s = 0; s < 4; s++) {
            float middle = (inner + outer) * 0.5;
            if (buildAt(uv + direction * middle))
                inner = middle;
            else
                outer = middle;
        }
        float w = 1.0 / (outer * sqrt(outer));
        sum += texture(SceneColor, uv + direction * (outer + 1.5)).rgb * w;
        weight += w;
    }
    if (weight <= 0.0)
        return mix(vec3(0.04, 0.05, 0.08), vec3(0.62, 0.75, 0.95), Daylight);
    return sum / weight;
}

#ifdef WRITES_DEPTH
float vanished = 0.0;
#endif

vec4 animate(Pixel pixel);

void main() {
    vec4 mask = texture(Mask, screenUv);
    float inside = mask.r - mask.g < 0.5 / 255.0 ? 0.0 : 1.0;
    if (Area == 0 && inside < 0.5) {
        discard;
    }

    float depth = texture(SceneDepth, screenUv).r;
    vec4 world = InvViewProj * vec4(screenUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    world /= world.w;
    vec3 local = (SceneToLocal * vec4(world.xyz, 1.0)).xyz;

    vec4 near = InvViewProj * vec4(screenUv * 2.0 - 1.0, -1.0, 1.0);
    near /= near.w;
    vec3 eye = (SceneToLocal * vec4(near.xyz, 1.0)).xyz;
    vec3 ray = normalize(local - eye);

    if (Area == 1 && inside < 0.5 && !crossesBox(eye, ray, BoundsMin - vec3(Margin), BoundsMax + vec3(Margin))) {
        discard;
    }

    vec3 normal = normalize(cross(dFdx(local), dFdy(local)));
    if (dot(normal, eye - local) < 0.0) {
        normal = -normal;
    }

    Pixel pixel;
    pixel.color = texture(SceneColor, screenUv).rgb;
    pixel.pos = local;
    pixel.normal = normal;
    pixel.block = floor(local - normal * 0.02);
    pixel.inBlock = clamp(local - pixel.block, 0.0, 1.0);
    pixel.bounds = clamp((local - BoundsMin) / max(BoundsMax - BoundsMin, vec3(1.0)), 0.0, 1.0);
    pixel.distance = clamp(length(pixel.block + 0.5) / Reach, 0.0, 1.0);
    pixel.random = hash(pixel.block);
    pixel.screen = screenUv;
    pixel.inside = inside;
    pixel.eye = eye;
    pixel.ray = ray;
    pixel.depth = length(local - eye);

    vec4 result = animate(pixel);
#ifdef RAW_OUTPUT
    vec3 color = result.rgb;
#else
    vec3 color = mix(pixel.color, result.rgb, Brightness);
#endif
    float bound = step(-1.0e30, Progress + Time + Duration + float(Stage) + Daylight + Reach + ScreenSize.x
            + BoundsMin.x + BoundsMax.x + LocalToClip[3][3] + float(Area) + Margin + texelFetch(Heights, ivec2(0), 0).a
            + texelFetch(Solids, ivec2(0), 0).a + SolidsGrid
            + Brightness + StartRight.x);
    fragColor = vec4(max(color, vec3(0.0)), clamp(result.a, 0.0, 1.0) * Fade * bound);
#ifdef WRITES_DEPTH
    gl_FragDepth = vanished * Fade > 0.5 ? 1.0 : depth;
#endif
}
