package com.confect1on.dynetech.fluid;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import com.confect1on.dynetech.config.DTConfig;

public class PymParticleLiquidBlock extends LiquidBlock {

    public PymParticleLiquidBlock(FlowingFluid fluid, Properties properties) {
        super(fluid, properties);
    }

    @Override
    public MutableComponent getName() {
        return Component.translatable(this.getDescriptionId(), DTConfig.particleBrand());
    }
}
