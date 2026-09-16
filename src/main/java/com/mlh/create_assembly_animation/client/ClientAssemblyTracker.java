package com.mlh.create_assembly_animation.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ClientAssemblyTracker {

    private static final int ASSEMBLY_WAIT_TICKS = 100;
    private static final int MAX_ALIGN_TICKS = 20 * 60;
    private static final int FORCE_REFRESH_TICKS = 10;
    private static final int MAX_BLOCKS = 131_072;

    private record PendingAssembly(BlockPos trigger, Vec3 worldCenter, long since, Set<UUID> known) {
    }

    private record Disassembly(ClientSubLevel subLevel, BlockPos assembler, long[] blocks, long since) {
    }

    private static final List<PendingAssembly> PENDING = new ArrayList<>();
    private static final Map<UUID, Disassembly> DISASSEMBLING = new HashMap<>();

    private ClientAssemblyTracker() {
    }

    public static void leverFlicked(final BlockPos assembler, final boolean assembling) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(assembling ? Phase.ASSEMBLY : Phase.DISASSEMBLY).enabled())
            return;

        final ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null)
            return;

        if (assembling) {
            final Set<UUID> known = new HashSet<>();
            for (final ClientSubLevel subLevel : container.getAllSubLevels())
                known.add(subLevel.getUniqueId());

            PENDING.removeIf(pending -> pending.trigger().equals(assembler));
            PENDING.add(new PendingAssembly(assembler.immutable(),
                    Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(assembler)), level.getGameTime(), known));
            AssemblyAnimations.charge(assembler);
            return;
        }

        if (!(Sable.HELPER.getContaining(level, assembler) instanceof final ClientSubLevel subLevel))
            return;

        final long[] blocks = plotBlocks(level, subLevel);
        DISASSEMBLING.put(subLevel.getUniqueId(), new Disassembly(subLevel, assembler.immutable(), blocks, level.getGameTime()));
        AssemblyAnimations.aligning(assembler, subLevel.getUniqueId(), blocks);
        Forces.request(subLevel.getUniqueId(), 2);
    }

    public static void failed(final BlockPos assembler) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;

        PENDING.removeIf(pending -> pending.trigger().equals(assembler));

        UUID subLevel = null;
        final Iterator<Disassembly> iterator = DISASSEMBLING.values().iterator();
        while (iterator.hasNext()) {
            final Disassembly disassembly = iterator.next();
            if (disassembly.assembler().equals(assembler)) {
                subLevel = disassembly.subLevel().getUniqueId();
                iterator.remove();
            }
        }
        if (subLevel == null && Sable.HELPER.getContaining(level, assembler) instanceof final SubLevel containing)
            subLevel = containing.getUniqueId();

        AssemblyAnimations.failed(assembler, subLevel);
    }

    static void tick(final ClientLevel level) {
        final ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null)
            return;

        final long now = level.getGameTime();
        PENDING.removeIf(pending -> now - pending.since() > ASSEMBLY_WAIT_TICKS || tryAssemble(level, container, pending));

        DISASSEMBLING.values().removeIf(disassembly -> {
            final ClientSubLevel subLevel = disassembly.subLevel();
            if (subLevel.isRemoved() || container.getSubLevel(subLevel.getUniqueId()) != subLevel) {
                placed(disassembly);
                return true;
            }

            final long age = now - disassembly.since();
            if (age > MAX_ALIGN_TICKS)
                return true;
            if (age > 0 && age % FORCE_REFRESH_TICKS == 0)
                Forces.request(subLevel.getUniqueId(), 0);
            return false;
        });
    }

    private static boolean tryAssemble(final ClientLevel level, final ClientSubLevelContainer container,
                                       final PendingAssembly pending) {
        for (final ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (pending.known().contains(subLevel.getUniqueId()) || !subLevel.isFinalized())
                continue;

            final BlockPos assembler = findAssembler(level, subLevel, pending.worldCenter());
            if (assembler == null)
                continue;

            final long[] blocks = plotBlocks(level, subLevel);
            AssemblyAnimations.assembled(pending.trigger(), assembler, subLevel.getUniqueId(), blocks);
            Forces.request(subLevel.getUniqueId(), 6);
            return true;
        }
        return false;
    }

    @Nullable
    private static BlockPos findAssembler(final ClientLevel level, final ClientSubLevel subLevel, final Vec3 worldCenter) {
        final BlockPos guess = BlockPos.containing(subLevel.logicalPose().transformPositionInverse(worldCenter));
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = 0; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    pos.set(guess.getX() + (dx == 2 ? -1 : dx), guess.getY() + (dy == 2 ? -1 : dy), guess.getZ() + (dz == 2 ? -1 : dz));
                    if (level.getBlockEntity(pos) instanceof PhysicsAssemblerBlockEntity)
                        return pos.immutable();
                }
            }
        }
        return null;
    }

    private static void placed(final Disassembly disassembly) {
        final Pose3dc pose = disassembly.subLevel().logicalPose();
        final BlockPos anchor = disassembly.assembler();
        final BlockPos goal = BlockPos.containing(pose.transformPosition(Vec3.atCenterOf(anchor)));

        final Quaterniondc orientation = pose.orientation();
        final double yaw = 2.0 * Math.atan2(-orientation.y(), orientation.w());
        final int quarterTurns = Math.floorMod(-Mth.floor(yaw / (Math.PI / 2.0) + 0.5), 4);
        final float turn = (float) (quarterTurns * Math.PI / 2.0);

        final Vec3 anchorCenter = Vec3.atCenterOf(anchor);
        final Vec3 goalCenter = Vec3.atCenterOf(goal);
        final long[] placed = new long[disassembly.blocks().length];
        for (int i = 0; i < placed.length; i++) {
            final Vec3 center = Vec3.atCenterOf(BlockPos.of(disassembly.blocks()[i]));
            placed[i] = BlockPos.containing(center.subtract(anchorCenter).yRot(turn).add(goalCenter)).asLong();
        }

        final UUID id = disassembly.subLevel().getUniqueId();
        final Forces forces = Forces.latest(id);
        Forces.forget(id);

        AssemblyAnimations.disassembled(anchor, goal, id, placed,
                forces != null ? forces.placed(anchorCenter, goalCenter, turn) : null);
    }

    private static long[] plotBlocks(final ClientLevel level, final ClientSubLevel subLevel) {
        final LongArrayList blocks = new LongArrayList();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (final PlotChunkHolder chunk : subLevel.getPlot().getLoadedChunks()) {
            final BoundingBox3ic bounds = chunk.getBoundingBox();
            if (bounds == null || bounds == BoundingBox3i.EMPTY)
                continue;

            final int baseX = chunk.getPos().getMinBlockX();
            final int baseZ = chunk.getPos().getMinBlockZ();

            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        pos.set(baseX + x, y, baseZ + z);
                        if (level.getBlockState(pos).isAir())
                            continue;

                        blocks.add(pos.asLong());
                        if (blocks.size() >= MAX_BLOCKS)
                            return blocks.toLongArray();
                    }
                }
            }
        }

        return blocks.toLongArray();
    }

    static void clear() {
        PENDING.clear();
        DISASSEMBLING.clear();
    }
}
