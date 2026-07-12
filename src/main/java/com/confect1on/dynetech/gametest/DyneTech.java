package com.confect1on.dynetech.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.entity.PymParticleDiskEntity;
import com.confect1on.dynetech.entity.ShrunkenEntityEntity;
import com.confect1on.dynetech.entity.ShrunkenStructureEntity;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.pehkui.PehkuiCompat;

import java.util.EnumSet;
import java.util.List;

/**
 * GameTests for the structure shrinker (1.21.1 NeoForge classic API).
 *
 * <p>Every test builds a 5x1x5 white concrete platform via {@link #buildArena}, paints only the
 * colored role tiles it uses (shrinker/source/target/out-of-range), and plants a labeled oak
 * sign so tests are distinguishable when they run side by side.
 *
 * <p>Tests exercise the real in-game flow. Shrinking is triggered by pressing a stone button on
 * the shrinker; regrowth by spawning a real {@link PymParticleDiskEntity} directly aimed at
 * the {@link ShrunkenStructureEntity}. The roundtrip test additionally has a mock player pick up
 * the entity, carry it, and toss it onto the target zone. The only shortcut is
 * {@code setSelection}, which stands in for the shrinker's selection GUI.
 *
 * <p>Registered with NeoForge via {@code @GameTestHolder} auto-discovery. Templates are loaded
 * from {@code data/dynetech/structure/dynetech.empty.nbt} — the {@code dynetech.} prefix comes
 * from {@code @PrefixGameTestTemplate(true)} which uses the lowercased class simple name.
 *
 * <p>The class is named {@code DyneTech} so that the auto-prefix produces test names of the form
 * {@code dynetech.<method>}. That lets you run every mod test at once with
 * {@code /test runall DyneTech}, and lets {@code /test run dynetech.<method>} target a specific
 * one. See {@link net.minecraft.gametest.framework.GameTestRegistry#isTestFunctionPartOfClass}
 * for the exact grouping logic (case-insensitive {@code startsWith("dynetech.")}).
 */
@GameTestHolder(com.confect1on.dynetech.DyneTech.MODID)
@PrefixGameTestTemplate(true)
public final class DyneTech {

    // Resolves to "dynetech:dynetech.empty":
    //   - namespace "dynetech" from @GameTestHolder
    //   - "dynetech." prefix from @PrefixGameTestTemplate(true) using the lowercased class name
    //   - "empty" from this constant
    public static final String TEMPLATE = "empty";

    private DyneTech() {}

    // All fixed positions are test-relative and live inside X,Z in [0..4] so the whole arena
    // sits inside the 5x5x5 structure bounding box.
    private static final BlockPos SHRINKER_POS = new BlockPos(1, 1, 1);
    private static final BlockPos SHRINKER_BUTTON_POS = new BlockPos(1, 2, 1);
    private static final BlockPos SIGN_POS = new BlockPos(3, 1, 0);
    private static final BlockPos SIGN_BASE = new BlockPos(3, 0, 0);

    // 3-block row selection used by the regrow_* tests. Odd width, so the shrunken entity
    // rests at (3.5, 1, 1.5).
    private static final BlockPos ROW_A = new BlockPos(2, 1, 1);
    private static final BlockPos ROW_B = new BlockPos(3, 1, 1);
    private static final BlockPos ROW_C = new BlockPos(4, 1, 1);

    private static final BlockPos[] SOURCE_TILES = new BlockPos[] {
            new BlockPos(2, 0, 1), new BlockPos(3, 0, 1),
            new BlockPos(2, 0, 2), new BlockPos(3, 0, 2),
    };
    private static final BlockPos[] TARGET_TILES = new BlockPos[] {
            new BlockPos(3, 0, 3), new BlockPos(4, 0, 3),
            new BlockPos(3, 0, 4), new BlockPos(4, 0, 4),
    };
    private static final BlockPos OUT_OF_RANGE_TILE = new BlockPos(4, 0, 4);

    private enum ArenaZone { SHRINKER, SOURCE, TARGET, OUT_OF_RANGE }

    // ============================================================================
    //  Tests
    // ============================================================================

