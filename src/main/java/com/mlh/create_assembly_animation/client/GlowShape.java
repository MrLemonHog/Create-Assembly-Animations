package com.mlh.create_assembly_animation.client;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

final class GlowShape {

    static final Direction[][] EDGE_FACES = edgeFaces();

    private static final float JITTER = 0.9f;

    private static final int READINESS_SAMPLES = 48;

    final BlockPos origin;
    final int size;

    final int[] x;
    final int[] y;
    final int[] z;

    final byte[] faces;

    final short[] edges;

    final int[] shell;

    final float[] distance;
    final float maxDistance;

    final float[] noise;

    final int minX;
    final int maxX;
    final int minY;
    final int maxY;
    final int minZ;
    final int maxZ;

    final float centerX;
    final float centerY;
    final float centerZ;

    private final long[] positions;

    private GlowShape(final BlockPos origin, final long[] positions) {
        this.origin = origin.immutable();
        this.positions = positions;
        this.size = positions.length;

        this.x = new int[this.size];
        this.y = new int[this.size];
        this.z = new int[this.size];
        this.faces = new byte[this.size];
        this.edges = new short[this.size];
        this.distance = new float[this.size];
        this.noise = new float[this.size];

        final LongOpenHashSet set = new LongOpenHashSet(positions);
        final IntArrayList shell = new IntArrayList();

        float maxDistance = 0f;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;

        for (int i = 0; i < this.size; i++) {
            final long packed = positions[i];
            final int bx = BlockPos.getX(packed) - origin.getX();
            final int by = BlockPos.getY(packed) - origin.getY();
            final int bz = BlockPos.getZ(packed) - origin.getZ();
            this.x[i] = bx;
            this.y[i] = by;
            this.z[i] = bz;

            int mask = 0;
            for (final Direction d : Direction.values()) {
                if (!set.contains(BlockPos.offset(packed, d.getStepX(), d.getStepY(), d.getStepZ())))
                    mask |= 1 << d.ordinal();
            }
            this.faces[i] = (byte) mask;

            int edgeMask = 0;
            for (int e = 0; e < EDGE_FACES.length; e++) {
                if ((mask & (1 << EDGE_FACES[e][0].ordinal())) != 0 && (mask & (1 << EDGE_FACES[e][1].ordinal())) != 0)
                    edgeMask |= 1 << e;
            }
            this.edges[i] = (short) edgeMask;

            if (mask != 0)
                shell.add(i);

            final long hash = HashCommon.mix(packed);
            this.noise[i] = ((hash >>> 40) & 0xFFFF) / 65536f;
            this.distance[i] = (float) Math.sqrt(bx * bx + by * by + bz * bz) + ((hash >>> 20) & 0xFFFF) / 65535f * JITTER;

            maxDistance = Math.max(maxDistance, this.distance[i]);
            minX = Math.min(minX, bx);
            maxX = Math.max(maxX, bx);
            minY = Math.min(minY, by);
            maxY = Math.max(maxY, by);
            minZ = Math.min(minZ, bz);
            maxZ = Math.max(maxZ, bz);
            sumX += bx + 0.5;
            sumY += by + 0.5;
            sumZ += bz + 0.5;
        }

        this.shell = shell.toIntArray();
        this.maxDistance = maxDistance;
        this.minY = this.size == 0 ? 0 : minY;
        this.maxY = this.size == 0 ? 0 : maxY;
        this.minX = this.size == 0 ? 0 : minX;
        this.maxX = this.size == 0 ? 0 : maxX;
        this.minZ = this.size == 0 ? 0 : minZ;
        this.maxZ = this.size == 0 ? 0 : maxZ;
        this.centerX = this.size == 0 ? 0.5f : (float) (sumX / this.size);
        this.centerY = this.size == 0 ? 0.5f : (float) (sumY / this.size);
        this.centerZ = this.size == 0 ? 0.5f : (float) (sumZ / this.size);
    }

    static GlowShape of(final BlockPos origin, final long[] positions) {
        return new GlowShape(origin, positions);
    }

    static GlowShape single(final BlockPos pos) {
        return new GlowShape(pos, new long[]{pos.asLong()});
    }

    long position(final int block) {
        return this.positions[block];
    }

    int height() {
        return this.maxY - this.minY + 1;
    }

    float readiness(final ClientLevel level) {
        if (this.size == 0)
            return 1f;

        final int step = Math.max(1, this.size / READINESS_SAMPLES);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int sampled = 0;
        int present = 0;

        for (int i = 0; i < this.size; i += step) {
            sampled++;
            if (!level.getBlockState(pos.set(this.positions[i])).isAir())
                present++;
        }

        return present / (float) sampled;
    }

    private static Direction[][] edgeFaces() {
        final List<Direction[]> edges = new ArrayList<>();
        for (final Direction a : Direction.values()) {
            for (final Direction b : Direction.values()) {
                if (a.ordinal() < b.ordinal() && a.getAxis() != b.getAxis())
                    edges.add(new Direction[]{a, b});
            }
        }
        return edges.toArray(new Direction[0][]);
    }
}
