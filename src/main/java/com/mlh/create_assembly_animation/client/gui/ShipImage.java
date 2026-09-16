package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ShipImage {

    static final String[] SHIP = {
            "...A.....",
            "..CCC.C..",
            "OOOOOOOOO",
            ".SSSSSSS.",
            "..SSSSS..",
    };
    static final int COLUMNS = SHIP[0].length();
    static final int ROWS = SHIP.length;
    static final int TEXELS = 16;

    private static final int WIDTH = COLUMNS * TEXELS;
    private static final int HEIGHT = ROWS * TEXELS;
    private static final int ASSEMBLER_COLUMN = SHIP[0].indexOf('A');

    private static final ResourceLocation BLOCKS_ID = CreateAssemblyAnimation.asResource("config_preview/blocks");
    private static final ResourceLocation DIAGRAM_ID = CreateAssemblyAnimation.asResource("config_preview/diagram");

    private static final float SOFTNESS = 0.35f;
    private static final float EXPOSURE = 0.41f;
    private static final float SOUTH_SHADE = 0.8f;
    private static final float[][] PALETTE = {
            {0.188f, 0.192f, 0.200f},
            {0.224f, 0.227f, 0.235f},
            {0.349f, 0.353f, 0.341f},
            {0.478f, 0.475f, 0.451f},
            {0.604f, 0.596f, 0.557f},
            {0.733f, 0.718f, 0.667f},
            {0.831f, 0.812f, 0.749f},
            {0.882f, 0.863f, 0.796f},
    };
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    private static final int INK = 0xFF2E3032;
    private static final int INK_SHADOW = 0xFF696965;

    private static final float[] DISTANCE = new float[COLUMNS * ROWS];
    private static final float[] NOISE = new float[COLUMNS * ROWS];
    private static final float[] KEY = new float[COLUMNS * ROWS];

    private static final RandomSource RANDOM = RandomSource.create();

    @Nullable
    private static int[] blockPixels;
    @Nullable
    private static int[] diagramPixels;
    @Nullable
    private static DynamicTexture diagram;

    static {
        float reach = 1f;
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                final int index = row * COLUMNS + column;
                final float dx = column - ASSEMBLER_COLUMN;
                final float dy = -row;
                DISTANCE[index] = (float) Math.sqrt(dx * dx + dy * dy);
                final double hash = Math.sin(dx * 12.9898 + dy * 78.233) * 43758.5453;
                NOISE[index] = (float) (hash - Math.floor(hash));
                if (solid(column, row))
                    reach = Math.max(reach, DISTANCE[index]);
            }
        }
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (DISTANCE[i] + NOISE[i] * 0.9f) / (reach + 0.9f);
            DISTANCE[i] = Math.min(DISTANCE[i] / reach, 1f);
        }
    }

    private ShipImage() {
    }

    static boolean solid(final int column, final int row) {
        return row >= 0 && row < ROWS && column >= 0 && column < COLUMNS && SHIP[row].charAt(column) != '.';
    }

    static boolean assembler(final int column, final int row) {
        return SHIP[row].charAt(column) == 'A';
    }

    static float distance(final int column, final int row) {
        return DISTANCE[row * COLUMNS + column];
    }

    static float noise(final int column, final int row) {
        return NOISE[row * COLUMNS + column];
    }

    static void invalidate() {
        if (blockPixels != null) {
            final TextureManager textures = Minecraft.getInstance().getTextureManager();
            textures.release(BLOCKS_ID);
            textures.release(DIAGRAM_ID);
        }
        blockPixels = null;
        diagramPixels = null;
        diagram = null;
    }

    static void drawBlocks(final GuiGraphics graphics, final int left, final int top, final int cell) {
        ensureBuilt();
        blitCells(graphics, BLOCKS_ID, left, top, cell);
    }

    static void drawDiagram(final GuiGraphics graphics, final int left, final int top, final int cell,
                            final float reveal, final float erase, final boolean eraseFromFar, final float[] strokes) {
        ensureBuilt();
        final int[] pixels = diagramPixels;
        final NativeImage image = diagram.getPixels();
        final boolean redraw = reveal > 0f && erase < 1f;
        final float revealFront = front(reveal);
        final float eraseFront = front(erase);

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = 0;
                final int pixel = pixels[y * WIDTH + x];
                if (redraw && pixel != 0) {
                    final float key = KEY[(y / TEXELS) * COLUMNS + x / TEXELS];
                    final float band = threshold(x, y) * SOFTNESS;
                    if (key + band < revealFront && (eraseFromFar ? 1f - key : key) + band >= eraseFront)
                        color = pixel;
                }
                image.setPixelRGBA(x, y, abgr(color));
            }
        }

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                final float stroke = strokes[row * COLUMNS + column];
                if (stroke <= 0f || !solid(column, row))
                    continue;

                final int length = Mth.clamp(Math.round(TEXELS * stroke), 0, TEXELS);
                final int ox = column * TEXELS;
                final int oy = row * TEXELS;
                final int last = TEXELS - 1;
                for (int i = 0; i < length; i++) {
                    if (!solid(column, row - 1))
                        image.setPixelRGBA(ox + i, oy, abgr(INK_SHADOW));
                    if (!solid(column - 1, row))
                        image.setPixelRGBA(ox, oy + i, abgr(INK_SHADOW));
                    if (!solid(column, row + 1))
                        image.setPixelRGBA(ox + last - i, oy + last, abgr(INK));
                    if (!solid(column + 1, row))
                        image.setPixelRGBA(ox + last, oy + last - i, abgr(INK));
                }
            }
        }

        diagram.upload();
        blitCells(graphics, DIAGRAM_ID, left, top, cell);
    }

    private static void blitCells(final GuiGraphics graphics, final ResourceLocation texture, final int left,
                                  final int top, final int cell) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                if (solid(column, row))
                    graphics.blit(texture, left + column * cell, top + row * cell, cell, cell,
                            column * TEXELS, row * TEXELS, TEXELS, TEXELS, WIDTH, HEIGHT);
            }
        }
        RenderSystem.disableBlend();
    }

    private static void ensureBuilt() {
        if (blockPixels != null)
            return;

        final int[] blocks = new int[WIDTH * HEIGHT];
        final BlockState[] states = {
                state("simulated:physics_assembler"),
                state("create:andesite_casing"),
                state("minecraft:oak_planks"),
                state("minecraft:spruce_planks"),
        };
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                final BlockState state = switch (SHIP[row].charAt(column)) {
                    case 'A' -> states[0];
                    case 'C' -> states[1];
                    case 'O' -> states[2];
                    case 'S' -> states[3];
                    default -> null;
                };
                if (state != null)
                    rasterize(blocks, state, column * TEXELS, row * TEXELS);
            }
        }

        final int[] redrawn = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                final int pixel = blocks[y * WIDTH + x];
                if (pixel != 0)
                    redrawn[y * WIDTH + x] = diagramColor(pixel, threshold(x, y));
            }
        }

        final NativeImage blockImage = new NativeImage(WIDTH, HEIGHT, true);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++)
                blockImage.setPixelRGBA(x, y, abgr(blocks[y * WIDTH + x]));
        }

        final TextureManager textures = Minecraft.getInstance().getTextureManager();
        textures.register(BLOCKS_ID, new DynamicTexture(blockImage));
        diagram = new DynamicTexture(new NativeImage(WIDTH, HEIGHT, true));
        textures.register(DIAGRAM_ID, diagram);

        blockPixels = blocks;
        diagramPixels = redrawn;
    }

    private static BlockState state(final String id) {
        final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return (block == Blocks.AIR ? Blocks.OAK_PLANKS : block).defaultBlockState();
    }

    private static void rasterize(final int[] target, final BlockState state, final int originX, final int originY) {
        final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        final List<BakedQuad> quads = new ArrayList<>();
        RANDOM.setSeed(42L);
        quads.addAll(model.getQuads(state, Direction.SOUTH, RANDOM));
        RANDOM.setSeed(42L);
        for (final BakedQuad quad : model.getQuads(state, null, RANDOM)) {
            if (quad.getDirection() == Direction.SOUTH)
                quads.add(quad);
        }
        quads.sort(Comparator.comparingDouble(ShipImage::depth));

        for (final BakedQuad quad : quads) {
            final int[] vertices = quad.getVertices();
            final float x0 = element(vertices, 0, IQuadTransformer.POSITION);
            final float y0 = element(vertices, 0, IQuadTransformer.POSITION + 1);
            final float ex1 = element(vertices, 1, IQuadTransformer.POSITION) - x0;
            final float ey1 = element(vertices, 1, IQuadTransformer.POSITION + 1) - y0;
            final float ex2 = element(vertices, 3, IQuadTransformer.POSITION) - x0;
            final float ey2 = element(vertices, 3, IQuadTransformer.POSITION + 1) - y0;
            final float det = ex1 * ey2 - ex2 * ey1;
            if (Math.abs(det) < 1e-6f)
                continue;

            final float u0 = element(vertices, 0, IQuadTransformer.UV0);
            final float v0 = element(vertices, 0, IQuadTransformer.UV0 + 1);
            final float du1 = element(vertices, 1, IQuadTransformer.UV0) - u0;
            final float dv1 = element(vertices, 1, IQuadTransformer.UV0 + 1) - v0;
            final float du2 = element(vertices, 3, IQuadTransformer.UV0) - u0;
            final float dv2 = element(vertices, 3, IQuadTransformer.UV0 + 1) - v0;

            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                final float vx = element(vertices, i, IQuadTransformer.POSITION);
                final float vy = element(vertices, i, IQuadTransformer.POSITION + 1);
                minX = Math.min(minX, vx);
                maxX = Math.max(maxX, vx);
                minY = Math.min(minY, vy);
                maxY = Math.max(maxY, vy);
            }

            final TextureAtlasSprite sprite = quad.getSprite();
            final SpriteContents contents = sprite.contents();
            final NativeImage texture = contents.getOriginalImage();
            final float spanU = sprite.getU1() - sprite.getU0();
            final float spanV = sprite.getV1() - sprite.getV0();

            for (int ty = 0; ty < TEXELS; ty++) {
                for (int tx = 0; tx < TEXELS; tx++) {
                    final float px = (tx + 0.5f) / TEXELS;
                    final float py = 1f - (ty + 0.5f) / TEXELS;
                    if (px < minX || px > maxX || py < minY || py > maxY)
                        continue;

                    final float dx = px - x0;
                    final float dy = py - y0;
                    final float s = (dx * ey2 - ex2 * dy) / det;
                    final float t = (ex1 * dy - dx * ey1) / det;
                    final float u = u0 + s * du1 + t * du2;
                    final float v = v0 + s * dv1 + t * dv2;

                    final int ix = Mth.clamp((int) ((u - sprite.getU0()) / spanU * contents.width()), 0, contents.width() - 1);
                    final int iy = Mth.clamp((int) ((v - sprite.getV0()) / spanV * contents.height()), 0, contents.height() - 1);
                    final int abgr = texture.getPixelRGBA(ix, iy);
                    if ((abgr >>> 24) < 128)
                        continue;

                    target[(originY + ty) * WIDTH + originX + tx] = 0xFF000000
                            | (abgr & 0xFF) << 16 | (abgr >> 8 & 0xFF) << 8 | (abgr >> 16 & 0xFF);
                }
            }
        }
    }

    private static float element(final int[] vertices, final int vertex, final int offset) {
        return Float.intBitsToFloat(vertices[vertex * IQuadTransformer.STRIDE + offset]);
    }

    private static double depth(final BakedQuad quad) {
        final int[] vertices = quad.getVertices();
        float sum = 0f;
        for (int i = 0; i < 4; i++)
            sum += element(vertices, i, IQuadTransformer.POSITION + 2);
        return sum;
    }

    private static float threshold(final int x, final int y) {
        return (BAYER[(x & 3) + (y & 3) * 4] + 0.5f) / 16f;
    }

    private static float front(final float progress) {
        return Mth.clamp(progress, 0f, 1f) * (1f + 2f * SOFTNESS) - SOFTNESS;
    }

    private static int diagramColor(final int argb, final float threshold) {
        final float r = (argb >> 16 & 0xFF) / 255f;
        final float g = (argb >> 8 & 0xFF) / 255f;
        final float b = (argb & 0xFF) / 255f;

        float luminosity = (0.299f * r + 0.587f * g + 0.114f * b) * SOUTH_SHADE * EXPOSURE;
        luminosity *= 1.7f;
        luminosity = Mth.clamp((luminosity - 0.5f + 0.18f) * 1.8f + 0.5f, 0f, 1f);
        luminosity = Mth.floor(luminosity * 32f) / 32f;

        final float columns = 11f;
        luminosity = Math.max(luminosity - 0.00001f, 0f);
        final float step = Mth.floor(luminosity * columns);
        final float lower = step / columns;
        final float upper = (step + 1f) / columns;
        final float between = luminosity * columns - step;
        final float sampleAt = between >= threshold * 0.99f + 0.005f ? upper : lower;

        final float position = Mth.clamp(sampleAt * 8f - 0.5f, 0f, 7f);
        final int index = Mth.floor(position);
        final float blend = position - index;
        final float[] from = PALETTE[index];
        final float[] to = PALETTE[Math.min(index + 1, 7)];
        return 0xFF000000
                | channel(Mth.lerp(blend, from[0], to[0])) << 16
                | channel(Mth.lerp(blend, from[1], to[1])) << 8
                | channel(Mth.lerp(blend, from[2], to[2]));
    }

    private static int channel(final float value) {
        return Mth.clamp(Math.round(value * 255f), 0, 255);
    }

    private static int abgr(final int argb) {
        return argb & 0xFF00FF00 | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;
    }
}
