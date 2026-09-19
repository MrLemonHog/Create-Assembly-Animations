package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

public record StyleSource(String id, Component name, @Nullable Component author, Icon icon) {

    public static final StyleSource BUILT_IN = new StyleSource(CreateAssemblyAnimation.ID,
            Component.translatable(CreateAssemblyAnimation.ID + ".source.built_in"), null,
            Icon.texture(CreateAssemblyAnimation.asResource("textures/gui/mod_icon.png"), 64));

    @FunctionalInterface
    public interface Icon {

        int SIZE = 16;

        void render(GuiGraphics graphics, int x, int y);

        static Icon item(final ResourceLocation id) {
            return new Icon() {
                @Nullable
                private ItemStack stack;

                @Override
                public void render(final GuiGraphics graphics, final int x, final int y) {
                    if (this.stack == null) {
                        final Item item = BuiltInRegistries.ITEM.get(id);
                        this.stack = new ItemStack(item == Items.AIR ? Items.PAPER : item);
                    }
                    graphics.renderItem(this.stack, x, y);
                }
            };
        }

        static Icon texture(final ResourceLocation texture, final int size) {
            return (graphics, x, y) -> {
                RenderSystem.enableBlend();
                graphics.blit(texture, x, y, SIZE, SIZE, 0f, 0f, size, size, size, size);
                RenderSystem.disableBlend();
            };
        }
    }
}
