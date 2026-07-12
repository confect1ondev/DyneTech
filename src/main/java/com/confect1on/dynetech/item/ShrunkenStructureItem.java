package com.confect1on.dynetech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.entity.ShrunkenStructureEntity;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ShrunkenStructureItem extends Item {

    public ShrunkenStructureItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(ShrunkenStructureRef ref) {
        ItemStack stack = new ItemStack(DTItems.SHRUNKEN_STRUCTURE.get());
        stack.set(DTDataComponents.SHRUNKEN_STRUCTURE.get(), ref);
        return stack;
    }

    // -- Dropping in the world spawns our custom entity instead of a plain ItemEntity. --
    @Override
    public boolean hasCustomEntity(ItemStack stack) {
        return stack.has(DTDataComponents.SHRUNKEN_STRUCTURE.get());
    }

    @Nullable
    @Override
    public Entity createEntity(Level level, Entity location, ItemStack stack) {
        ShrunkenStructureEntity entity = new ShrunkenStructureEntity(level, location.getX(), location.getY(), location.getZ(), stack);
        // Pehkui BASE scales motion by the entity's scale, so a normal thrown velocity of ~0.4
        // would only travel ~0.04 blocks/tick in world at 0.1× scale. Boost by 1/scale so the
        // throw feels like a normal item toss. Drag decays proportionally each tick.
        net.minecraft.world.phys.Vec3 v = location.getDeltaMovement();
        float scale = com.confect1on.dynetech.pehkui.PehkuiCompat.getScale(entity);
        if (scale > 0F && scale < 1F) {
            entity.setDeltaMovement(v.x / scale, v.y / scale, v.z / scale);
        } else {
            entity.setDeltaMovement(v);
        }
        return entity;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        ShrunkenStructureRef ref = stack.get(DTDataComponents.SHRUNKEN_STRUCTURE.get());
        if (ref != null) {
            tooltip.add(Component.literal(ref.size().getX() + " × " + ref.size().getY() + " × " + ref.size().getZ())
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
