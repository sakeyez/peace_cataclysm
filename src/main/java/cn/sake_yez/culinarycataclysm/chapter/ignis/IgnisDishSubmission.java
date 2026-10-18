package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import cn.sake_yez.culinarycataclysm.competition.CookedDishEvidence;
import cn.sake_yez.culinarycataclysm.competition.DishProvenance;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import cn.sake_yez.culinarycataclysm.integration.DishQualityEvaluator;
import cn.sake_yez.culinarycataclysm.npc.BlazeChefController;
import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class IgnisDishSubmission {
    private final Map<UUID, PendingPlate> pendingPlates = new HashMap<>();

    public void tick(MinecraftServer server) {
        pendingPlates.entrySet().removeIf(entry -> server.getTickCount() > entry.getValue().expiresAt());
    }

    public void onLogout(UUID playerId) {
        pendingPlates.remove(playerId);
    }

    /** 返回 true 表示餐桌交互已由摆盘流程处理。 */
    public boolean onRightClickBlock(PlayerInteractEvent.RightClickBlock event, ServerPlayer player,
                                     CompetitionRecord record) {
        if (event.getPos().equals(record.playerServingBase().above())
                || event.getPos().equals(record.blazeServingBase().above())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return true;
        }
        if (!event.getPos().equals(record.playerServingBase())) return false;
        if (event.getHand() != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                || event.getFace() != Direction.UP || record.state != CompetitionState.ROUND_ACTIVE
                || record.playerPlatedDishId != null || !record.servingTableBuilt
                || !record.playerId.equals(player.getUUID())
                || !player.serverLevel().getBlockState(record.playerServingBase().above()).isAir()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return true;
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
            return true;
        }
        pendingPlates.put(player.getUUID(), new PendingPlate(record.competitionId,
                marker.get().dishId(), record.playerServingBase().above(), held.copyWithCount(1),
                player.serverLevel().getServer().getTickCount() + 2));
        // 诡异木餐桌会优先收纳右击物品；此处跳过其交互，让方块物品执行原生 3D 放置。
        event.setUseBlock(TriState.FALSE);
        return true;
    }

    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(level);
        data.at(level.dimension().location(), event.getPos()).ifPresent(record -> {
            if (event.getPos().equals(record.playerServingBase().above())
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
                    data.changed(record);
                    player.displayClientMessage(Component.translatable("message.culinary_cataclysm.dish_plated"), true);
                    return;
                }
            }
            event.setCanceled(true);
        });
    }

    public void tryJudgePlated(ServerLevel level, IgnisChallengeRegistry data, CompetitionRecord record) {
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
            data.changed(record);
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
        record.state = CompetitionState.ROUND_SUBMITTED;
        record.stateTicks = 0;
        data.changed(record);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(record.playerId);
        if (player != null) {
            player.sendSystemMessage(Component.literal("玩家评分依据：" + String.join("，", playerEvaluation.reasons())));
            player.sendSystemMessage(Component.literal("烈焰人评分依据：" + String.join("，", blazeEvaluation.reasons())));
        }
    }

    private static void resetPlatedJudgingTimer(IgnisChallengeRegistry data, CompetitionRecord record) {
        if (record.platedJudgingAtGameTime != 0) {
            record.platedJudgingAtGameTime = 0;
            data.changed(record);
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

    private record PendingPlate(UUID competitionId, UUID dishId, BlockPos pos,
                                ItemStack stack, long expiresAt) {
    }
}
