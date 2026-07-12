package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.entity.ShrunkenStructureEntity;
import com.confect1on.dynetech.item.ShrunkenStructureItem;
import com.confect1on.dynetech.menu.StructureShrinkerMenu;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;
import com.confect1on.dynetech.storage.ShrunkenStructureStorage;
import com.confect1on.dynetech.storage.StructureBlob;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class StructureShrinkerBlockEntity extends BlockEntity implements MenuProvider {

    private BlockPos selectionStart = BlockPos.ZERO;
    private BlockPos selectionEnd = BlockPos.ZERO;
    private boolean lastPowered = false;

    public StructureShrinkerBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.STRUCTURE_SHRINKER.get(), pos, state);
    }

    public void serverTick(net.minecraft.world.level.Level level) {
        boolean powered = level.hasNeighborSignal(getBlockPos());
        if (powered && !lastPowered) {
            shrink(null);
        }
        lastPowered = powered;
    }

    public BlockPos getSelectionStart() {
        return getBlockPos().offset(selectionStart);
    }

    public BlockPos getSelectionEnd() {
        return getBlockPos().offset(selectionEnd);
    }

    public BlockPos getSelectionStartRelative() {
        return selectionStart;
    }

    public BlockPos getSelectionEndRelative() {
        return selectionEnd;
    }

    public void setSelection(BlockPos startAbs, BlockPos endAbs) {
        this.selectionStart = startAbs.subtract(getBlockPos());
        this.selectionEnd = endAbs.subtract(getBlockPos());
        onSelectionChanged();
    }

    public void setSelectionStart(BlockPos startAbs) {
        this.selectionStart = startAbs.subtract(getBlockPos());
        onSelectionChanged();
    }

    public void setSelectionEnd(BlockPos endAbs) {
        this.selectionEnd = endAbs.subtract(getBlockPos());
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void shrink(@Nullable Player activator) {
        if (!(level instanceof ServerLevel server)) return;

        BlockPos a = getSelectionStart();
        BlockPos b = getSelectionEnd();
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));

        AABB region = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
        AABB shrinkerBox = new AABB(getBlockPos().getX(), getBlockPos().getY(), getBlockPos().getZ(),
                getBlockPos().getX() + 1.0, getBlockPos().getY() + 1.0, getBlockPos().getZ() + 1.0);
        if (!region.inflate(1).intersects(shrinkerBox)) {
            if (activator != null) activator.displayClientMessage(Component.literal("Structure region is not adjacent to shrinker"), true);
            return;
        }

        // Reject before capture so we never allocate a palette/indices array for an oversized selection.
        long volume = (long) (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        int maxVolume = DTConfig.SHRINK_MAX_VOLUME.get();
        if (volume > maxVolume) {
            if (activator != null) activator.displayClientMessage(
                    Component.literal("Selection too large: " + volume + " blocks (max " + maxVolume + ")"), true);
            return;
        }

        // Blacklisted blocks are quietly skipped during capture/clear (see StructureBlob) and the shrink now proceeds around them instead of refusing the whole op
        StructureBlob blob = StructureBlob.capture(server, min, max);
        if (blob.isEmpty()) {
            if (activator != null) activator.displayClientMessage(Component.literal("Selection contains no blocks"), true);
            return;
        }

        ShrunkenStructureStorage storage = ShrunkenStructureStorage.get(server.getServer());
        UUID id = storage.store(blob);

        StructureBlob.clearRegion(server, min, max, activator);

        ItemStack out = ShrunkenStructureItem.create(new ShrunkenStructureRef(id, blob.size()));
        double cx = (min.getX() + max.getX() + 1) / 2.0;
        double cz = (min.getZ() + max.getZ() + 1) / 2.0;
        double y = min.getY();
        ShrunkenStructureEntity entity = new ShrunkenStructureEntity(server, cx, y, cz, out);
        server.addFreshEntity(entity);
        // Override the constructor's immediate 0.1× with a visible animation from full size down.
        // The entity's tick loop suppresses restore while target < 0.5, so no premature paste-back.
        com.confect1on.dynetech.pehkui.PehkuiCompat.setScaleImmediate(entity, 1.0F);
        com.confect1on.dynetech.pehkui.PehkuiCompat.setTargetScale(entity, 0.1F, 20);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity, new com.confect1on.dynetech.network.DTPayloads.SpawnPulses(entity.getId()));
    }

    // -- persistence --
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("SelStart")) selectionStart = readPos(tag, "SelStart");
        if (tag.contains("SelEnd")) selectionEnd = readPos(tag, "SelEnd");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writePos(tag, "SelStart", selectionStart);
        writePos(tag, "SelEnd", selectionEnd);
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        int[] v = tag.getIntArray(key);
        return v.length == 3 ? new BlockPos(v[0], v[1], v[2]) : BlockPos.ZERO;
    }

    private static void writePos(CompoundTag tag, String key, BlockPos pos) {
        tag.putIntArray(key, new int[]{pos.getX(), pos.getY(), pos.getZ()});
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        writePos(tag, "SelStart", selectionStart);
        writePos(tag, "SelEnd", selectionEnd);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // -- menu --
    @Override
    public Component getDisplayName() {
        return Component.translatable("block.dynetech.structure_shrinker");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new StructureShrinkerMenu(id, inv, this);
    }
}
