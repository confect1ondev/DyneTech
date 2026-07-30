package com.confect1on.dynetech.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity;
import com.confect1on.dynetech.blockentity.GeneMicroscopeBlockEntity;
import com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity;
import com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity;
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

    public static final DeferredHolder<MenuType<?>, MenuType<GeneSequencerMenu>> GENE_SEQUENCER =
            MENUS.register("gene_sequencer", () -> IMenuTypeExtension.create((id, inv, buf) -> {
                var pos = buf.readBlockPos();
                var be = inv.player.level().getBlockEntity(pos);
                return be instanceof GeneSequencerBlockEntity s
                        ? new GeneSequencerMenu(id, inv, s)
                        : new GeneSequencerMenu(id, inv);
            }));

    public static final DeferredHolder<MenuType<?>, MenuType<GeneSplicerMenu>> GENE_SPLICER =
            MENUS.register("gene_splicer", () -> IMenuTypeExtension.create((id, inv, buf) -> {
                var pos = buf.readBlockPos();
                var be = inv.player.level().getBlockEntity(pos);
                return be instanceof GeneSplicerBlockEntity s
                        ? new GeneSplicerMenu(id, inv, s)
                        : new GeneSplicerMenu(id, inv);
            }));

    public static final DeferredHolder<MenuType<?>, MenuType<CryoPreservatorMenu>> CRYO_PRESERVATOR =
            MENUS.register("cryo_preservator", () -> IMenuTypeExtension.create((id, inv, buf) -> {
                var pos = buf.readBlockPos();
                var be = inv.player.level().getBlockEntity(pos);
                return be instanceof CryoPreservatorBlockEntity s
                        ? new CryoPreservatorMenu(id, inv, s)
                        : new CryoPreservatorMenu(id, inv);
            }));

    public static final DeferredHolder<MenuType<?>, MenuType<GeneMicroscopeMenu>> GENE_MICROSCOPE =
            MENUS.register("gene_microscope", () -> IMenuTypeExtension.create((id, inv, buf) -> {
                var pos = buf.readBlockPos();
                var be = inv.player.level().getBlockEntity(pos);
                return be instanceof GeneMicroscopeBlockEntity s
                        ? new GeneMicroscopeMenu(id, inv, s)
                        : new GeneMicroscopeMenu(id, inv);
            }));
}
