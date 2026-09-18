package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

final class ShaderPacks {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateAssemblyAnimation.ID);

    @Nullable
    private static Object api;
    @Nullable
    private static Method inUse;
    private static boolean resolved;

    private ShaderPacks() {
    }

    static boolean inUse() {
        if (!resolved) {
            resolved = true;
            if (ModList.get().isLoaded("iris")) {
                try {
                    final Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                } catch (final ReflectiveOperationException | LinkageError e) {
                    LOGGER.warn("Could not reach the Iris API; shader packs may draw the animations incorrectly.", e);
                }
            }
        }

        if (api == null || inUse == null)
            return false;
        try {
            return (boolean) inUse.invoke(api);
        } catch (final ReflectiveOperationException e) {
            api = null;
            return false;
        }
    }
}
