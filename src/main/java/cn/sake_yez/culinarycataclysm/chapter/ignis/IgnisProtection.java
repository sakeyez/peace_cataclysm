package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.boss.IgnisPeaceController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

public final class IgnisProtection {
    private IgnisProtection() {
    }

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity victim = event.getEntity();
        Entity source = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        boolean chefVictim = victim.getPersistentData().getBoolean("CulinaryCataclysmChef");
        boolean managedSource = source != null && IgnisPeaceController.isManaged(source);
        boolean managedProjectile = direct instanceof Projectile projectile
                && projectile.getOwner() != null && IgnisPeaceController.isManaged(projectile.getOwner());
        if (IgnisPeaceController.isManaged(victim) || chefVictim || managedSource || managedProjectile) event.setCanceled(true);
    }

    public static void onMobGriefing(EntityMobGriefingEvent event) {
        if (IgnisPeaceController.isManaged(event.getEntity())
                || event.getEntity().getPersistentData().getBoolean("CulinaryCataclysmChef")) event.setCanGrief(false);
    }

    public static void onItemJoin(ItemEntity item, ServerLevel level) {
        IgnisChallengeRegistry.get(level).at(level.dimension().location(), item.blockPosition()).ifPresent(record -> {
            if (record.isBlazeKitchen(item.blockPosition())) item.setNeverPickUp();
        });
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        IgnisChallengeRegistry.get(level).at(level.dimension().location(), event.getPos()).ifPresent(record -> event.setCanceled(true));
    }

    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(level);
        event.getAffectedBlocks().removeIf(pos -> data.at(level.dimension().location(), pos).isPresent());
    }

    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(level);
        if (data.at(level.dimension().location(), event.getPos()).isPresent()
                || data.at(level.dimension().location(), event.getFaceOffsetPos()).isPresent()) {
            event.setCanceled(true);
            return;
        }
        var structure = event.getStructureHelper();
        if (structure.resolve() && (structure.getToPush().stream()
                .anyMatch(pos -> data.at(level.dimension().location(), pos).isPresent())
                || structure.getToDestroy().stream()
                .anyMatch(pos -> data.at(level.dimension().location(), pos).isPresent()))) {
            event.setCanceled(true);
        }
    }

    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        IgnisChallengeRegistry.get(level).at(level.dimension().location(), event.getPos())
                .ifPresent(record -> event.setCanceled(true));
    }
}
