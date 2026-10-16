package cn.sake_yez.culinarycataclysm.competition;

import cn.sake_yez.culinarycataclysm.boss.IgnisPeaceController;
import cn.sake_yez.culinarycataclysm.build.ArenaPlacementValidator;
import cn.sake_yez.culinarycataclysm.build.MagicSchematicBuilder;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import cn.sake_yez.culinarycataclysm.integration.DishQualityEvaluator;
import cn.sake_yez.culinarycataclysm.npc.ArenaAudienceController;
import cn.sake_yez.culinarycataclysm.npc.BlazeChefController;
import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CookingCompetitionManager {
    private static final int OPENING_TICKS = 100;
    private static final int RESULT_TICKS = 100;
    private static final ResourceLocation ALTAR = ResourceLocation.fromNamespaceAndPath("cataclysm", "altar_of_fire");
    private static final ResourceLocation ASHES = ResourceLocation.fromNamespaceAndPath("cataclysm", "burning_ashes");
    private final Map<GlobalPos, PendingSummon> pendingSummons = new HashMap<>();
    private final Map<UUID, PendingPlate> pendingPlates = new HashMap<>();
    private final MagicSchematicBuilder builder = new MagicSchematicBuilder();
    private final CookingProcessTracker cookingTracker = new CookingProcessTracker();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("culinarycompetition")
                .then(Commands.literal("resume").executes(this::resumeCommand))
                .then(Commands.literal("cancel").executes(this::cancelCommand))
                .then(Commands.literal("end").executes(this::cancelCommand))
                .then(Commands.literal("status").executes(this::statusCommand))
                .then(Commands.literal("diagnose").executes(this::diagnoseCommand)));
    }

    private void startFromAltar(ServerLevel level, ServerPlayer player, Entity ignis, BlockPos altarPos) {
        CompetitionSavedData data = CompetitionSavedData.get(level);
        if (data.byPlayer(player.getUUID()).isPresent()) {
            player.sendSystemMessage(Component.translatable("message.culinary_cataclysm.already_active"));
            return;
        }
        if (!CookeryIntegration.requiredRegistriesPresent()) {
            player.sendSystemMessage(Component.translatable("message.culinary_cataclysm.dependencies_missing"));
            return;
        }
        List<ResourceLocation> recipes;
        try {
            recipes = CookeryIntegration.resolveConfiguredRecipes(level.getServer());
        } catch (IOException exception) {
            player.sendSystemMessage(Component.literal("比赛菜谱配置错误：" + exception.getMessage()));
            return;
        }
        // 玩家从祭坛南侧望向北侧：在上一版基础上再向北移三格，直接沿用大厅地板。
        // 焰魔留在祭坛上，不跟随厨房移动。
        BlockPos origin = altarPos.offset(-10, -2, -24);
        BlockPos center = altarPos.above();
        CompetitionRecord record = new CompetitionRecord(UUID.randomUUID(), player.getUUID(), ignis.getUUID(),
                level.dimension().location(), center, origin.offset(4, 2, 1), origin.offset(12, 2, 1), recipes);
        record.battlefieldOrigin = origin;
        record.twoSidedBuild = true;
        record.mirroredBuild = true;
        record.aboveGroundOnly = true;
        record.platedSubmission = true;
        final ArenaPlacementValidator.Result placement;
        try {
            placement = builder.prepare(level, record);
        } catch (IOException exception) {
            player.sendSystemMessage(Component.literal("场地模板读取失败：" + exception.getMessage()));
            return;
        }
        if (!placement.valid()) {
            player.sendSystemMessage(placement.message());
            return;
        }
        if (!ServingTable.canBuild(level, record)) {
            player.sendSystemMessage(Component.literal("比赛桌位置被占用，请清空两侧九格玻璃正中间上方的餐桌与摆盘空间。"));
            return;
        }
        record.state = CompetitionState.OPENING;
        IgnisPeaceController.apply(ignis, record.competitionId);
        data.put(record);
        announce(level, record, Component.translatable("message.culinary_cataclysm.opening"));
        player.sendSystemMessage(Component.literal("已启动比赛 " + record.competitionId));
    }

    private int resumeCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        CompetitionSavedData data = CompetitionSavedData.get(source.getLevel());
        Optional<CompetitionRecord> optional = data.byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        CompetitionRecord record = optional.get();
        if (record.state == CompetitionState.INTERRUPTED) {
            // 料理事务中断后不盲目重放投料；恢复原状态、锅具和账本，不再次配给原料。
            record.state = record.interruptedState == CompetitionState.IDLE
                    ? (record.kitchensBuilt ? CompetitionState.ROUND_ACTIVE : CompetitionState.BUILDING)
                    : record.interruptedState;
            record.interruptedState = CompetitionState.IDLE;
            record.stateTicks = 0;
            data.setDirty();
        }
        source.sendSuccess(() -> statusText(record), false);
        return 1;
    }

    private int cancelCommand(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        CompetitionSavedData data = CompetitionSavedData.get(player.serverLevel());
        Optional<CompetitionRecord> optional = data.byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        CompetitionRecord record = optional.get();
        if (record.state != CompetitionState.CLEANUP
                && !(record.state == CompetitionState.INTERRUPTED
                    && record.interruptedState == CompetitionState.CLEANUP)) {
            builder.cancelVisuals(record);
            record.cleanupTargetCount = record.buildCursor;
            record.buildCursor = 0;
        }
        record.state = CompetitionState.CLEANUP;
        record.stateTicks = 0;
        data.setDirty();
        context.getSource().sendSuccess(() -> Component.literal("正在还原比赛场地，完成后可重新召唤焰魔。"), false);
        return 1;
    }

    private int statusCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Optional<CompetitionRecord> optional = CompetitionSavedData.get(source.getLevel()).byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        source.sendSuccess(() -> statusText(optional.get()), false);
        return 1;
    }

    private int diagnoseCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Optional<CompetitionRecord> optional = CompetitionSavedData.get(source.getLevel()).byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        CompetitionRecord record = optional.get();
        CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFactsUnchecked(source.getServer(), record.currentRecipe());
        BlockPos playerStationPos = CookeryIntegration.playerStation(record, facts);
        BlockPos blazeStationPos = CookeryIntegration.blazeStation(record, facts);
        var playerStation = CookeryIntegration.inspectStation(source.getLevel(), playerStationPos, facts);
        var blazeStation = CookeryIntegration.inspectStation(source.getLevel(), blazeStationPos, facts);
        boolean playerStationTagged = CookeryIntegration.isTaggedPlayerStation(source.getLevel(), playerStationPos, record);
        boolean canContinue = facts.supported() && playerStation.blockEntityPresent() && playerStation.heated()
                && blazeStation.blockEntityPresent() && blazeStation.heated()
                && record.blazeWorkState != BlazeChefController.WorkState.FAILED;
        source.sendSuccess(() -> Component.literal("料理比赛诊断：" + (canContinue ? "可继续" : "需要管理员检查")
                + " | 当前菜谱=" + record.currentRecipe()
                + " | 玩家锅具=" + playerStation.blockEntityPresent() + "/热源=" + playerStation.heated()
                + " | NPC=" + record.blazeWorkState
                + (record.blazeFailureReason.isEmpty() ? "" : "/原因=" + record.blazeFailureReason)
                + " | 有效生产=" + (record.playerProducedCount + record.blazeProducedCount)
                + " | 已提交=" + (record.playerSubmittedCount + record.blazeSubmittedCount)), false);
        if (source.hasPermission(2)) {
            source.sendSuccess(() -> Component.literal("管理诊断：依赖 " + versions()
                    + " | 注册表=" + CookeryIntegration.requiredRegistriesPresent()
                    + " | 玩家锅=" + playerStationPos + "/标签=" + playerStationTagged
                    + "/状态=" + playerStation.status() + "/完成=" + playerStation.readyToExtract()
                    + "/配方=" + playerStation.recipeMatches() + "/结果=" + itemId(playerStation.result())
                    + " | NPC锅=" + blazeStationPos + "/状态=" + blazeStation.status()
                    + "/配方=" + blazeStation.recipeMatches()
                    + " | NPC航点=" + record.blazeRouteIndex
                    + "/移动停滞=" + record.blazeMoveStallTicks
                    + "/烹饪停滞=" + record.blazeCookingStallTicks
                    + " | 证据记录=" + record.dishEvidence.size()), false);
        }
        return canContinue ? 1 : 0;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        pendingSummons.entrySet().removeIf(entry -> {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            return level == null || level.getGameTime() > entry.getValue().expiresAt();
        });
        pendingPlates.entrySet().removeIf(entry -> server.getTickCount() > entry.getValue().expiresAt());
        CompetitionSavedData data = CompetitionSavedData.get(server.overworld());
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

    private void tickCompetition(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        builder.removeBlazeStool(level, record);
        if (record.platedSubmission && record.servingTableBuilt && record.state != CompetitionState.CLEANUP
                && ServingTable.bases(record).stream().anyMatch(pos -> !ServingTable.isReady(level, pos))) {
            if (!ServingTable.migratePreviousLayout(level, record)) {
                if (record.state != CompetitionState.INTERRUPTED) {
                    record.interruptedState = record.state;
                    record.state = CompetitionState.INTERRUPTED;
                    record.stateTicks = 0;
                    data.setDirty();
                    announce(level, record, Component.literal("餐桌迁移受阻，请检查两侧九格玻璃的中心位置。"));
                }
                return;
            }
            data.setDirty();
            announce(level, record, Component.literal("两张餐桌已移到各自九格玻璃的正中间。"));
        }
        Entity ignis = level.getEntity(record.ignisId);
        if (ignis == null && record.state != CompetitionState.CLEANUP) {
            if (record.state != CompetitionState.INTERRUPTED) {
                record.interruptedState = record.state;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.setDirty();
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
            case BUILDING -> tickBuilding(level, data, record);
            case READY -> {
                if (record.stateTicks == 1) BlazeChefController.spawn(level, record);
                if (record.stateTicks >= 40) startRound(level, data, record);
            }
            case ROUND_ACTIVE -> {
                BlazeChefController.tick(level, data, record);
                if (record.platedSubmission) tryJudgePlated(level, data, record);
            }
            case ROUND_SUBMITTED -> {
                if (record.stateTicks >= 20) transition(data, record, CompetitionState.JUDGING);
            }
            case JUDGING -> {
                if (record.stateTicks == 20) showRoundResult(level, record);
                if (record.stateTicks >= 60) transition(data, record, CompetitionState.ROUND_RESULT);
            }
            case ROUND_RESULT -> {
                if (record.stateTicks >= RESULT_TICKS) advanceRound(level, data, record);
            }
            case FINAL_RESULT -> tickFinalResult(level, data, record);
            case CLEANUP -> tickCleanup(level, data, record);
            default -> {
            }
        }
        if (record.stateTicks % 100 == 0) data.setDirty();
    }

    private void tickOpening(ServerLevel level, CompetitionSavedData data, CompetitionRecord record, Entity ignis) {
        face(ignis, record.battlefieldOrigin != null
                ? record.battlefieldOrigin.offset(10, 4, 6)
                : record.playerKitchenOrigin.offset(11, 0, 4));
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

    private void tickBuilding(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        Entity ignis = level.getEntity(record.ignisId);
        if (ignis != null) {
            IgnisPeaceController.setCeremonialSword(ignis, true);
            if (record.twoSidedBuild && record.stateTicks == 1) IgnisPeaceController.playBuildGesture(ignis);
            if (record.twoSidedBuild && record.stateTicks == 25) IgnisPeaceController.stopBuildGesture(ignis);
        }
        int previousCursor = record.buildCursor;
        MagicSchematicBuilder.BuildResult result = builder.tick(level, record);
        if (record.buildCursor != previousCursor) data.setDirty();
        if (result == MagicSchematicBuilder.BuildResult.COMPLETE) {
            if (record.platedSubmission && !ServingTable.build(level, record)) {
                record.interruptedState = CompetitionState.BUILDING;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.setDirty();
                announce(level, record, Component.literal("餐桌搭建受阻，请检查两侧九格玻璃正中间上方后恢复比赛。"));
                return;
            }
            record.kitchensBuilt = true;
            CookeryIntegration.markCompetitionStations(level, record);
            if (ignis != null) {
                IgnisPeaceController.stopBuildGesture(ignis);
                IgnisPeaceController.setCeremonialSword(ignis, false);
            }
            transition(data, record, CompetitionState.READY);
            announce(level, record, Component.translatable("message.culinary_cataclysm.kitchens_ready"));
        } else if (result == MagicSchematicBuilder.BuildResult.BLOCKED || result == MagicSchematicBuilder.BuildResult.ERROR) {
            builder.cancelVisuals(record);
            if (ignis != null) {
                IgnisPeaceController.stopBuildGesture(ignis);
                IgnisPeaceController.setCeremonialSword(ignis, false);
            }
            record.interruptedState = CompetitionState.BUILDING;
            record.state = CompetitionState.INTERRUPTED;
            record.stateTicks = 0;
            data.setDirty();
            announce(level, record, Component.translatable("message.culinary_cataclysm.build_interrupted"));
        }
    }

    private void startRound(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        if (record.platedSubmission) ServingTable.clearDishes(level, record);
        record.platedJudgingAtGameTime = 0;
        // 兼容已建好厨房的旧存档：每轮开始时重写一次锅具归属，不做任何持续扫描。
        CookeryIntegration.markCompetitionStations(level, record);
        transition(data, record, CompetitionState.ROUND_ACTIVE);
        record.stationInteractions = 0;
        record.playerVerifiedOperations = 0;
        record.playerCookingStartedTick = 0;
        record.playerRoundIngredients.clear();
        record.currentPlayerDishId = null;
        record.playerPlatedDishId = null;
        BlazeChefController.beginRound(record);
        data.setDirty();
        showRoundProgress(level, record);
    }

    private void advanceRound(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        record.currentRound++;
        if (record.currentRound >= record.recipes.size()) transition(data, record, CompetitionState.FINAL_RESULT);
        else startRound(level, data, record);
    }

    private void tickFinalResult(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(record.playerId);
        if (!record.finalResultResolved && player != null) {
            Component result;
            if (record.playerTotal > record.blazeTotal) {
                CompetitionRewardHandler.grant(player, record);
                result = Component.translatable("message.culinary_cataclysm.final_win", record.playerTotal, record.blazeTotal);
            } else if (record.playerTotal < record.blazeTotal) {
                result = Component.translatable("message.culinary_cataclysm.final_loss", record.playerTotal, record.blazeTotal);
            } else {
                result = Component.translatable("message.culinary_cataclysm.final_draw", record.playerTotal);
            }
            announce(level, record, result);
            record.finalResultResolved = true;
            data.setDirty();
        }
        if (record.finalResultResolved && record.stateTicks >= 200) {
            record.cleanupTargetCount = record.buildCursor;
            record.buildCursor = 0;
            transition(data, record, CompetitionState.CLEANUP);
        }
    }

    private void tickCleanup(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        ArenaAudienceController.cleanup(level, record);
        if (record.platedSubmission) ServingTable.cleanup(level, record);
        MagicSchematicBuilder.BuildResult result = builder.cleanup(level, record);
        if (result == MagicSchematicBuilder.BuildResult.COMPLETE) {
            if (record.blazeChefId != null) {
                Entity chef = level.getEntity(record.blazeChefId);
                if (chef != null) chef.discard();
            }
            record.blazeInventory.clear();
            record.state = CompetitionState.COMPLETED;
            record.stateTicks = 0;
            data.setDirty();
        } else if (result == MagicSchematicBuilder.BuildResult.ERROR) {
            record.interruptedState = CompetitionState.CLEANUP;
            record.state = CompetitionState.INTERRUPTED;
            data.setDirty();
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() == InteractionHand.MAIN_HAND
                && !player.isShiftKeyDown()
                && ALTAR.equals(BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(event.getPos()).getBlock()))
                && ASHES.equals(BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()))
                && altarSlotEmpty(player.serverLevel(), event.getPos())) {
            GlobalPos altar = GlobalPos.of(player.serverLevel().dimension(), event.getPos().immutable());
            pendingSummons.putIfAbsent(altar, new PendingSummon(player.getUUID(), player.serverLevel().getGameTime() + 180));
        }
        CompetitionSavedData data = CompetitionSavedData.get(player.serverLevel());
        Optional<CompetitionRecord> optional = data.at(player.serverLevel().dimension().location(), event.getPos());
        if (optional.isEmpty()) return;
        CompetitionRecord record = optional.get();
        if (record.platedSubmission) {
            if (event.getPos().equals(record.playerServingBase().above())
                    || event.getPos().equals(record.blazeServingBase().above())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                return;
            }
            if (event.getPos().equals(record.playerServingBase())) {
                if (event.getHand() != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                        || event.getFace() != Direction.UP || record.state != CompetitionState.ROUND_ACTIVE
                        || record.playerPlatedDishId != null || !record.servingTableBuilt
                        || !record.playerId.equals(player.getUUID())
                        || !player.serverLevel().getBlockState(record.playerServingBase().above()).isAir()) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    return;
                }
                ItemStack held = event.getItemStack();
                Optional<DishProvenance.Marker> marker = DishProvenance.read(held);
                Optional<CookedDishEvidence> evidence = marker.flatMap(proof -> record.evidence(proof.dishId()));
                ItemStack expected = CookeryIntegration.expectedDish(player.serverLevel().getServer(), record.currentRecipe());
                if (marker.isEmpty() || evidence.isEmpty() || !ServingTable.isPlaceableDish(held)
                        || !held.is(expected.getItem()) || evidence.get().submitted
                        || evidence.get().producer != CookedDishEvidence.Producer.PLAYER
                        || !evidence.get().authorId.equals(player.getUUID())
                        || !evidence.get().competitionId.equals(record.competitionId)
                        || !evidence.get().recipeId.equals(record.currentRecipe())
                        || evidence.get().round != record.currentRound
                        || !evidence.get().cookeryQuality.equals(CookeryIntegration.quality(held))
                        || !marker.get().competitionId().equals(record.competitionId)
                        || marker.get().round() != record.currentRound) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    player.displayClientMessage(Component.translatable("message.culinary_cataclysm.unverified_dish"), true);
                    return;
                }
                pendingPlates.put(player.getUUID(), new PendingPlate(record.competitionId,
                        marker.get().dishId(), record.playerServingBase().above(), held.copyWithCount(1),
                        player.serverLevel().getServer().getTickCount() + 2));
                // 诡异木餐桌会优先收纳右击物品；此处跳过其交互，让方块物品执行原生 3D 放置。
                event.setUseBlock(TriState.FALSE);
                return;
            }
        }
        if (!record.playerId.equals(player.getUUID()) || record.isBlazeKitchen(event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.protected_station"), true);
            return;
        }
        if (record.state == CompetitionState.ROUND_ACTIVE && record.isPlayerStation(event.getPos())) {
            // 事件式迁移旧比赛：玩家第一次再碰锅时补写标签，不需要后台扫描。
            if (!CookeryIntegration.isTaggedPlayerStation(player.serverLevel(), event.getPos(), record)) {
                CookeryIntegration.markCompetitionStations(player.serverLevel(), record);
            }
            cookingTracker.beforeStationInteraction(player, record, event.getPos());
        }
    }

    @SubscribeEvent
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
            IgnisPeaceController.altarHome(event.getTarget()).ifPresent(home -> {
                if (home.dimension().equals(player.serverLevel().dimension())
                        && player.distanceToSqr(home.pos().getX() + 0.5, home.pos().getY() + 0.5,
                            home.pos().getZ() + 0.5) <= 64.0 * 64.0
                        && ALTAR.equals(BuiltInRegistries.BLOCK.getKey(
                            player.serverLevel().getBlockState(home.pos()).getBlock()))) {
                    startFromAltar(player.serverLevel(), player, event.getTarget(), home.pos());
                }
            });
            if (IgnisPeaceController.isManaged(event.getTarget())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (!IgnisPeaceController.isManaged(event.getTarget())) return;
        CompetitionSavedData data = CompetitionSavedData.get(player.serverLevel());
        Optional<CompetitionRecord> optional = data.byIgnis(event.getTarget().getUUID());
        if (optional.isEmpty()) return;
        CompetitionRecord record = optional.get();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (record.platedSubmission) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.place_on_table"), true);
            return;
        }
        if (!record.playerId.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.spectator_submit"), true);
            return;
        }
        if (record.state != CompetitionState.ROUND_ACTIVE) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.not_accepting"), true);
            return;
        }
        submitRound(player, data, record, event.getItemStack());
    }

    private void submitRound(ServerPlayer player, CompetitionSavedData data, CompetitionRecord record, ItemStack held) {
        Optional<DishProvenance.Marker> marker = DishProvenance.read(held);
        if (marker.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.unverified_dish"), true);
            return;
        }
        DishProvenance.Marker proof = marker.get();
        if (!proof.competitionId().equals(record.competitionId) || proof.round() != record.currentRound) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.wrong_competition_dish"), true);
            return;
        }
        Optional<CookedDishEvidence> playerEvidenceOptional = record.evidence(proof.dishId());
        Optional<CookedDishEvidence> blazeEvidenceOptional = record.evidence(record.currentBlazeDishId);
        if (playerEvidenceOptional.isEmpty() || blazeEvidenceOptional.isEmpty()
                || record.blazeWorkState != BlazeChefController.WorkState.FINISHED) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.wait_blaze"), true);
            return;
        }
        CookedDishEvidence playerEvidence = playerEvidenceOptional.get();
        CookedDishEvidence blazeEvidence = blazeEvidenceOptional.get();
        ItemStack expected = CookeryIntegration.expectedDish(player.serverLevel().getServer(), record.currentRecipe());
        if (playerEvidence.submitted || blazeEvidence.submitted
                || !playerEvidence.authorId.equals(player.getUUID())
                || playerEvidence.round != record.currentRound
                || !playerEvidence.recipeId.equals(record.currentRecipe())
                || !held.is(expected.getItem())) {
            player.displayClientMessage(Component.translatable("message.culinary_cataclysm.invalid_or_used_dish"), true);
            return;
        }

        DishQualityEvaluator.Evaluation playerEvaluation = DishQualityEvaluator.evaluate(playerEvidence);
        DishQualityEvaluator.Evaluation blazeEvaluation = DishQualityEvaluator.evaluate(blazeEvidence);
        // 全部校验通过后一次性提交；先消费再标记，避免未经消费的重复得分。
        held.shrink(1);
        playerEvidence.submitted = true;
        blazeEvidence.submitted = true;
        record.playerSubmittedCount++;
        record.blazeSubmittedCount++;
        record.playerScores.add(playerEvaluation.score());
        record.blazeScores.add(blazeEvaluation.score());
        record.playerTotal += playerEvaluation.score();
        record.blazeTotal += blazeEvaluation.score();
        Entity chef = player.serverLevel().getEntity(record.blazeChefId);
        if (chef instanceof Mob mob) mob.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        record.state = CompetitionState.ROUND_SUBMITTED;
        record.stateTicks = 0;
        data.setDirty();
        player.sendSystemMessage(Component.literal("玩家评分依据：" + String.join("，", playerEvaluation.reasons())));
        player.sendSystemMessage(Component.literal("烈焰人评分依据：" + String.join("，", blazeEvaluation.reasons())));
    }

    private void tryJudgePlated(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        if (record.playerPlatedDishId == null || record.blazePlatedDishId == null
                || record.blazeWorkState != BlazeChefController.WorkState.FINISHED) {
            resetPlatedJudgingTimer(data, record);
            return;
        }
        Optional<CookedDishEvidence> playerOptional = record.evidence(record.playerPlatedDishId);
        Optional<CookedDishEvidence> blazeOptional = record.evidence(record.blazePlatedDishId);
        if (playerOptional.isEmpty() || blazeOptional.isEmpty()) {
            resetPlatedJudgingTimer(data, record);
            return;
        }
        CookedDishEvidence playerDish = playerOptional.get();
        CookedDishEvidence blazeDish = blazeOptional.get();
        ItemStack expected = CookeryIntegration.expectedDish(level.getServer(), record.currentRecipe());
        if (expected.isEmpty() || playerDish.submitted || blazeDish.submitted
                || playerDish.producer != CookedDishEvidence.Producer.PLAYER
                || blazeDish.producer != CookedDishEvidence.Producer.BLAZE_CHEF
                || !playerDish.authorId.equals(record.playerId)
                || !blazeDish.authorId.equals(record.blazeChefId)
                || !playerDish.competitionId.equals(record.competitionId)
                || !blazeDish.competitionId.equals(record.competitionId)
                || playerDish.round != record.currentRound || blazeDish.round != record.currentRound
                || !playerDish.recipeId.equals(record.currentRecipe())
                || !blazeDish.recipeId.equals(record.currentRecipe())
                || !playerDish.resultItemId.equals(BuiltInRegistries.ITEM.getKey(expected.getItem()))
                || !blazeDish.resultItemId.equals(BuiltInRegistries.ITEM.getKey(expected.getItem()))
                || !matchesPlatedEvidence(level, record.playerServingBase().above(), expected, playerDish)
                || !matchesPlatedEvidence(level, record.blazeServingBase().above(), expected, blazeDish)) {
            resetPlatedJudgingTimer(data, record);
            return;
        }

        if (record.platedJudgingAtGameTime == 0) {
            record.platedJudgingAtGameTime = level.getGameTime() + 40;
            data.setDirty();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(record.playerId);
            if (player != null) player.displayClientMessage(
                    Component.translatable("message.culinary_cataclysm.dishes_displaying"), true);
            return;
        }
        if (level.getGameTime() < record.platedJudgingAtGameTime) return;

        DishQualityEvaluator.Evaluation playerEvaluation = DishQualityEvaluator.evaluate(playerDish);
        DishQualityEvaluator.Evaluation blazeEvaluation = DishQualityEvaluator.evaluate(blazeDish);
        ServingTable.clearDishes(level, record);
        record.platedJudgingAtGameTime = 0;
        playerDish.submitted = true;
        blazeDish.submitted = true;
        record.playerSubmittedCount++;
        record.blazeSubmittedCount++;
        record.playerScores.add(playerEvaluation.score());
        record.blazeScores.add(blazeEvaluation.score());
        record.playerTotal += playerEvaluation.score();
        record.blazeTotal += blazeEvaluation.score();
        transition(data, record, CompetitionState.ROUND_SUBMITTED);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(record.playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal("玩家评分依据：" + String.join("，", playerEvaluation.reasons())));
            player.sendSystemMessage(Component.literal("烈焰人评分依据：" + String.join("，", blazeEvaluation.reasons())));
        }
    }

    private static void resetPlatedJudgingTimer(CompetitionSavedData data, CompetitionRecord record) {
        if (record.platedJudgingAtGameTime != 0) {
            record.platedJudgingAtGameTime = 0;
            data.setDirty();
        }
    }

    private static boolean matchesPlatedEvidence(ServerLevel level, BlockPos pos, ItemStack expected,
                                                   CookedDishEvidence evidence) {
        if (!(expected.getItem() instanceof BlockItem blockItem)) return false;
        var state = level.getBlockState(pos);
        return state.is(blockItem.getBlock()) && state.hasProperty(FoodBiteBlock.QUALITY)
                && Quality.BY_ID.apply(state.getValue(FoodBiteBlock.QUALITY))
                    .getSerializedName().equals(evidence.cookeryQuality);
    }

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity victim = event.getEntity();
        Entity source = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        boolean chefVictim = victim.getPersistentData().getBoolean("CulinaryCataclysmChef");
        boolean managedSource = source != null && IgnisPeaceController.isManaged(source);
        boolean managedProjectile = direct instanceof Projectile projectile
                && projectile.getOwner() != null && IgnisPeaceController.isManaged(projectile.getOwner());
        if (IgnisPeaceController.isManaged(victim) || chefVictim || managedSource || managedProjectile) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onMobGriefing(EntityMobGriefingEvent event) {
        if (IgnisPeaceController.isManaged(event.getEntity())
                || event.getEntity().getPersistentData().getBoolean("CulinaryCataclysmChef")) event.setCanGrief(false);
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity().getPersistentData().getBoolean("CulinaryCataclysmFlight")) {
            // 飞行展示不是权威方块；重载后按 buildCursor 重新调度，避免幽灵实体。
            event.getEntity().discard();
            return;
        }
        if (IgnisPeaceController.isManaged(event.getEntity())) IgnisPeaceController.maintain(event.getEntity());
        if (!event.loadedFromDisk() && event.getLevel() instanceof ServerLevel level
                && IgnisPeaceController.isIgnis(event.getEntity())) {
            IgnisPeaceController.altarHome(event.getEntity()).ifPresent(home -> {
                PendingSummon pending = pendingSummons.remove(home);
                if (pending == null || level.getGameTime() > pending.expiresAt()) return;
                ServerPlayer summoner = level.getServer().getPlayerList().getPlayer(pending.playerId());
                if (summoner == null || summoner.serverLevel() != level
                        || summoner.distanceToSqr(home.pos().getX() + 0.5, home.pos().getY() + 0.5,
                            home.pos().getZ() + 0.5) > 64.0 * 64.0) return;
                startFromAltar(level, summoner, event.getEntity(), home.pos());
            });
        }
        if (event.getEntity() instanceof ItemEntity item && event.getLevel() instanceof ServerLevel level) {
            CompetitionSavedData.get(level).at(level.dimension().location(), item.blockPosition()).ifPresent(record -> {
                if (record.isBlazeKitchen(item.blockPosition())) item.setNeverPickUp();
            });
        }
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompetitionSavedData.get(level).at(level.dimension().location(), event.getPos()).ifPresent(record -> event.setCanceled(true));
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompetitionSavedData data = CompetitionSavedData.get(level);
        data.at(level.dimension().location(), event.getPos()).ifPresent(record -> {
            if (record.platedSubmission && event.getPos().equals(record.playerServingBase().above())
                    && event.getEntity() instanceof ServerPlayer player) {
                PendingPlate pending = pendingPlates.remove(player.getUUID());
                if (pending != null && pending.competitionId().equals(record.competitionId)
                        && pending.pos().equals(event.getPos())
                        && level.getServer().getTickCount() <= pending.expiresAt()
                        && record.playerId.equals(player.getUUID())
                        && record.state == CompetitionState.ROUND_ACTIVE
                        && record.playerPlatedDishId == null
                        && ServingTable.matchesDish(level, event.getPos(), pending.stack())) {
                    record.playerPlatedDishId = pending.dishId();
                    data.setDirty();
                    player.displayClientMessage(Component.translatable("message.culinary_cataclysm.dish_plated"), true);
                    return;
                }
            }
            event.setCanceled(true);
        });
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompetitionSavedData data = CompetitionSavedData.get(level);
        event.getAffectedBlocks().removeIf(pos -> data.at(level.dimension().location(), pos).isPresent());
    }

    @SubscribeEvent
    public void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompetitionSavedData data = CompetitionSavedData.get(level);
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

    @SubscribeEvent
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        CompetitionSavedData.get(level).at(level.dimension().location(), event.getPos())
                .ifPresent(record -> event.setCanceled(true));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        pendingPlates.remove(player.getUUID());
        CompetitionSavedData data = CompetitionSavedData.get(player.serverLevel());
        data.byPlayer(player.getUUID()).ifPresent(record -> {
            // 仅未提交的活跃料理事务需要停下；评审、公布和清理均可安全继续。
            if (record.state == CompetitionState.ROUND_ACTIVE) {
                record.interruptedState = CompetitionState.ROUND_ACTIVE;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.setDirty();
            }
        });
    }

    private record PendingSummon(UUID playerId, long expiresAt) {
    }

    private record PendingPlate(UUID competitionId, UUID dishId, BlockPos pos,
                                ItemStack stack, long expiresAt) {
    }

    private static boolean altarSlotEmpty(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return false;
        try {
            Object item = blockEntity.getClass().getMethod("getItem", int.class).invoke(blockEntity, 0);
            return item instanceof ItemStack stack && stack.isEmpty();
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
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

    private static void transition(CompetitionSavedData data, CompetitionRecord record, CompetitionState state) {
        record.state = state;
        record.stateTicks = 0;
        data.setDirty();
    }

    private static void showRoundProgress(ServerLevel level, CompetitionRecord record) {
        ItemStack expected = CookeryIntegration.expectedDish(level.getServer(), record.currentRecipe());
        announce(level, record, Component.translatable("message.culinary_cataclysm.round",
                record.currentRound + 1, record.recipes.size(), expected.getHoverName(), record.playerTotal, record.blazeTotal));
    }

    private static void showRoundResult(ServerLevel level, CompetitionRecord record) {
        int playerScore = record.playerScores.get(record.playerScores.size() - 1);
        int blazeScore = record.blazeScores.get(record.blazeScores.size() - 1);
        announce(level, record, Component.translatable("message.culinary_cataclysm.round_result",
                playerScore, blazeScore, record.playerTotal, record.blazeTotal));
    }

    private static void announce(ServerLevel level, CompetitionRecord record, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(record.arenaCenter.getX(), record.arenaCenter.getY(), record.arenaCenter.getZ()) <= 96.0 * 96.0) {
                player.sendSystemMessage(Component.translatable("message.culinary_cataclysm.ignis_prefix").append(message));
            }
        }
    }

    private static Component statusText(CompetitionRecord record) {
        return Component.literal("比赛 " + record.competitionId + " | " + record.state + " | 轮次 "
                + (record.currentRound + 1) + "/" + record.recipes.size() + " | "
                + record.playerTotal + " : " + record.blazeTotal + " | NPC " + record.blazeWorkState);
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "空" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String versions() {
        return "Cataclysm=" + version("cataclysm") + ", Create=" + version("create")
                + ", Kaleidoscope Cookery=" + version("kaleidoscope_cookery")
                + ", NeoForge=" + version("neoforge");
    }

    private static String version(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("未加载");
    }
}