    /**
     * The selection is one bedrock plus two stones. Shrinking should take only the two stones
     * and leave the bedrock behind. While the structure is away, a bedrock appears at the far
     * end where a captured stone used to be. On regrow, the paste should refuse that spot and
     * drop the captured stone as an item instead of overwriting the bedrock.
     *
     * <p>The mutation happens at ROW_C, not ROW_B: the shrunken entity rests inside ROW_B's
     * block space, and putting a block there would trap it and block the disc.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void blacklisted_blocks_survive_shrink_and_regrow(GameTestHelper helper) {
        buildArena(helper, "blacklisted blocks survive shrink and regrow",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.SOURCE));

        helper.setBlock(ROW_A, Blocks.BEDROCK);
        helper.setBlock(ROW_B, Blocks.STONE);
        helper.setBlock(ROW_C, Blocks.STONE);

        runRowShrinkRegrow(helper,
                () -> {
                    // Shrink left the bedrock behind but took both stones.
                    helper.assertBlockPresent(Blocks.BEDROCK, ROW_A);
                    helper.assertBlockPresent(Blocks.AIR, ROW_B);
                    helper.assertBlockPresent(Blocks.AIR, ROW_C);
                    // Simulate a blacklisted block appearing where a captured stone used to be.
                    helper.setBlock(ROW_C, Blocks.BEDROCK);
                },
                () -> {
                    helper.assertBlockPresent(Blocks.BEDROCK, ROW_A);
                    helper.assertBlockPresent(Blocks.STONE, ROW_B);
                    // Refusal: bedrock at ROW_C isn't overwritten, and the captured stone drops.
                    helper.assertBlockPresent(Blocks.BEDROCK, ROW_C);
                    helper.assertItemEntityPresent(Items.STONE, ROW_C, 2.0);
                });
    }

    /**
     * Non-blacklisted blocks appear in the structure's footprint while it is shrunk: one plain
     * block, plus a live chest with contents. Regrow must replace both and drop the displaced
     * block, the chest, and the chest's inventory as items, without double-dropping.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void regrow_over_blocks_drops_displaced_items(GameTestHelper helper) {
        buildArena(helper, "regrow over blocks drops displaced items",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.SOURCE));

        // Mutations flank ROW_B because the entity rests inside ROW_B; a block there would
        // trap it and the disc could never hit.
        helper.setBlock(ROW_A, Blocks.DIAMOND_BLOCK);
        helper.setBlock(ROW_B, Blocks.STONE);
        helper.setBlock(ROW_C, Blocks.STONE);

        runRowShrinkRegrow(helper,
                () -> {
                    helper.setBlock(ROW_A, Blocks.OAK_PLANKS);
                    helper.setBlock(ROW_C, Blocks.CHEST);
                    ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(ROW_C));
                    chest.setItem(0, new ItemStack(Items.REDSTONE, 7));
                },
                () -> {
                    helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, ROW_A);
                    helper.assertBlockPresent(Blocks.STONE, ROW_B);
                    helper.assertBlockPresent(Blocks.STONE, ROW_C);
                    helper.assertItemEntityPresent(Items.OAK_PLANKS, ROW_A, 2.0);
                    helper.assertItemEntityPresent(Items.CHEST, ROW_C, 2.0);
                    helper.assertItemEntityPresent(Items.REDSTONE, ROW_C, 2.0);
                });
    }

    /**
     * The full player storyline. Shrink a 2x2 patch (chest with inventory, furnace with facing
     * plus inventory, oak stairs with facing, plain gold block), pick the entity up with a
     * mock player, carry the item to the target zone, toss it, and regrow it there. Every
     * block, state property, and inventory slot must reappear in the original arrangement on
     * the red tiles, and the source zone must stay empty.
     *
     * <p>The patch is even-sized on purpose. The restore origin is {@code floor(entityPos -
     * size/2)}, so an even size puts the rest point at x.5, half a block from every floor
     * boundary. That leaves enough room for the toss's small random drift without shifting
     * the paste position. An odd size would land on the boundary exactly.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void shrink_pickup_redeploy_and_regrow_roundtrip(GameTestHelper helper) {
        buildArena(helper, "shrink pickup redeploy and regrow roundtrip",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.SOURCE, ArenaZone.TARGET));

        StructureShrinkerBlockEntity shrinker = placeShrinker(helper, SHRINKER_POS);
        placeShrinkerButton(helper);

        // 2x2 source patch on the blue tiles; one of each kind of block state to preserve.
        BlockPos srcMin = new BlockPos(2, 1, 1);
        BlockPos srcMax = new BlockPos(3, 1, 2);

        // Chest with contents in three non-contiguous slots (low, mid, high).
        helper.setBlock(srcMin, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(srcMin));
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        chest.setItem(3, new ItemStack(Items.EMERALD, 17));
        chest.setItem(15, new ItemStack(Items.NETHERITE_INGOT, 1));

        // Furnace: FACING property and inventory (input/fuel/result slots).
        // Input is non-smeltable on purpose. A live recipe would burn fuel and change the
        // slot counts mid-test, so the exact-count assertions below wouldn't hold.
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.FURNACE.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(3, 1, 1)));
        furnace.setItem(0, new ItemStack(Items.WHEAT, 3));      // input (non-smeltable)
        furnace.setItem(1, new ItemStack(Items.COAL, 2));       // fuel
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 1)); // result

        // Stairs: non-container block with multiple state properties.
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));

        // Plain block: no state, no BE, no inventory. Sanity control.
        helper.setBlock(srcMax, Blocks.GOLD_BLOCK);

        shrinker.setSelection(helper.absolutePos(srcMin), helper.absolutePos(srcMax));

        // Toss point (4.5, y, 4.5) rests the entity so the 2x2 pastes at origin (3, 1, 3),
        // exactly the four red target tiles.
        BlockPos dstAnchor = new BlockPos(4, 1, 4);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        helper.startSequence()
                .thenExecute(() -> helper.pressButton(SHRINKER_BUTTON_POS))
                .thenWaitUntil(() -> {
                    List<ShrunkenStructureEntity> list = findShrunkenList(helper);
                    helper.assertTrue(list.size() == 1 && PehkuiCompat.getScale(list.get(0)) <= 0.5F,
                            "waiting for shrink animation to finish");
                })
                .thenExecute(() -> {
                    ShrunkenStructureEntity entity = findShrunken(helper);
                    InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
                    helper.assertTrue(result.consumesAction(), "pickup interaction should be consumed");
                    helper.assertTrue(!entity.isAlive(), "entity should be gone after pickup");
                })
                .thenExecuteAfter(2, () -> {
                    // Carry the item over the target zone and toss it straight down.
                    ItemStack stack = takeShrunkenItem(helper, player);
                    Vec3 dropPos = helper.absoluteVec(new Vec3(4.5, 2.0, 4.5));
                    player.setPos(dropPos.x, dropPos.y, dropPos.z);
                    player.setXRot(90.0F); // look straight down so the toss has no horizontal drift
                    player.drop(stack, false);
                })
                .thenWaitUntil(() -> {
                    List<ShrunkenStructureEntity> settled = findShrunkenNear(helper, dstAnchor);
                    if (settled.size() != 1 || !settled.get(0).onGround()) {
                        helper.fail("waiting for tossed entity to settle on target tiles");
                    }
                })
                .thenExecute(() -> {
                    player.discard(); // out of the disc's way
                    ShrunkenStructureEntity settled = findShrunkenNear(helper, dstAnchor).get(0);
                    fireGrowDisc(helper, settled);
                })
                .thenWaitUntil(() -> {
                    if (!findShrunkenNear(helper, dstAnchor).isEmpty()) {
                        helper.fail("waiting for regrow to complete");
                    }
                    // Same 2x2 arrangement, translated from the blue zone onto the red zone:
                    // (2,1,1)->(3,1,3), (3,1,1)->(4,1,3), (2,1,2)->(3,1,4), (3,1,2)->(4,1,4).
                    BlockPos chestDst = new BlockPos(3, 1, 3);
                    helper.assertBlockPresent(Blocks.CHEST, chestDst);
                    BlockEntity chestBE = helper.getLevel().getBlockEntity(helper.absolutePos(chestDst));
                    helper.assertTrue(chestBE instanceof ChestBlockEntity, "chest BE missing after regrow");
                    Container chestC = (Container) chestBE;
                    assertStack(helper, chestC.getItem(0), Items.DIAMOND, 5, "chest slot 0");
                    assertStack(helper, chestC.getItem(3), Items.EMERALD, 17, "chest slot 3");
                    assertStack(helper, chestC.getItem(15), Items.NETHERITE_INGOT, 1, "chest slot 15");

                    BlockPos furnaceDst = new BlockPos(4, 1, 3);
                    helper.assertBlockState(furnaceDst,
                            s -> s.is(Blocks.FURNACE) && s.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
                            () -> "expected east-facing furnace");
                    BlockEntity furnaceBE = helper.getLevel().getBlockEntity(helper.absolutePos(furnaceDst));
                    helper.assertTrue(furnaceBE instanceof FurnaceBlockEntity, "furnace BE missing after regrow");
                    Container furnaceC = (Container) furnaceBE;
                    assertStack(helper, furnaceC.getItem(0), Items.WHEAT, 3, "furnace input");
                    assertStack(helper, furnaceC.getItem(1), Items.COAL, 2, "furnace fuel");
                    assertStack(helper, furnaceC.getItem(2), Items.IRON_INGOT, 1, "furnace result");

                    helper.assertBlockState(new BlockPos(3, 1, 4),
                            s -> s.is(Blocks.OAK_STAIRS) && s.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
                            () -> "expected east-facing oak stairs");
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(4, 1, 4));

                    // The blue source zone must stay empty; nothing regrew back at the origin.
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 1, 1));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 1, 1));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 1, 2));
                    helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 1, 2));
                })
                .thenSucceed();
    }

    /**
     * A selection whose {@code inflate(1)} box doesn't touch the shrinker's own block should
     * be rejected. The target block is placed far from the shrinker and the shrink should be
     * a no-op.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void shrinker_refuses_non_adjacent_selection(GameTestHelper helper) {
        buildArena(helper, "shrinker refuses non adjacent selection",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.OUT_OF_RANGE));

        StructureShrinkerBlockEntity shrinker = placeShrinker(helper, SHRINKER_POS);
        placeShrinkerButton(helper);

        // Sits on top of the yellow OUT_OF_RANGE tile so the refused selection is easy to spot.
        BlockPos farRel = OUT_OF_RANGE_TILE.above();
        helper.setBlock(farRel, Blocks.GOLD_BLOCK);
        shrinker.setSelection(helper.absolutePos(farRel), helper.absolutePos(farRel));

        helper.startSequence()
                .thenExecute(() -> helper.pressButton(SHRINKER_BUTTON_POS))
                .thenExecuteAfter(2, () -> {
                    helper.assertBlockPresent(Blocks.GOLD_BLOCK, farRel);
                    helper.assertTrue(countShrunkenNearShrinker(helper) == 0,
                            "no shrunken entity should have spawned for a non-adjacent selection");
                })
                .thenSucceed();
    }

    /**
     * A selection whose block volume exceeds {@code DTConfig.SHRINK_MAX_VOLUME} (default 32768)
     * should be rejected before capture runs. Uses a 64x64x64 selection adjacent to the shrinker
     * so the adjacency check passes; then plants a distinctive block inside the selection so we
     * can prove the shrink didn't run at all (a passing volume check would clear it during
     * {@code clearRegion}, whereas an early refusal leaves it in place).
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void shrinker_refuses_oversized_selection(GameTestHelper helper) {
        buildArena(helper, "shrinker refuses oversized selection",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.SOURCE));

        StructureShrinkerBlockEntity shrinker = placeShrinker(helper, SHRINKER_POS);
        placeShrinkerButton(helper);

        // Witness block sitting inside the selection but within the arena.
        helper.setBlock(ROW_A, Blocks.LAPIS_BLOCK);

        // Adjacent to the shrinker at ROW_A, then extended 63 blocks per axis: 64^3 = 262144
        // blocks, well above the 32768 default max. Chunks beyond the arena are never touched
        // because the volume check must reject before capture/clearRegion iterates.
        BlockPos startAbs = helper.absolutePos(ROW_A);
        BlockPos endAbs = startAbs.offset(63, 63, 63);
        shrinker.setSelection(startAbs, endAbs);

        helper.startSequence()
                .thenExecute(() -> helper.pressButton(SHRINKER_BUTTON_POS))
                .thenExecuteAfter(2, () -> {
                    helper.assertBlockPresent(Blocks.LAPIS_BLOCK, ROW_A);
                    helper.assertTrue(countShrunkenNearShrinker(helper) == 0,
                            "no shrunken entity should spawn when selection exceeds max volume");
                })
                .thenSucceed();
    }

    /**
     * Full loop for the Tissue Compression Eliminator: a mock player aims at a nearby pig, uses
     * the TCE, and the pig is captured — original despawns, a {@link ShrunkenEntityEntity} appears
     * at the pig's location holding a shrunken-entity item. A grow disc then regrows the pig at
     * the same spot; the shrunken entity is gone and a live pig exists again.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void tce_captures_pig_and_grow_disc_regrows_it(GameTestHelper helper) {
        buildArena(helper, "tce captures pig and grow disc regrows it",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.SOURCE));

        BlockPos pigRel = new BlockPos(3, 1, 1);
        Vec3 pigCenter = Vec3.atCenterOf(helper.absolutePos(pigRel));
        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG, pigRel);

        Player shooter = helper.makeMockPlayer(GameType.SURVIVAL);
        // Place the shooter 2 blocks west of the pig, then have them look at the pig's center so
        // the raycast is guaranteed to hit rather than depending on hand-picked yaw/pitch.
        shooter.setPos(pigCenter.x - 2.0, pigCenter.y - shooter.getEyeHeight() + 0.5, pigCenter.z);
        shooter.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, pigCenter);

        ItemStack tce = DTItems.TISSUE_COMPRESSION_ELIMINATOR.get().getDefaultInstance();
        shooter.setItemInHand(InteractionHand.MAIN_HAND, tce);

        helper.startSequence()
                .thenExecute(() -> DTItems.TISSUE_COMPRESSION_ELIMINATOR.get()
                        .use(helper.getLevel(), shooter, InteractionHand.MAIN_HAND))
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(!pig.isAlive(), "original pig should be discarded after TCE hit");
                    List<ShrunkenEntityEntity> captured = findShrunkenEntitiesNear(helper, pigRel);
                    helper.assertTrue(captured.size() == 1,
                            "expected exactly one shrunken-entity carrier, found " + captured.size());
                })
                .thenWaitUntil(() -> {
                    List<ShrunkenEntityEntity> list = findShrunkenEntitiesNear(helper, pigRel);
                    helper.assertTrue(!list.isEmpty() && PehkuiCompat.getScale(list.get(0)) <= 0.5F,
                            "waiting for shrink animation to finish");
                })
                .thenExecute(() -> {
                    ShrunkenEntityEntity carrier = findShrunkenEntitiesNear(helper, pigRel).get(0);
                    fireGrowDisc(helper, carrier);
                })
                .thenWaitUntil(() -> {
                    if (!findShrunkenEntitiesNear(helper, pigRel).isEmpty()) {
                        helper.fail("waiting for shrunken entity to regrow");
                    }
                    List<net.minecraft.world.entity.animal.Pig> pigs = helper.getLevel()
                            .getEntitiesOfClass(net.minecraft.world.entity.animal.Pig.class,
                                    new AABB(helper.absolutePos(pigRel)).inflate(2.5));
                    helper.assertTrue(pigs.size() == 1,
                            "expected exactly one regrown pig near the original spot, found " + pigs.size());
                })
                .thenSucceed();
    }

    /**
     * A shrink disc that hits a shrunken structure entity should do nothing. The entity stays
     * small and the source region stays cleared.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void disc_shrink_disc_does_not_regrow_entity(GameTestHelper helper) {
        buildArena(helper, "disc shrink disc does not regrow entity",
                EnumSet.of(ArenaZone.SHRINKER, ArenaZone.SOURCE));

        StructureShrinkerBlockEntity shrinker = placeShrinker(helper, SHRINKER_POS);
        placeShrinkerButton(helper);

        BlockPos rel = new BlockPos(2, 1, 1);
        helper.setBlock(rel, Blocks.LAPIS_BLOCK);
        shrinker.setSelection(helper.absolutePos(rel), helper.absolutePos(rel));

        helper.startSequence()
                .thenExecute(() -> helper.pressButton(SHRINKER_BUTTON_POS))
                .thenWaitUntil(() -> {
                    List<ShrunkenStructureEntity> list = findShrunkenList(helper);
                    helper.assertTrue(list.size() == 1 && PehkuiCompat.getScale(list.get(0)) <= 0.5F,
                            "waiting for shrink animation to finish");
                })
                .thenExecute(() -> fireShrinkDisc(helper, findShrunken(helper)))
                .thenExecuteAfter(30, () -> {
                    helper.assertBlockPresent(Blocks.AIR, rel);
                    helper.assertTrue(countShrunkenNearShrinker(helper) >= 1,
                            "shrunken entity should still be present after being hit by a shrink disc");
                })
                .thenSucceed();
    }

    // ============================================================================
    //  Arena / helpers
    // ============================================================================

    private static void buildArena(GameTestHelper helper, String label, EnumSet<ArenaZone> zones) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.WHITE_CONCRETE);
            }
        }

        helper.setBlock(SIGN_BASE, Blocks.LIGHT_GRAY_CONCRETE);

        if (zones.contains(ArenaZone.SHRINKER)) {
            helper.setBlock(new BlockPos(1, 0, 1), Blocks.BLACK_CONCRETE);
        }
        if (zones.contains(ArenaZone.SOURCE)) {
            for (BlockPos p : SOURCE_TILES) helper.setBlock(p, Blocks.BLUE_CONCRETE);
        }
        if (zones.contains(ArenaZone.TARGET)) {
            for (BlockPos p : TARGET_TILES) helper.setBlock(p, Blocks.RED_CONCRETE);
        }
        if (zones.contains(ArenaZone.OUT_OF_RANGE)) {
            helper.setBlock(OUT_OF_RANGE_TILE, Blocks.YELLOW_CONCRETE);
        }

        // Belt and braces: clear the workspace air in case any framework barriers snuck in.
        for (int y = 1; y <= 4; y++) {
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }

        placeLabelSign(helper, SIGN_POS, label);
    }

    private static void placeLabelSign(GameTestHelper helper, BlockPos rel, String label) {
        BlockState signState = Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, 8);
        helper.setBlock(rel, signState);
        BlockPos abs = helper.absolutePos(rel);
        BlockEntity be = helper.getLevel().getBlockEntity(abs);
        if (!(be instanceof SignBlockEntity sign)) return;
        String[] lines = wrapForSign(label);
        SignText text = new SignText()
                .setMessage(0, Component.literal(lines[0]))
                .setMessage(1, Component.literal(lines[1]))
                .setMessage(2, Component.literal(lines[2]))
                .setMessage(3, Component.literal(lines[3]));
        sign.setText(text, true);
        sign.setText(text, false);
        helper.getLevel().sendBlockUpdated(abs, signState, signState, 3);
    }

    /** Splits a space-delimited label onto up to four 15-char sign lines. */
    private static String[] wrapForSign(String label) {
        String[] out = new String[] { "", "", "", "" };
        String[] words = label.split(" ");
        int lineIdx = 0;
        for (String word : words) {
            if (lineIdx >= 4) break;
            String candidate = out[lineIdx].isEmpty() ? word : out[lineIdx] + " " + word;
            if (candidate.length() <= 15) {
                out[lineIdx] = candidate;
            } else if (word.length() <= 15) {
                lineIdx++;
                if (lineIdx < 4) out[lineIdx] = word;
            } else {
                if (out[lineIdx].isEmpty()) {
                    out[lineIdx] = word.substring(0, 15);
                    lineIdx++;
                    if (lineIdx < 4) out[lineIdx] = word.length() > 30 ? word.substring(15, 30) : word.substring(15);
                } else {
                    lineIdx++;
                    if (lineIdx < 4) out[lineIdx] = word.substring(0, Math.min(15, word.length()));
                }
            }
        }
        return out;
    }

