package com.confect1on.dynetech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.entity.ShrunkenEntityEntity;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Held representation of a shrunken mob. Drops spawn a {@link ShrunkenEntityEntity} instead of a
 * plain ItemEntity so it can be regrown by a grow disc later.
 */
public class ShrunkenEntityItem extends Item {

    public ShrunkenEntityItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(ShrunkenEntityRef ref) {
        ItemStack stack = new ItemStack(DTItems.SHRUNKEN_ENTITY.get());
        stack.set(DTDataComponents.SHRUNKEN_ENTITY.get(), ref);
        return stack;
    }

    @Override
    public boolean hasCustomEntity(ItemStack stack) {
        return stack.has(DTDataComponents.SHRUNKEN_ENTITY.get());
    }

    @Nullable
    @Override
    public Entity createEntity(Level level, Entity location, ItemStack stack) {
        // Land the drop with the ShrunkenEntityEntity already at pickup scale (its constructor
        // sets 0.1×) rather than mid-shrink animation — the shrink animation is only for the
        // initial beam-cast, not for re-drops of the item.
        ShrunkenEntityEntity entity = new ShrunkenEntityEntity(
                level, location.getX(), location.getY(), location.getZ(), stack);
        net.minecraft.world.phys.Vec3 v = location.getDeltaMovement();
        float scale = PehkuiCompat.getScale(entity);
        if (scale > 0F && scale < 1F) {
            entity.setDeltaMovement(v.x / scale, v.y / scale, v.z / scale);
        } else {
            entity.setDeltaMovement(v);
        }
        return entity;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        ShrunkenEntityRef ref = stack.get(DTDataComponents.SHRUNKEN_ENTITY.get());
        if (ref == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ref.entityType());
        Component name = type != null
                ? Component.translatable(type.getDescriptionId())
                : Component.literal(ref.entityType().toString());
        tooltip.add(name.copy().withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(ref.lethal()
                        ? "item.dynetech.shrunken_entity.tooltip.lethal"
                        : "item.dynetech.shrunken_entity.tooltip.nonlethal")
                .withStyle(ref.lethal() ? ChatFormatting.RED : ChatFormatting.GREEN));
    }
}
