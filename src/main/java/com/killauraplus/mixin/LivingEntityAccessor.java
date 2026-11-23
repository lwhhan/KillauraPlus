package com.killauraplus.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LivingEntity.attackStrengthTicker（原版为 protected）的写权限。
 *
 * 用途：Player.attack() 开头会检查蓄力，若 attackStrengthTicker > 0 且
 * getAttackStrengthScale(0.5F) < 1.0F 就直接 return，不结算伤害。
 * 攻击间隔快于武器满蓄力时间时，就会出现「摇了好几次手才受一次伤」。
 * 每次攻击前把该值设为足够大（满蓄力），即可保证每轮都打出完整伤害。
 *
 * 方法名加 killauraplus$ 前缀，避免与其他模组的 Accessor 冲突。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("attackStrengthTicker")
    void killauraplus$setAttackStrengthTicker(int value);
}
