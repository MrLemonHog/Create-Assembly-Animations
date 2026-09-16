#version 150

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D Mask;

uniform mat4 InvViewProj;
uniform mat4 SceneToLocal;

uniform float Reveal;
uniform float Erase;
uniform float EraseFromFar;
uniform float Softness;
uniform float Reach;
uniform float Exposure;

in vec2 screenUv;

out vec4 fragColor;

const vec3 PALETTE[8] = vec3[8](
    vec3(0.188, 0.192, 0.200),
    vec3(0.224, 0.227, 0.235),
    vec3(0.349, 0.353, 0.341),
    vec3(0.478, 0.475, 0.451),
    vec3(0.604, 0.596, 0.557),
    vec3(0.733, 0.718, 0.667),
    vec3(0.831, 0.812, 0.749),
    vec3(0.882, 0.863, 0.796)
);

const float BAYER[16] = float[16](
     0.0,  8.0,  2.0, 10.0,
    12.0,  4.0, 14.0,  6.0,
     3.0, 11.0,  1.0,  9.0,
    15.0,  7.0, 13.0,  5.0
);

float hash(vec3 cell) {
    return fract(sin(dot(cell, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

void main() {
    vec4 mask = texture(Mask, screenUv);
    if (mask.r - mask.g < 0.5 / 255.0) {
        discard;
    }

    float depth = texture(SceneDepth, screenUv).r;
    vec4 world = InvViewProj * vec4(screenUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    world /= world.w;
    vec3 local = (SceneToLocal * vec4(world.xyz, 1.0)).xyz;

    vec3 eye = (SceneToLocal * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    vec3 normal = normalize(cross(dFdx(local), dFdy(local)));
    if (dot(normal, eye - local) < 0.0) {
        normal = -normal;
    }
    vec3 axis = abs(normal);
    vec2 plane = axis.x > axis.y && axis.x > axis.z ? local.zy : (axis.y > axis.z ? local.xz : local.xy);
    ivec2 pixel = ivec2(floor(plane * 16.0));
    float threshold = (BAYER[(pixel.x & 3) + (pixel.y & 3) * 4] + 0.5) / 16.0;

    vec3 cell = floor(local - normal * 0.02);
    float key = (length(cell) + hash(cell) * 0.9) / Reach;
    if (key + threshold * Softness >= Reveal) {
        discard;
    }
    float eraseKey = EraseFromFar > 0.5 ? 1.0 - key : key;
    if (eraseKey + threshold * Softness < Erase) {
        discard;
    }

    vec3 scene = texture(SceneColor, screenUv).rgb;
    float luminosity = dot(scene, vec3(0.299, 0.587, 0.114)) * Exposure;
    luminosity *= 1.7;
    luminosity = clamp((luminosity - 0.5 + 0.18) * 1.8 + 0.5, 0.0, 1.0);
    luminosity = floor(luminosity * 32.0) / 32.0;

    const float columns = 11.0;
    luminosity = max(luminosity - 0.00001, 0.0);
    float lower = floor(luminosity * columns) / columns;
    float upper = (floor(luminosity * columns) + 1.0) / columns;
    float between = luminosity * columns - floor(luminosity * columns);

    float sampleAt = mix(lower, upper, step(threshold * 0.99 + 0.005, between));

    float position = clamp(sampleAt * 8.0 - 0.5, 0.0, 7.0);
    int index = int(floor(position));
    vec3 colour = mix(PALETTE[index], PALETTE[min(index + 1, 7)], fract(position));

    fragColor = vec4(colour, 1.0);
}
