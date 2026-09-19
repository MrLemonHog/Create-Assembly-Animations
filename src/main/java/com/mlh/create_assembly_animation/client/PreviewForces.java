package com.mlh.create_assembly_animation.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.serialization.JsonOps;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity;
import dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.physics.config.FloatingBlockMaterialDataHandler;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PreviewForces {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateAssemblyAnimation.ID);

    private static final double DEFAULT_PROPELLER_THRUST = 1.0;

    private PreviewForces() {
    }

    @Nullable
    static Forces measure(final BlockGetter blocks, final Collection<BlockPos> positions, final float rpm) {
        final Map<ForceGroup, List<QueuedForceGroup.PointForce>> forces = new LinkedHashMap<>();
        double mass = 0.0;

        try {
            for (final BlockPos pos : positions) {
                final BlockState state = blocks.getBlockState(pos);
                mass += PhysicsBlockPropertyHelper.getMass(blocks, pos, state);
                final Vector3d center = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

                final FloatingBlockMaterial material = floatingMaterial(state);
                if (material != null) {
                    final double lift = material.liftStrength() * PhysicsBlockPropertyHelper.getFloatingScale(state);
                    record(forces, group("levitation"), center, new Vector3d(0.0, lift, 0.0));
                }

                final Vector3d thrust = thrust(pos, state, rpm);
                if (thrust != null)
                    record(forces, group("propulsion"), center, thrust);
            }
            return Forces.from(forces, mass);
        } catch (final RuntimeException | LinkageError e) {
            LOGGER.warn("Could not work out the forces for the style previews.", e);
            return null;
        }
    }

    @Nullable
    private static ForceGroup group(final String name) {
        return ForceGroups.REGISTRY.get(ResourceLocation.fromNamespaceAndPath("sable", name));
    }

    private static void record(final Map<ForceGroup, List<QueuedForceGroup.PointForce>> forces, @Nullable final ForceGroup group,
                               final Vector3d point, final Vector3d force) {
        if (group != null && force.lengthSquared() > 1.0e-8)
            forces.computeIfAbsent(group, key -> new ArrayList<>()).add(new QueuedForceGroup.PointForce(point, force));
    }

    @Nullable
    private static Vector3d thrust(final BlockPos pos, final BlockState state, final float rpm) {
        if (!(state.getBlock() instanceof final EntityBlock entityBlock))
            return null;
        final BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
        if (!(blockEntity instanceof final BlockEntityPropeller propeller))
            return null;

        double configThrust = DEFAULT_PROPELLER_THRUST;
        if (blockEntity instanceof final BasePropellerBlockEntity base) {
            try {
                configThrust = base.getConfigThrust();
            } catch (final RuntimeException e) {
                configThrust = DEFAULT_PROPELLER_THRUST;
            }
        }

        final Direction facing = propeller.getBlockDirection();
        final double sign = facing.getAxisDirection().getStep() * (reversed(state) ? -1.0 : 1.0);
        return new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()).mul(configThrust * rpm * sign);
    }

    private static boolean reversed(final BlockState state) {
        for (final Property<?> property : state.getProperties()) {
            if (property instanceof final BooleanProperty flag && flag.getName().equals("reversed"))
                return state.getValue(flag);
        }
        return false;
    }

    @Nullable
    private static FloatingBlockMaterial floatingMaterial(final BlockState state) {
        final FloatingBlockMaterial material = PhysicsBlockPropertyHelper.getFloatingMaterial(state);
        if (material != null || !FloatingBlockMaterialDataHandler.allMaterials.isEmpty())
            return material;
        return bundledMaterial(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    @Nullable
    private static FloatingBlockMaterial bundledMaterial(final ResourceLocation id) {
        final IModFileInfo file = ModList.get().getModFileById(id.getNamespace());
        if (file == null)
            return null;

        final Path path = file.getFile().findResource("data", id.getNamespace(), "floating_materials", id.getPath() + ".json");
        if (!Files.isRegularFile(path))
            return null;

        try (final Reader reader = Files.newBufferedReader(path)) {
            final JsonElement json = JsonParser.parseReader(reader);
            return FloatingBlockMaterial.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }
}
