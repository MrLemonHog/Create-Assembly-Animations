package com.mlh.create_assembly_animation;

import com.mlh.create_assembly_animation.client.DiagramRedraw;
import com.mlh.create_assembly_animation.client.gui.AAConfigScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = CreateAssemblyAnimation.ID, dist = Dist.CLIENT)
public class CreateAssemblyAnimation {

    public static final String ID = "create_assembly_animation";

    public CreateAssemblyAnimation(final IEventBus modBus, final ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AAConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new AAConfigScreen(parent));
        modBus.addListener(DiagramRedraw::registerShader);
    }

    public static ResourceLocation asResource(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
