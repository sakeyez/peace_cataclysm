package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.boss.IgnisPeaceController;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import cn.sake_yez.culinarycataclysm.competition.CookingProcessTracker;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import cn.sake_yez.culinarycataclysm.npc.ArenaAudienceController;
import cn.sake_yez.culinarycataclysm.npc.BlazeChefController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IgnisChapter {
    private static final int OPENING_TICKS = 100;
    private static final int RESULT_TICKS = 100;
    private final IgnisDishSubmission dishSubmission = new IgnisDishSubmission();
    private final IgnisBuildController buildController = new IgnisBuildController();
    private final IgnisCommands commands = new IgnisCommands(buildController);
    private final IgnisTrigger trigger = new IgnisTrigger(buildController);
    private final CookingProcessTracker cookingTracker = new CookingProcessTracker();

    public void onRegisterCommands(RegisterCommandsEvent event) {
        commands.register(event.getDispatcher());
    }

    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        trigger.tick(server);
        dishSubmission.tick(server);
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(server.overworld());
        cookingTracker.tick(server, data);
        List<CompetitionRecord> snapshot = new ArrayList<>(data.all());
        for (CompetitionRecord record : snapshot) {
            if (record.state == CompetitionState.COMPLETED) continue;
            ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, record.dimension);
            ServerLevel level = server.getLevel(key);
            if (level == null) continue;
            tickCompetition(level, data, record);
        }
    }

    private void tickCompetition(ServerLevel level, IgnisChallengeRegistry data, CompetitionRecord record) {
        if (record.servingTableBuilt && record.state != CompetitionState.CLEANUP
                && ServingTable.bases(record).stream().anyMatch(pos -> !ServingTable.isReady(level, pos))) {
            if (record.state != CompetitionState.INTERRUPTED) {
                record.interruptedState = record.state;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.changed(record);
                IgnisPresentation.announce(level, record, Component.literal("餐桌缺失，请检查两侧九格玻璃的中心位置。"));
            }
            return;
        }
        Entity ignis = level.getEntity(record.ignisId);
        if (ignis == null && record.state != CompetitionState.CLEANUP) {
            if (record.state != CompetitionState.INTERRUPTED) {
                record.interruptedState = record.state;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.changed(record);
            }
            return;
        }
        if (ignis != null) IgnisPeaceController.maintain(ignis);
        record.stateTicks++;
        if (record.kitchensBuilt && record.state != CompetitionState.CLEANUP
                && record.state != CompetitionState.INTERRUPTED && record.stateTicks % 100 == 1) {
            ArenaAudienceController.maintain(level, record);
        }
        switch (record.state) {
            case OPENING -> tickOpening(level, data, record, ignis);
            case BUILDING -> buildController.tickBuilding(level, data, record);
            case READY -> {
                if (record.stateTicks == 1) BlazeChefController.spawn(level, record);
                if (record.stateTicks >= 40) IgnisRounds.startRound(level, data, record);
            }
            case ROUND_ACTIVE -> {
                BlazeChefController.tick(level, data, record);
                dishSubmission.tryJudgePlated(level, data, record);
            }
            case ROUND_SUBMITTED -> {
                if (record.stateTicks >= 20) transition(data, record, CompetitionState.JUDGING);
            }
            case JUDGING -> {
                if (record.stateTicks == 20) IgnisPresentation.showRoundResult(level, record);
                if (record.stateTicks >= 60) transition(data, record, CompetitionState.ROUND_RESULT);
            }
            case ROUND_RESULT -> {
                if (record.stateTicks >= RESULT_TICKS) IgnisRounds.advanceRound(level, data, record);
            }
            case FINAL_RESULT -> IgnisSettlement.tickFinalResult(level, data, record);
            case CLEANUP -> buildController.tickCleanup(level, data, record);
            default -> {
            }
        }
        if (record.state != CompetitionState.COMPLETED && record.stateTicks % 100 == 0) data.changed(record);
    }

    private void tickOpening(ServerLevel level, IgnisChallengeRegistry data, CompetitionRecord record, Entity ignis) {
        face(ignis, record.battlefieldOrigin.offset(10, 4, 6));
        IgnisPeaceController.setCeremonialSword(ignis, record.stateTicks >= 20);
        if (record.stateTicks >= 30 && record.stateTicks <= 85 && record.stateTicks % 3 == 0) {
            level.sendParticles(ParticleTypes.FLAME, ignis.getX(), ignis.getY() + ignis.getBbHeight() + 1.5,
                    ignis.getZ(), 5, 0.25, 0.5, 0.25, 0.02);
        }
        if (record.stateTicks == 45) {
            level.playSound(null, ignis.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2F, 0.55F);
        }
        if (record.stateTicks >= OPENING_TICKS) {
            ignis.teleportTo(record.arenaCenter.getX() + 0.5, record.arenaCenter.getY(),
                    record.arenaCenter.getZ() + 0.5);
            transition(data, record, CompetitionState.BUILDING);
        }
    }

    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        trigger.trackAltarClick(player, event.getHand(), event.getPos(), event.getItemStack());
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(player.serverLevel());
        Optional<CompetitionRecord> optional = data.at(player.serverLevel().dimension().location(), event.getPos());
        if (optional.isEmpty()) return;
        CompetitionRecord record = optional.get();
        if (dishSubmission.onRightClickBlock(event, player, record)) return;
        if (!record.playerId.equals(player.getUUID()) || record.isBlazeKitchen(event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.protected_station"), true);
            return;
        }
        if (record.state == CompetitionState.ROUND_ACTIVE && record.isPlayerStation(event.getPos())) {
            cookingTracker.beforeStationInteraction(player, record, event.getPos());
        }
    }

    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (ArenaAudienceController.isAudience(event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (!IgnisPeaceController.isManaged(event.getTarget())
                && IgnisPeaceController.isIgnis(event.getTarget())) {
            if (event.getHand() != InteractionHand.MAIN_HAND) return;
            trigger.tryStartFromInteraction(player, event.getTarget());
            if (IgnisPeaceController.isManaged(event.getTarget())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (!IgnisPeaceController.isManaged(event.getTarget())) return;
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(player.serverLevel());
        Optional<CompetitionRecord> optional = data.byIgnis(event.getTarget().getUUID());
        if (optional.isEmpty()) return;
        CompetitionRecord record = optional.get();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.displayClientMessage(Component.translatable("message.culinary_cataclysm.place_on_table"), true);
    }

    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        IgnisProtection.onIncomingDamage(event);
    }

    public void onMobGriefing(EntityMobGriefingEvent event) {
        IgnisProtection.onMobGriefing(event);
    }

    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity().getPersistentData().getBoolean("CulinaryCataclysmFlight")) {
            // 飞行展示不是权威方块；重载后按 buildCursor 重新调度，避免幽灵实体。
            event.getEntity().discard();
            return;
        }
        if (IgnisPeaceController.isManaged(event.getEntity())) IgnisPeaceController.maintain(event.getEntity());
        if (!event.loadedFromDisk() && event.getLevel() instanceof ServerLevel level
                && IgnisPeaceController.isIgnis(event.getEntity())) {
            trigger.onIgnisSpawn(level, event.getEntity());
        }
        if (event.getEntity() instanceof ItemEntity item && event.getLevel() instanceof ServerLevel level) {
            IgnisProtection.onItemJoin(item, level);
        }
    }

    public void onBreak(BlockEvent.BreakEvent event) {
        IgnisProtection.onBreak(event);
    }

    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        dishSubmission.onPlace(event);
    }

    public void onExplosion(ExplosionEvent.Detonate event) {
        IgnisProtection.onExplosion(event);
    }

    public void onPiston(PistonEvent.Pre event) {
        IgnisProtection.onPiston(event);
    }

    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        IgnisProtection.onFluidPlace(event);
    }

    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        dishSubmission.onLogout(player.getUUID());
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(player.serverLevel());
        data.byPlayer(player.getUUID()).ifPresent(record -> {
            // 仅未提交的活跃料理事务需要停下；评审、公布和清理均可安全继续。
            if (record.state == CompetitionState.ROUND_ACTIVE) {
                record.interruptedState = CompetitionState.ROUND_ACTIVE;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.changed(record);
            }
        });
    }

    private static void face(Entity entity, BlockPos target) {
        double dx = target.getX() + 0.5 - entity.getX();
        double dz = target.getZ() + 0.5 - entity.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
        if (entity instanceof Mob mob) mob.stopInPlace();
    }

    private static void transition(IgnisChallengeRegistry data, CompetitionRecord record, CompetitionState state) {
        record.state = state;
        record.stateTicks = 0;
        data.changed(record);
    }

}
