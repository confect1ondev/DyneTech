package com.confect1on.dynetech.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import com.confect1on.dynetech.DyneTech;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Coordinator for the Godhood regeneration client state. The visual show has moved into
 * {@link com.confect1on.dynetech.client.renderer.godhood.GodhoodAuraFx} - this class now owns
 * only the timeline, the pose override (via {@link com.confect1on.dynetech.mixin.HumanoidModelBurnupMixin}),
 * the local player's input lock, and the HUD action-bar update.
 *
 * <p>Timeline (ticks from burn-up start) is still authoritative here so the aura FX and any
 * future consumers can look it up rather than re-deriving it:
 * <ul>
 *   <li>Ignition (0-10): pose ramps in.</li>
 *   <li>Burn-up (10-80): pose holds, aura pearls stream off.</li>
 *   <li>Collapse (80-95): pose releases, pillar fades.</li>
 *   <li>Detonation (95-115): aura dome + crown ring, shockwave rings from GodhoodShockwaveManager.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class GodhoodBurnupManager {

    private GodhoodBurnupManager() {}

    public static final int IGNITION_END_TICKS   = 10;
    public static final int BURNUP_END_TICKS     = 80;
    public static final int COLLAPSE_END_TICKS   = 95;
    public static final int EXPLOSION_END_TICKS  = 115;

    private static final Map<Integer, BurnupState> ACTIVE = new HashMap<>();
    private static int clientTick = 0;

    public static void start(int entityId, int durationTicks) {
        ACTIVE.put(entityId, new BurnupState(clientTick, durationTicks));
    }

    public static void updateCharges(int charges, int max) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ChatFormatting color = charges == 0 ? ChatFormatting.DARK_RED
                : charges >= max ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
        mc.player.displayClientMessage(
                Component.translatable("dynetech.godhood.hud", charges, max).withStyle(color),
                true);
    }

    public static boolean isBurning(int entityId) {
        return ACTIVE.containsKey(entityId);
    }

    public static boolean isLocalPlayerBurning() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ACTIVE.containsKey(mc.player.getId());
    }

    public static float elapsedTicks(int entityId, float partialTick) {
        BurnupState s = ACTIVE.get(entityId);
        if (s == null) return -1F;
        return (clientTick - s.startTick) + partialTick;
    }

    /** Snapshot of currently-burning entity ids. Read-only view for renderer subscribers. */
    public static Set<Integer> burningIds() {
        return Collections.unmodifiableSet(ACTIVE.keySet());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTick++;
        Iterator<Map.Entry<Integer, BurnupState>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, BurnupState> entry = it.next();
            if (clientTick - entry.getValue().startTick > EXPLOSION_END_TICKS + 3) {
                it.remove();
            }
        }
    }

    // ============================================================================
    //  Pose override (invoked from HumanoidModelBurnupMixin)
    // ============================================================================

    /**
     * Force the arms-up-and-out + head-back pose on any humanoid whose entity id is in
     * {@link #ACTIVE}. Called from the mixin at setupAnim's TAIL so it overrides the vanilla
     * idle animation for both the base body render and any layered overlay that follows.
     */
    public static void applyPoseToHumanoid(HumanoidModel<?> hm, LivingEntity entity) {
        BurnupState state = ACTIVE.get(entity.getId());
        if (state == null) return;
        float elapsed = clientTick - state.startTick;
        float t = getPoseIntensity(elapsed);
        if (t <= 0.001F) return;

        // Two-axis tremor so the frozen pose keeps micro-motion. Two frequencies picked to be
        // co-prime enough to not read as a single beat.
        float vibX = Mth.sin(elapsed * 2.10F) * 0.045F * t;
        float vibZ = Mth.sin(elapsed * 2.55F) * 0.030F * t;

        // Head: tilt back and slightly to the side. xRot < 0 in HumanoidModel = looking up.
        hm.head.xRot = Mth.lerp(t, hm.head.xRot, -0.75F) + vibX;
        hm.head.yRot = Mth.lerp(t, hm.head.yRot, 0F);
        hm.head.zRot = Mth.lerp(t, hm.head.zRot, 0F) + vibZ;

        // Arms: rotate around Z so they swing outward-upward from the body's sides.
        //   rightArm.zRot > 0 rotates the right arm AWAY from body center (out to the right).
        // leftArm mirrors with negative zRot outward-left; sign inversion crosses them.
        float armLift = 2.30F; // ~132 deg from hanging: past horizontal, into the "Y" pose
        hm.rightArm.xRot = Mth.lerp(t, hm.rightArm.xRot, 0F);
        hm.rightArm.yRot = Mth.lerp(t, hm.rightArm.yRot, 0F);
        hm.rightArm.zRot = Mth.lerp(t, hm.rightArm.zRot, armLift) + vibZ;

        hm.leftArm.xRot  = Mth.lerp(t, hm.leftArm.xRot, 0F);
        hm.leftArm.yRot  = Mth.lerp(t, hm.leftArm.yRot, 0F);
        hm.leftArm.zRot  = Mth.lerp(t, hm.leftArm.zRot, -armLift) - vibZ;
    }

    /** Pose intensity from 0 (neutral) to 1 (full angel pose). */
    public static float getPoseIntensity(float elapsed) {
        if (elapsed < IGNITION_END_TICKS) return elapsed / (float) IGNITION_END_TICKS;
        if (elapsed < BURNUP_END_TICKS) return 1F;
        if (elapsed < COLLAPSE_END_TICKS) {
            float k = (elapsed - BURNUP_END_TICKS) / (float) (COLLAPSE_END_TICKS - BURNUP_END_TICKS);
            return 1F - k;
        }
        return 0F;
    }

    // ============================================================================
    //  Input lock (local player only)
    // ============================================================================

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isLocalPlayerBurning()) return;
        Player p = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (p != mc.player) return;
        var input = event.getInput();
        input.leftImpulse = 0F;
        input.forwardImpulse = 0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    private record BurnupState(int startTick, int durationTicks) {}
}
