package com.confect1on.dynetech.pehkui;

import net.minecraft.world.entity.Entity;
import virtuoel.pehkui.api.ScaleData;
import virtuoel.pehkui.api.ScaleTypes;

public final class PehkuiCompat {

    private PehkuiCompat() {}

    public static void setTargetScale(Entity entity, float target, int tickDelay) {
        ScaleData data = ScaleTypes.BASE.getScaleData(entity);
        data.setScaleTickDelay(tickDelay);
        data.setTargetScale(target);
    }

    public static void setScaleImmediate(Entity entity, float scale) {
        ScaleData data = ScaleTypes.BASE.getScaleData(entity);
        data.setScaleTickDelay(0);
        data.setScale(scale);
    }

    public static float getScale(Entity entity) {
        return ScaleTypes.BASE.getScaleData(entity).getScale();
    }

    public static float getTargetScale(Entity entity) {
        return ScaleTypes.BASE.getScaleData(entity).getTargetScale();
    }
}
