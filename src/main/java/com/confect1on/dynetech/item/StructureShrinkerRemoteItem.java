package com.confect1on.dynetech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;
import com.confect1on.dynetech.component.DTDataComponents;

import java.util.List;

/**
 * Coord-holder remote. Doesn't trigger anything on its own.
 * <ul>
 *     <li>Left-click any block → store as Corner A</li>
 *     <li>Right-click any block → store as Corner B</li>
 *     <li>Right-click a Structure Shrinker → transfer both corners to it</li>
 * </ul>
 */
public class StructureShrinkerRemoteItem extends Item {

    public StructureShrinkerRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        BlockPos a = stack.get(DTDataComponents.CORNER_A.get());
        BlockPos b = stack.get(DTDataComponents.CORNER_B.get());
        tooltip.add(Component.translatable("item.dynetech.structure_shrinker_remote.tooltip.corner_a",
                a == null ? "—" : (a.getX() + ", " + a.getY() + ", " + a.getZ()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dynetech.structure_shrinker_remote.tooltip.corner_b",
                b == null ? "—" : (b.getX() + ", " + b.getY() + ", " + b.getZ()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dynetech.structure_shrinker_remote.tooltip.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @EventBusSubscriber(modid = DyneTech.MODID)
    public static class Events {

        @SubscribeEvent
        public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
            ItemStack stack = e.getItemStack();
            if (!(stack.getItem() instanceof StructureShrinkerRemoteItem)) return;
            Level level = e.getLevel();
            if (level.isClientSide) return;

            BlockPos pos = e.getPos();
            stack.set(DTDataComponents.CORNER_A.get(), pos);
            e.getEntity().displayClientMessage(
                    Component.translatable("item.dynetech.structure_shrinker_remote.set_corner_a",
                            pos.getX(), pos.getY(), pos.getZ()), true);
            e.setCanceled(true);
        }

        @SubscribeEvent
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
            ItemStack stack = e.getItemStack();
            if (!(stack.getItem() instanceof StructureShrinkerRemoteItem)) return;
            Level level = e.getLevel();
            if (level.isClientSide) return;
            BlockPos pos = e.getPos();
            Player player = e.getEntity();

            // Right-click on a shrinker → transfer both corners into it.
            if (level.getBlockState(pos).is(DTBlocks.STRUCTURE_SHRINKER.get())
                    && level.getBlockEntity(pos) instanceof StructureShrinkerBlockEntity be) {
                BlockPos a = stack.get(DTDataComponents.CORNER_A.get());
                BlockPos b = stack.get(DTDataComponents.CORNER_B.get());
                if (a == null || b == null) {
                    player.displayClientMessage(
                            Component.translatable("item.dynetech.structure_shrinker_remote.no_corners")
                                    .withStyle(ChatFormatting.RED), true);
                } else {
                    be.setSelection(a, b);
                    player.displayClientMessage(
                            Component.translatable("item.dynetech.structure_shrinker_remote.transferred"), true);
                }
                e.setCanceled(true);
                return;
            }

            // Right-click any other block → store as Corner B.
            stack.set(DTDataComponents.CORNER_B.get(), pos);
            player.displayClientMessage(
                    Component.translatable("item.dynetech.structure_shrinker_remote.set_corner_b",
                            pos.getX(), pos.getY(), pos.getZ()), true);
            e.setCanceled(true);
        }
    }
}
