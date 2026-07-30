package com.confect1on.dynetech.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.pehkui.PehkuiCompat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client-side burn-up FX for the Godhood regeneration. Server sends one
 * {@code GodhoodRegenStart} payload with a duration; the manager then drives a phased
 * timeline until the entry ages out.
 *
 * <p>Timeline (ticks from burn-up start):
 * <ul>
 *   <li>Ignition (0-10): halo grows in, base body pose swings into arms-up-and-out.</li>
 *   <li>Burn-up (10-80): halo pulses fast and flickers through orange/red/yellow, violent
 *       fire streams pour off the head and hands.</li>
 *   <li>Collapse (80-95): halo eases to zero scale (invisible inside the body), fire cuts.</li>
 *   <li>Explosion (95-115): halo scale explodes outward past the outer blast radius,
 *       fading as it rides the shockwave ring outward.</li>
 * </ul>
 *
 * <p>Pose overriding lives in {@link com.confect1on.dynetech.mixin.HumanoidModelBurnupMixin},
 * which calls back into {@link #applyPoseToHumanoid} so both the base body and the halo
 * ghost render in the same pose (mixin runs at setupAnim TAIL, before base render).
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class GodhoodBurnupManager {

    private GodhoodBurnupManager() {}

    static final int IGNITION_END_TICKS   = 10;
    static final int BURNUP_END_TICKS     = 80;
    static final int COLLAPSE_END_TICKS   = 95;
    static final int EXPLOSION_END_TICKS  = 115;

    /** Peak scale of the halo during ignition/burn-up (proportional, on top of Pehkui). */
    private static final float HALO_PEAK_SCALE = 1.20F;

    /**
     * Peak halo scale during the explosion phase. Capped well below the outer blast radius
     * because rendering a player model at 15x scale looks like a floating giant rather than
     * an aura - the shockwave rings do the "big" work while the halo stays a readable
     * body-sized expansion.
     */
    private static final float HALO_EXPLOSION_SCALE = 4.5F;

    private static final ResourceLocation WHITE_TEX = DyneTech.id("textures/misc/white.png");

    private static final Map<Integer, BurnupState> ACTIVE = new HashMap<>();
    private static final RandomSource RNG = RandomSource.create();

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

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Iterator<Map.Entry<Integer, BurnupState>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, BurnupState> entry = it.next();
            BurnupState s = entry.getValue();
            int elapsed = clientTick - s.startTick;
            if (elapsed > EXPLOSION_END_TICKS + 3) { it.remove(); continue; }
            Entity e = mc.level.getEntity(entry.getKey());
            if (!(e instanceof LivingEntity le)) continue;
            spawnPhaseParticles(le, elapsed);
        }
    }

    /**
     * Ignition + collapse get sparse ambience; burn-up is the loud part with fire streams
     * from head and hands. The streams are the show - keep them dense enough to look
     * violent but not so dense they occlude the halo.
     */
    private static void spawnPhaseParticles(LivingEntity le, int elapsed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        double x = le.getX();
        double y = le.getY();
        double z = le.getZ();
        float scale = PehkuiCompat.getScale(le);
        float h = le.getBbHeight() * scale;
        float w = le.getBbWidth() * scale;

        if (elapsed < IGNITION_END_TICKS) {
            spawnIgnition(mc, x, y, z, w, elapsed);
        } else if (elapsed < BURNUP_END_TICKS) {
            spawnFireStreams(mc, le, x, y, z, w, h, elapsed);
        } else if (elapsed < COLLAPSE_END_TICKS) {
            float t = (elapsed - BURNUP_END_TICKS) / (float) (COLLAPSE_END_TICKS - BURNUP_END_TICKS);
            spawnCollapse(mc, x, y, z, w, h, t);
        }
    }

    /** Ignition: a couple of sparks around the feet as the gene catches. */
    private static void spawnIgnition(Minecraft mc, double x, double y, double z,
                                      float w, int elapsed) {
        if (elapsed % 3 != 0) return;
        int count = 1 + RNG.nextInt(3);
        for (int i = 0; i < count; i++) {
            double angle = RNG.nextDouble() * Math.PI * 2;
            double r = w * 0.4;
            double ox = Math.cos(angle) * r;
            double oz = Math.sin(angle) * r;
            mc.level.addParticle(ParticleTypes.FLAME,
                    x + ox, y + RNG.nextDouble() * 0.3, z + oz,
                    ox * 0.03, 0.02 + RNG.nextDouble() * 0.02, oz * 0.03);
        }
    }

    /**
     * Burn-up: dense flame + lava + soul-fire jets from the head-top and both hands. Head
     * jet goes straight up; hand jets project up-and-outward following the pose the mixin
     * forces on the body. The whole thing is intentionally chaotic - multiple particle
     * types with randomised velocities so it reads cloudy and violent instead of a clean
     * beam.
     */
    private static void spawnFireStreams(Minecraft mc, LivingEntity le,
                                         double x, double y, double z, float w, float h, int elapsed) {
        float yawRad = (float) Math.toRadians(le.yBodyRot);
        // Minecraft yaw 0 = facing south (+Z). Forward = (-sin yaw, cos yaw). Body's
        // physical right side is forward rotated 90 degrees clockwise (viewed from above):
        //   right = (-cos yaw, -sin yaw).
        // At yaw 0 (facing south) that gives -X = west, which matches "right of south."
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        // Pose-corrected anchor points. The mixin's pose swings the arms up-and-out at
        // roughly 30 degrees from vertical, so the hand ends up ~0.7 blocks out to the
        // side and ~1.6 blocks above the shoulder. Add pose intensity so the streams
        // ramp in with the pose during ignition.
        float poseT = getPoseIntensity(elapsed);
        double shoulderY = h * 0.78;
        double shoulderOffset = w * 0.4;
        double armReach = 0.75 * poseT;
        double armLiftHoriz = armReach * 0.5;   // sin(30 deg)
        double armLiftVert  = armReach * 0.87;  // cos(30 deg)

        double rHandX = x + rightX * (shoulderOffset + armLiftHoriz);
        double rHandY = y + shoulderY + armLiftVert;
        double rHandZ = z + rightZ * (shoulderOffset + armLiftHoriz);
        double lHandX = x - rightX * (shoulderOffset + armLiftHoriz);
        double lHandY = y + shoulderY + armLiftVert;
        double lHandZ = z - rightZ * (shoulderOffset + armLiftHoriz);

        double headX = x;
        double headY = y + h + 0.15;
        double headZ = z;

        // Head stream: straight up, most intense.
        emitFireStream(mc, headX, headY, headZ,
                0.0, 0.35, 0.0,
                8, 0.20);

        // Hand streams: outward-upward, angled to match the pose.
        double handVx = rightX * 0.22;
        double handVz = rightZ * 0.22;
        emitFireStream(mc, rHandX, rHandY, rHandZ,
                handVx, 0.28, handVz,
                6, 0.18);
        emitFireStream(mc, lHandX, lHandY, lHandZ,
                -handVx, 0.28, -handVz,
                6, 0.18);
    }

    /**
     * One violent fire stream: {@code count} particles per tick from ({@code x,y,z}) with
     * base velocity ({@code vx,vy,vz}), randomised in position and velocity by
     * {@code jitter}. Alternates FLAME (main body), LAVA (chunky embers), SMALL_FLAME
     * (fine detail), and SMOKE (cloudy trail behind the flames).
     */
    private static void emitFireStream(Minecraft mc,
                                       double x, double y, double z,
                                       double vx, double vy, double vz,
                                       int count, double jitter) {
        for (int i = 0; i < count; i++) {
            double jx = (RNG.nextDouble() - 0.5) * jitter;
            double jy = (RNG.nextDouble() - 0.5) * jitter * 0.5;
            double jz = (RNG.nextDouble() - 0.5) * jitter;
            double jvx = vx + (RNG.nextDouble() - 0.5) * 0.18;
            double jvy = vy + (RNG.nextDouble() - 0.5) * 0.12;
            double jvz = vz + (RNG.nextDouble() - 0.5) * 0.18;

            ParticleOptions particle = pickFireParticle();
            mc.level.addParticle(particle, x + jx, y + jy, z + jz, jvx, jvy, jvz);
        }
    }

    /** Weighted pick: mostly FLAME, some LAVA + SMALL_FLAME, occasional SMOKE for cloud. */
    private static ParticleOptions pickFireParticle() {
        int r = RNG.nextInt(10);
        if (r < 5) return ParticleTypes.FLAME;
        if (r < 7) return ParticleTypes.SMALL_FLAME;
        if (r < 9) return ParticleTypes.LAVA;
        return ParticleTypes.LARGE_SMOKE;
    }

    /**
     * Collapse: a couple of streaks per tick pulled from a shrinking shell into the chest
     * so the eye reads the halo's implosion. Sparse - the pose reset is the primary cue.
     */
    private static void spawnCollapse(Minecraft mc, double x, double y, double z,
                                      float w, float h, float t) {
        if (RNG.nextInt(2) != 0) return;
        int count = Math.max(1, (int) (3 * (1F - t)));
        double shellR = Mth.lerp(t, w * 1.2, w * 0.15);
        double chestY = y + h * 0.55;
        for (int i = 0; i < count; i++) {
            double angle = RNG.nextDouble() * Math.PI * 2;
            double ox = Math.cos(angle) * shellR;
            double oz = Math.sin(angle) * shellR;
            double oy = (RNG.nextDouble() - 0.5) * h * 0.6;
            double vx = -ox * 0.20;
            double vy = ((chestY - (y + h * 0.5 + oy))) * 0.10;
            double vz = -oz * 0.20;
            mc.level.addParticle(ParticleTypes.END_ROD,
                    x + ox, y + h * 0.5 + oy, z + oz, vx, vy, vz);
        }
    }

    // ============================================================================
    //  Third-person halo overlay
    // ============================================================================

    /**
     * Draws the halo ghost of the model. The pose it inherits was applied by the mixin at
     * setupAnim's TAIL, so both the base body and this halo render in the burn-up pose
     * without any manual part-swapping here.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post event) {
        LivingEntity entity = event.getEntity();
        BurnupState state = ACTIVE.get(entity.getId());
        if (state == null) return;

        float elapsed = (clientTick - state.startTick) + event.getPartialTick();
        if (elapsed < 0F || elapsed > EXPLOSION_END_TICKS) return;

        float alpha = getHaloAlpha(elapsed);
        float scale = getHaloScale(elapsed);
        if (alpha <= 0.01F || scale <= 0.001F) return;

        LivingEntityRenderer<?, ?> renderer = event.getRenderer();
        EntityModel model = renderer.getModel();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float partialTick = event.getPartialTick();
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(WHITE_TEX));

        // Anchor the scale on the entity's chest so the halo grows equally in every
        // direction. Without the pre/post translate, PoseStack scales from the feet - the
        // halo then extends UP past the head instead of surrounding the body.
        float chestY = entity.getBbHeight() * 0.5F * PehkuiCompat.getScale(entity);

        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180F - bodyYaw));
        pose.translate(0F, chestY, 0F);
        pose.scale(scale, scale, scale);
        pose.translate(0F, -chestY, 0F);
        pose.scale(-1F, -1F, 1F);
        pose.scale(0.9375F, 0.9375F, 0.9375F);
        pose.translate(0F, -1.501F, 0F);

        int a = Mth.clamp((int) (alpha * 255F), 0, 255);
        int color = (a << 24) | getHaloRGB(elapsed);
        model.renderToBuffer(pose, vc, 0xF000F0, OverlayTexture.NO_OVERLAY, color);
        pose.popPose();
    }

    static float getHaloAlpha(float elapsed) {
        if (elapsed < IGNITION_END_TICKS) {
            return Mth.lerp(elapsed / (float) IGNITION_END_TICKS, 0F, 0.75F);
        }
        if (elapsed < BURNUP_END_TICKS) {
            float wobble = Mth.sin(elapsed * 0.75F) * 0.10F;
            return 0.75F + wobble;
        }
        if (elapsed < COLLAPSE_END_TICKS) {
            float t = (elapsed - BURNUP_END_TICKS) / (float) (COLLAPSE_END_TICKS - BURNUP_END_TICKS);
            return Mth.lerp(t, 0.75F, 0F);
        }
        if (elapsed < EXPLOSION_END_TICKS) {
            float t = (elapsed - COLLAPSE_END_TICKS) / (float) (EXPLOSION_END_TICKS - COLLAPSE_END_TICKS);
            return Mth.lerp(t, 0.60F, 0F);
        }
        return 0F;
    }

    static float getHaloScale(float elapsed) {
        if (elapsed < IGNITION_END_TICKS) {
            return Mth.lerp(elapsed / (float) IGNITION_END_TICKS, 1.02F, HALO_PEAK_SCALE);
        }
        if (elapsed < BURNUP_END_TICKS) {
            float fast = Mth.sin(elapsed * 1.20F) * 0.055F;
            float slow = Mth.sin(elapsed * 0.35F) * 0.030F;
            return HALO_PEAK_SCALE + fast + slow;
        }
        if (elapsed < COLLAPSE_END_TICKS) {
            float t = (elapsed - BURNUP_END_TICKS) / (float) (COLLAPSE_END_TICKS - BURNUP_END_TICKS);
            float easedT = t * t * t;
            return Mth.lerp(easedT, HALO_PEAK_SCALE, 0F);
        }
        if (elapsed < EXPLOSION_END_TICKS) {
            float t = (elapsed - COLLAPSE_END_TICKS) / (float) (EXPLOSION_END_TICKS - COLLAPSE_END_TICKS);
            float easedT = 1F - (float) Math.pow(1F - t, 3);
            return Mth.lerp(easedT, 0F, HALO_EXPLOSION_SCALE);
        }
        return 0F;
    }

    /**
     * Halo tint: color flicker between orange, red, and yellow during burn-up (three
     * phase-offset sines mixed by their normalised weights so the dominant hue rotates).
     * Amber for the collapse + explosion so it reads as one warm object.
     */
    static int getHaloRGB(float elapsed) {
        if (elapsed < IGNITION_END_TICKS) return 0xFF7020;
        if (elapsed < BURNUP_END_TICKS) {
            float wOrange = 0.5F + 0.5F * Mth.sin(elapsed * 0.90F);
            float wRed    = 0.5F + 0.5F * Mth.sin(elapsed * 0.90F + 2.10F);
            float wYellow = 0.5F + 0.5F * Mth.sin(elapsed * 0.90F + 4.20F);
            float total = wOrange + wRed + wYellow;
            wOrange /= total; wRed /= total; wYellow /= total;
            int r = (int) (wOrange * 0xFF + wRed * 0xE8 + wYellow * 0xFF);
            int g = (int) (wOrange * 0x60 + wRed * 0x18 + wYellow * 0xD8);
            int b = (int) (wOrange * 0x18 + wRed * 0x20 + wYellow * 0x38);
            return (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
        }
        return 0xFFB040;
    }

    // ============================================================================
    //  Pose override (invoked from HumanoidModelBurnupMixin)
    // ============================================================================

    /**
     * Force the arms-up-and-out + head-back pose on any humanoid whose entity id is in
     * {@link #ACTIVE}. Called from the mixin at setupAnim's TAIL so it overrides the
     * vanilla idle animation for both the base body render and the halo overlay render
     * that follows.
     *
     * <p>The pose ramps in during ignition and out during collapse; a small two-axis
     * vibration is layered on so the frozen body still shivers with the gene burning off.
     */
    public static void applyPoseToHumanoid(HumanoidModel<?> hm, LivingEntity entity) {
        BurnupState state = ACTIVE.get(entity.getId());
        if (state == null) return;
        // No partial tick from the mixin's callsite - use the whole-tick count. The pose
        // changes gradually, so single-tick precision is fine.
        float elapsed = clientTick - state.startTick;
        float t = getPoseIntensity(elapsed);
        if (t <= 0.001F) return;

        // Two-axis tremor so the frozen pose keeps micro-motion. Two frequencies picked
        // to be co-prime enough to not read as a single beat.
        float vibX = Mth.sin(elapsed * 2.10F) * 0.045F * t;
        float vibZ = Mth.sin(elapsed * 2.55F) * 0.030F * t;

        // Head: tilt back and slightly to the side. xRot < 0 in HumanoidModel = looking up.
        hm.head.xRot = Mth.lerp(t, hm.head.xRot, -0.75F) + vibX;
        hm.head.yRot = Mth.lerp(t, hm.head.yRot, 0F);
        hm.head.zRot = Mth.lerp(t, hm.head.zRot, 0F) + vibZ;

        // Arms: rotate around Z so they swing outward-upward from the body's sides.
        // Sign convention verified against vanilla AnimationUtils.animateZombieArms:
        //   rightArm.zRot > 0 rotates the right arm AWAY from body center (out to the
        //   right side). leftArm mirrors with negative zRot going outward-left. Getting
        //   these signs backwards makes the arms cross over each other.
        float armLift = 2.30F; // ~132 deg from hanging: past horizontal, into the "Y" pose
        hm.rightArm.xRot = Mth.lerp(t, hm.rightArm.xRot, 0F);
        hm.rightArm.yRot = Mth.lerp(t, hm.rightArm.yRot, 0F);
        hm.rightArm.zRot = Mth.lerp(t, hm.rightArm.zRot, armLift) + vibZ;

        hm.leftArm.xRot  = Mth.lerp(t, hm.leftArm.xRot, 0F);
        hm.leftArm.yRot  = Mth.lerp(t, hm.leftArm.yRot, 0F);
        hm.leftArm.zRot  = Mth.lerp(t, hm.leftArm.zRot, -armLift) - vibZ;
    }

    /**
     * Pose intensity from 0 (neutral) to 1 (full angel pose). Swings in over ignition,
     * holds through burn-up, releases across the collapse.
     */
    static float getPoseIntensity(float elapsed) {
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
