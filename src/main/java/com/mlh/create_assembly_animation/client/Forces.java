package com.mlh.create_assembly_animation.client;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.simulated_team.simulated.content.entities.diagram.screen.DiagramScreen;
import dev.simulated_team.simulated.content.entities.diagram.screen.ForceClusterFinder;
import dev.simulated_team.simulated.network.packets.contraption_diagram.DiagramDataPacket;
import dev.simulated_team.simulated.network.packets.contraption_diagram.RequestDiagramDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Forces(List<Arrow> arrows, double mass, double maxMagnitude) {

    record Arrow(int color, Component name, double x, double y, double z, Vector3d force, double magnitude) {
    }

    private static final int ANSWER_TIMEOUT_TICKS = 40;

    private static final Map<UUID, Long> DUE = new HashMap<>();
    private static final Map<UUID, Forces> LATEST = new HashMap<>();

    @Nullable
    private static UUID awaiting;
    private static long awaitingSince;

    static Forces from(final Map<ForceGroup, List<QueuedForceGroup.PointForce>> forces, final double mass) {
        final List<Arrow> arrows = new ArrayList<>();

        for (final Map.Entry<ForceGroup, List<QueuedForceGroup.PointForce>> entry : forces.entrySet()) {
            final ForceGroup group = entry.getKey();
            if (group == null || !group.defaultDisplayed() || entry.getValue().isEmpty())
                continue;

            for (final ForceClusterFinder.Cluster cluster : ForceClusterFinder.getMergedClusters(entry.getValue())) {
                final double magnitude = cluster.force().length();
                if (magnitude <= 0.01)
                    continue;

                final int count = cluster.groupSize().getValue();
                final Component name = count > 1 ? Component.literal(count + "× ").append(group.name()) : group.name();
                arrows.add(new Arrow(group.color(), name, cluster.pos().x, cluster.pos().y, cluster.pos().z,
                        cluster.force(), magnitude));
            }
        }

        arrows.sort(Comparator.comparingDouble(Arrow::magnitude).reversed());
        final double max = arrows.isEmpty() ? 1.0 : arrows.get(0).magnitude();
        return new Forces(arrows, mass, max);
    }

    Forces placed(final Vec3 anchorCenter, final Vec3 goalCenter, final float turn) {
        final List<Arrow> moved = new ArrayList<>(this.arrows.size());
        for (final Arrow arrow : this.arrows) {
            final Vec3 position = new Vec3(arrow.x(), arrow.y(), arrow.z()).subtract(anchorCenter).yRot(turn).add(goalCenter);
            final Vec3 force = new Vec3(arrow.force().x, arrow.force().y, arrow.force().z).yRot(turn);
            moved.add(new Arrow(arrow.color(), arrow.name(), position.x, position.y, position.z,
                    new Vector3d(force.x, force.y, force.z), arrow.magnitude()));
        }
        return new Forces(moved, this.mass, this.maxMagnitude);
    }

    static void request(final UUID subLevel, final int delayTicks) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level != null)
            DUE.merge(subLevel, level.getGameTime() + delayTicks, Math::min);
    }

    @Nullable
    static Forces latest(final UUID subLevel) {
        return LATEST.get(subLevel);
    }

    static void tick(final ClientLevel level) {
        final long now = level.getGameTime();
        if (awaiting != null && now - awaitingSince > ANSWER_TIMEOUT_TICKS)
            awaiting = null;

        if (awaiting != null || DUE.isEmpty() || Minecraft.getInstance().screen instanceof DiagramScreen)
            return;

        final Iterator<Map.Entry<UUID, Long>> iterator = DUE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() > now)
                continue;

            iterator.remove();
            awaiting = entry.getKey();
            awaitingSince = now;
            PacketDistributor.sendToServer(new RequestDiagramDataPacket(entry.getKey()));
            return;
        }
    }

    public static void received(final DiagramDataPacket packet) {
        if (awaiting == null || Minecraft.getInstance().screen instanceof DiagramScreen)
            return;

        final UUID subLevel = awaiting;
        awaiting = null;

        final Forces forces = from(packet.forces(), packet.mass());
        LATEST.put(subLevel, forces);
        AssemblyAnimations.receiveForces(subLevel, forces);
    }

    static void forget(final UUID subLevel) {
        DUE.remove(subLevel);
        LATEST.remove(subLevel);
    }

    static void clear() {
        DUE.clear();
        LATEST.clear();
        awaiting = null;
    }
}
