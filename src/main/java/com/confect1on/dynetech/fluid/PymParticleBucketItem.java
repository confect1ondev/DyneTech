package com.confect1on.dynetech.fluid;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import com.confect1on.dynetech.config.DTConfig;

public class PymParticleBucketItem extends BucketItem {

    public PymParticleBucketItem(Fluid fluid, Properties properties) {
        super(fluid, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(this.getDescriptionId(stack), DTConfig.particleBrand());
    }

    @Override
    public Component getDescription() {
        return Component.translatable(this.getDescriptionId(), DTConfig.particleBrand());
    }
}
