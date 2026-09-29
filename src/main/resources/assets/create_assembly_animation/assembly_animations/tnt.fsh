uniform sampler2D TntSide;
uniform sampler2D TntTop;
uniform sampler2D TntBottom;

float chainTime() {
    return min(1.6, Duration * 0.28);
}

float fuseTime() {
    return clamp(Duration - chainTime() - 1.2, 0.6, 4.0);
}

float blowsAt(vec3 block) {
    float reach = clamp(length(block + vec3(0.5)) / max(Reach, 1.0), 0.0, 1.0);
    return fuseTime() + reach * chainTime();
}

vec4 tntSkin(vec3 at) {
    vec3 gap = min(at, vec3(1.0) - at);

    if (gap.y <= gap.x && gap.y <= gap.z)
        return at.y > 0.5 ? texture(TntTop, vec2(at.x, at.z)) : texture(TntBottom, vec2(at.x, 1.0 - at.z));
    if (gap.x <= gap.z)
        return texture(TntSide, vec2(at.x > 0.5 ? 1.0 - at.z : at.z, 1.0 - at.y));
    return texture(TntSide, vec2(at.z > 0.5 ? at.x : 1.0 - at.x, 1.0 - at.y));
}

float blastNear(vec3 block) {
    float light = 0.0;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                float age = Time - blowsAt(block + vec3(dx, dy, dz));
                if (age > 0.0)
                    light += exp(-age * 7.0) / (1.0 + float(dx * dx + dy * dy + dz * dz));
            }
        }
    }
    return light;
}

vec4 animate(Pixel pixel) {
    bool standby = Stage == ALIGNMENT;
    vec3 cube;
    bool wholeBlock = onCubeFace(pixel, cube);
    float ticksLeft = (blowsAt(wholeBlock ? cube : pixel.block) - Time) * 20.0;
    bool primed = standby || ticksLeft > 0.0;

    float light = standby ? 0.0 : min(blastNear(pixel.block), 1.2);
    vec3 fire = vec3(1.0, 0.62, 0.25) * light * 0.7;

    if (primed && wholeBlock) {
        vec4 skin = tntSkin(clamp(pixel.pos - cube, 0.0, 1.0));
        float flash = standby ? step(0.5, fract(Time)) : step(mod(floor(ticksLeft / 5.0), 2.0), 0.5);
        float swell = standby ? 0.0 : pow(clamp(1.0 - ticksLeft / 10.0, 0.0, 1.0), 4.0);
        return vec4(mix(skin.rgb, vec3(1.0), clamp(flash * 0.85 + swell * 0.6, 0.0, 1.0)) + fire, 1.0);
    }

    float scorch = primed ? 0.0 : 0.45 * exp(-(Time - blowsAt(pixel.block)) * 1.2);
    return vec4(pixel.color * (1.0 - scorch) + fire, min(1.0, light * 0.8 + scorch));
}