    private static StructureShrinkerBlockEntity placeShrinker(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, DTBlocks.STRUCTURE_SHRINKER.get());
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        if (!(be instanceof StructureShrinkerBlockEntity shrinker)) {
            helper.fail("shrinker BE missing at " + rel);
            throw new AssertionError();
        }
        return shrinker;
    }

    private static void placeShrinkerButton(GameTestHelper helper) {
        helper.setBlock(SHRINKER_BUTTON_POS, Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
    }

    /** Returns the one shrunken entity belonging to this test near the shrinker. */
    private static ShrunkenStructureEntity findShrunken(GameTestHelper helper) {
        List<ShrunkenStructureEntity> list = findShrunkenList(helper);
        if (list.size() != 1) {
            helper.fail("expected exactly one shrunken structure entity near shrinker, found " + list.size());
            throw new AssertionError();
        }
        return list.get(0);
    }

    private static List<ShrunkenStructureEntity> findShrunkenList(GameTestHelper helper) {
        return findShrunkenNear(helper, SHRINKER_POS);
    }

    private static List<ShrunkenStructureEntity> findShrunkenNear(GameTestHelper helper, BlockPos anchorRel) {
        Vec3 anchorCenter = Vec3.atCenterOf(helper.absolutePos(anchorRel));
        AABB box = new AABB(helper.absolutePos(anchorRel)).inflate(3.0);
        return helper.getLevel().getEntitiesOfClass(ShrunkenStructureEntity.class, box,
                e -> e.position().distanceTo(anchorCenter) < 2.5);
    }

    private static int countShrunkenNearShrinker(GameTestHelper helper) {
        return findShrunkenList(helper).size();
    }

    private static List<ShrunkenEntityEntity> findShrunkenEntitiesNear(GameTestHelper helper, BlockPos anchorRel) {
        Vec3 anchorCenter = Vec3.atCenterOf(helper.absolutePos(anchorRel));
        AABB box = new AABB(helper.absolutePos(anchorRel)).inflate(3.0);
        return helper.getLevel().getEntitiesOfClass(ShrunkenEntityEntity.class, box,
                e -> e.position().distanceTo(anchorCenter) < 2.5);
    }

    private static ItemStack takeShrunkenItem(GameTestHelper helper, Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(DTItems.SHRUNKEN_STRUCTURE.get())) {
                return inv.removeItemNoUpdate(i);
            }
        }
        helper.fail("shrunken structure item not found in mock player inventory");
        throw new AssertionError();
    }

    /**
     * Shared shrink -> mutate -> regrow skeleton. Presses the shrinker button, runs
     * {@code whileShrunk} once the entity exists, then fires a grow disc directly at the
     * shrunken entity and waits for {@code finalAssertions} to pass.
     */
    private static void runRowShrinkRegrow(GameTestHelper helper, Runnable whileShrunk, Runnable finalAssertions) {
        // The row extends one tile past the standard blue zone; paint that tile to match.
        helper.setBlock(new BlockPos(4, 0, 1), Blocks.BLUE_CONCRETE);
        StructureShrinkerBlockEntity shrinker = placeShrinker(helper, SHRINKER_POS);
        placeShrinkerButton(helper);
        shrinker.setSelection(helper.absolutePos(ROW_A), helper.absolutePos(ROW_C));

        helper.startSequence()
                .thenExecute(() -> helper.pressButton(SHRINKER_BUTTON_POS))
                .thenWaitUntil(() -> {
                    List<ShrunkenStructureEntity> list = findShrunkenList(helper);
                    helper.assertTrue(list.size() == 1 && PehkuiCompat.getScale(list.get(0)) <= 0.5F,
                            "waiting for shrink animation to finish");
                })
                .thenExecute(() -> {
                    whileShrunk.run();
                    fireGrowDisc(helper, findShrunken(helper));
                })
                .thenWaitUntil(() -> {
                    if (countShrunkenNearShrinker(helper) != 0) {
                        helper.fail("waiting for regrow to complete");
                    }
                    finalAssertions.run();
                })
                .thenSucceed();
    }

    /**
     * Bypasses dispenser physics: spawns the grow disc close enough to hit the entity, using a
     * mock player as the thrower so the projectile has a valid owner.
     */
    private static void fireGrowDisc(GameTestHelper helper, net.minecraft.world.entity.Entity target) {
        fireDiscAt(helper, target, DTItems.ENLARGE_DISK.get().getDefaultInstance());
    }

    private static void fireShrinkDisc(GameTestHelper helper, net.minecraft.world.entity.Entity target) {
        fireDiscAt(helper, target, DTItems.SHRINK_DISK.get().getDefaultInstance());
    }

    private static void fireDiscAt(GameTestHelper helper, net.minecraft.world.entity.Entity target, ItemStack discStack) {
        Player thrower = helper.makeMockPlayer(GameType.SURVIVAL);
        // Position the thrower directly above the target so the projectile falls onto it.
        thrower.setPos(target.getX(), target.getY() + 2.5, target.getZ());
        thrower.setXRot(90.0F);
        thrower.setYRot(0.0F);

        PymParticleDiskEntity disc = new PymParticleDiskEntity(helper.getLevel(), thrower, discStack);
        disc.setPos(thrower.getX(), thrower.getY() - 0.2, thrower.getZ());
        disc.shootFromRotation(thrower, 90.0F, 0.0F, 0.0F, 1.5F, 0.0F);
        helper.getLevel().addFreshEntity(disc);
    }

    private static void assertStack(GameTestHelper helper, ItemStack stack,
                                    net.minecraft.world.item.Item expected, int expectedCount, String label) {
        helper.assertTrue(stack.is(expected) && stack.getCount() == expectedCount,
                label + " should be " + expectedCount + " " + expected + " but was " + stack);
    }

    // Silences the unused-import warning while keeping the disc entity class visible to the compiler.
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_TYPES_REFERENCED = DTEntityTypes.class;
}
