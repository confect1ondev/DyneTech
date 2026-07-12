package com.confect1on.dynetech.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;

public class DTMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, DyneTech.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<StructureShrinkerMenu>> STRUCTURE_SHRINKER =
            MENUS.register("structure_shrinker", () -> IMenuTypeExtension.create((id, inv, buf) -> {
                var pos = buf.readBlockPos();
                var be = inv.player.level().getBlockEntity(pos);
                return new StructureShrinkerMenu(id, inv,
                        be instanceof StructureShrinkerBlockEntity s ? s : null);
            }));
}
