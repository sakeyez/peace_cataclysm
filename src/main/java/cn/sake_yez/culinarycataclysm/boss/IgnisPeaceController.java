package cn.sake_yez.culinarycataclysm.boss;

import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.UUID;
import java.util.Optional;
import java.lang.reflect.Method;

public final class IgnisPeaceController {
    private static final String MANAGED_KEY = "CulinaryCataclysmManaged";
    private static final String COMPETITION_KEY = "CulinaryCataclysmCompetition";

    private IgnisPeaceController() {
    }

    public static boolean isIgnis(Entity entity) {
        return CookeryIntegration.IGNIS.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static boolean isManaged(Entity entity) {
        return isIgnis(entity) && entity.getPersistentData().getBoolean(MANAGED_KEY);
    }

    /** Cataclysm 在祭坛生成 Ignis 时设置 homePos，命令召唤不会设置这个来源位置。 */
    public static Optional<GlobalPos> altarHome(Entity entity) {
        if (!isIgnis(entity)) return Optional.empty();
        try {
            Object value = entity.getClass().getMethod("getHomePos").invoke(entity);
            return value instanceof GlobalPos pos ? Optional.of(pos) : Optional.empty();
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    public static void apply(Entity entity, UUID competitionId) {
        if (!isIgnis(entity)) throw new IllegalArgumentException("目标不是 Ignis");
        entity.getPersistentData().putBoolean(MANAGED_KEY, true);
        entity.getPersistentData().putUUID(COMPETITION_KEY, competitionId);
        entity.addTag("culinary_cataclysm_judge");
        entity.setInvulnerable(true);
        entity.setDeltaMovement(0, 0, 0);
        if (entity instanceof Mob mob) {
            mob.setTarget(null);
            mob.setAggressive(false);
            mob.stopInPlace();
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
    }

    public static void maintain(Entity entity) {
        if (!isManaged(entity)) return;
        entity.setInvulnerable(true);
        entity.setDeltaMovement(0, 0, 0);
        if (entity instanceof Mob mob && !mob.isNoAi()) mob.setNoAi(true);
    }

    /**
     * Cataclysm 3.33 的 setIsSword 是独立同步姿态，不会进入攻击动画的伤害帧。
     * 反射保持对其他 3.x 小版本的软兼容；失败时仅保留粒子演出。
     */
    public static void setCeremonialSword(Entity entity, boolean raised) {
        if (!isManaged(entity)) return;
        try {
            entity.getClass().getMethod("setIsSword", boolean.class).invoke(entity, raised);
        } catch (ReflectiveOperationException ignored) {
            // 不调用 MAGIC_ATTACK/PHASE_3 等危险动画作为降级方案。
        }
    }

    /** 只播放一次原版 PHASE_2 的举剑段；施工第 25 刻结束，避开第 29 帧冲击。 */
    public static void playBuildGesture(Entity entity) {
        if (!isManaged(entity)) return;
        playAnimation(entity, "PHASE_2");
    }

    public static void stopBuildGesture(Entity entity) {
        if (isManaged(entity)) playAnimation(entity, "NO_ANIMATION");
    }

    private static void playAnimation(Entity entity, String animationName) {
        try {
            Class<?> animationClass = Class.forName("com.github.L_Ender.lionfishapi.server.animation.Animation");
            Class<?> handlerClass = Class.forName("com.github.L_Ender.lionfishapi.server.animation.AnimationHandler");
            Object animation = "NO_ANIMATION".equals(animationName)
                    ? Class.forName("com.github.L_Ender.lionfishapi.server.animation.IAnimatedEntity")
                        .getField(animationName).get(null)
                    : entity.getClass().getField(animationName).get(null);
            Method setTick = entity.getClass().getMethod("setAnimationTick", int.class);
            setTick.invoke(entity, 0);
            handlerClass.getMethod("sendAnimationMessage", Entity.class, animationClass)
                    .invoke(handlerClass.getField("INSTANCE").get(null), entity, animation);
            if (!"NO_ANIMATION".equals(animationName)) setTick.invoke(entity, 2);
        } catch (ReflectiveOperationException ignored) {
            // 缺少 Cataclysm/Lionfish 动画接口时保留同步的持剑姿势。
        }
    }
}
