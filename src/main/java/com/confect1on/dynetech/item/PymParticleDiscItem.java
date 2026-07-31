package com.confect1on.dynetech.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.entity.PymParticleDiscEntity;

public class PymParticleDiscItem extends Item {

    public final float size;

    public PymParticleDiscItem(Properties properties, float size) {
        super(properties);
        this.size = size;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.getCooldowns().addCooldown(this, 10);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5F,
                0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        if (!level.isClientSide) {
            PymParticleDiscEntity disc = new PymParticleDiscEntity(level, player, stack.copyWithCount(1));
            // 1.2 speed (a touch slower than a snowball's 1.5), tiny inaccuracy so it flies
            // where you aim. Paired with the entity's low gravity/drag = frisbee :D
            disc.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.2F, 0.2F);
            level.addFreshEntity(disc);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
