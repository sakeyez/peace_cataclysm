package cn.sake_yez.culinarycataclysm.competition;

import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisCookeryStations;

import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 以服务端事件前后快照观察森罗公开 BlockEntity 状态。
 * 只有已完成、配方匹配且真实取出导致的新成品才会签发来源证明。
 */
public final class CookingProcessTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("culinary_cataclysm/cooking_tracker");
    /**
     * 森罗会通过 ItemHandlerHelper 回填背包；给跨模组事件与背包同步留出短确认窗口，
     * 避免只检查下一 tick 时漏掉真实出锅。
     */
    private static final int EXTRACTION_CONFIRM_TICKS = 10;
    private static final double NEARBY_RESULT_RADIUS = 6.0;
    private final Map<InteractionKey, PendingInteraction> pending = new HashMap<>();

    public void beforeStationInteraction(ServerPlayer player, CompetitionRecord record, BlockPos station) {
        CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFactsUnchecked(
                player.serverLevel().getServer(), record.currentRecipe());
        if (!facts.supported() || !IgnisCookeryStations.isTaggedPlayerStation(player.serverLevel(), station, record)) return;
        CookeryIntegration.StationSnapshot snapshot = CookeryIntegration.inspectStation(player.serverLevel(), station, facts);
        long gameTime = player.serverLevel().getGameTime();
        InteractionKey key = new InteractionKey(player.getUUID(), station.immutable());
        PendingInteraction existing = pending.get(key);
        // 同一点击可能继续触发另一只手的交互事件；不能让出锅后的空锅快照覆盖有效的完成快照。
        if (existing != null && existing.isExtractionCandidate()
                && existing.competitionId().equals(record.competitionId)
                && existing.round() == record.currentRound && existing.station().equals(station)) {
            return;
        }
        ItemStack trackedResult = trackedResult(snapshot, facts);
        pending.put(key, new PendingInteraction(record.competitionId, record.currentRound, station,
                snapshot, countExpected(player, trackedResult),
                countNearby(player.serverLevel(), station, trackedResult), player.getMainHandItem().copy(), gameTime));
        if (snapshot.readyToExtract() && !snapshot.result().isEmpty()) {
            LOGGER.info("已识别标记比赛锅内成品，等待本次交互取出：competition={}, round={}, player={}, "
                            + "station={}, status={}, result={}, ingredients={}",
                    record.competitionId, record.currentRound + 1, player.getGameProfile().getName(), station,
                    snapshot.status(), BuiltInRegistries.ITEM.getKey(snapshot.result().getItem()), snapshot.ingredients());
        }
    }

    public void tick(MinecraftServer server, CompetitionSavedData data) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<InteractionKey, PendingInteraction> entry = iterator.next();
            PendingInteraction pendingInteraction = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey().playerId());
            if (player == null) {
                iterator.remove();
                continue;
            }
            ServerLevel level = player.serverLevel();
            if (level.getGameTime() <= pendingInteraction.gameTime()) continue;
            Optional<CompetitionRecord> optional = data.byId(pendingInteraction.competitionId());
            if (optional.isEmpty()) {
                iterator.remove();
                continue;
            }
            CompetitionRecord record = optional.get();
            if (record.state != CompetitionState.ROUND_ACTIVE || record.currentRound != pendingInteraction.round()) {
                iterator.remove();
                continue;
            }
            boolean finished = processAfter(player, data, record, pendingInteraction);
            if (finished) {
                iterator.remove();
                continue;
            }
            if (level.getGameTime() - pendingInteraction.gameTime() >= EXTRACTION_CONFIRM_TICKS) {
                CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFactsUnchecked(server, record.currentRecipe());
                ItemStack trackedResult = trackedResult(pendingInteraction.before(), facts);
                if (pendingInteraction.isExtractionCandidate()) {
                    LOGGER.warn("未能确认已标记比赛锅产出：competition={}, round={}, player={}, station={}, "
                                    + "status={}, result={}, expectedBefore={}, expectedNow={}, nearbyBefore={}, nearbyNow={}",
                            record.competitionId, record.currentRound + 1, player.getGameProfile().getName(),
                            pendingInteraction.station(), pendingInteraction.before().status(),
                            BuiltInRegistries.ITEM.getKey(trackedResult.getItem()),
                            pendingInteraction.expectedCountBefore(), countExpected(player, trackedResult),
                            pendingInteraction.nearbyCountBefore(), countNearby(level, pendingInteraction.station(), trackedResult));
                }
                iterator.remove();
            }
        }
    }

    private static boolean processAfter(ServerPlayer player, CompetitionSavedData data, CompetitionRecord record,
                                        PendingInteraction pending) {
        CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFactsUnchecked(
                player.serverLevel().getServer(), record.currentRecipe());
        CookeryIntegration.StationSnapshot after = CookeryIntegration.inspectStation(
                player.serverLevel(), pending.station(), facts);
        if (!after.blockEntityPresent()) return true;

        boolean changed = pending.before().status() != after.status()
                || pending.before().stirFryCount() != after.stirFryCount()
                || !pending.before().ingredients().equals(after.ingredients())
                || !ItemStack.matches(pending.heldBefore(), player.getMainHandItem());
        boolean firstConfirmationTick = player.serverLevel().getGameTime() == pending.gameTime() + 1;
        if (changed && firstConfirmationTick) {
            record.playerVerifiedOperations++;
            record.stationInteractions++;
            if (!after.ingredients().isEmpty()) {
                record.playerRoundIngredients.clear();
                record.playerRoundIngredients.addAll(after.ingredients());
            } else if (!pending.before().ingredients().isEmpty()) {
                record.playerRoundIngredients.clear();
                record.playerRoundIngredients.addAll(pending.before().ingredients());
            }
            if (record.playerCookingStartedTick == 0
                    && (after.status() != pending.before().status() || !after.ingredients().isEmpty())) {
                record.playerCookingStartedTick = record.stateTicks;
            }
            data.setDirty();
        }

        if (!pending.isExtractionCandidate()) return true;
        ItemStack trackedResult = trackedResult(pending.before(), facts);
        int expectedAfter = countExpected(player, trackedResult);
        int nearbyAfter = countNearby(player.serverLevel(), pending.station(), trackedResult);
        if (expectedAfter <= pending.expectedCountBefore() && nearbyAfter <= pending.nearbyCountBefore()) return false;

        boolean producedNearby = nearbyAfter > pending.nearbyCountBefore()
                && expectedAfter <= pending.expectedCountBefore();
        ItemStack produced = producedNearby
                ? findNearbyUnmarkedExpected(player.serverLevel(), pending.station(), trackedResult)
                : findUnmarkedExpected(player, trackedResult);
        if (produced.isEmpty()) {
            produced = producedNearby
                    ? findUnmarkedExpected(player, trackedResult)
                    : findNearbyUnmarkedExpected(player.serverLevel(), pending.station(), trackedResult);
        }
        if (produced.isEmpty()) return false;

        int requiredOperations = facts.ingredients().size() + (facts.station() == CookeryIntegration.StationType.POT ? 2 : 3);
        UUID dishId = UUID.randomUUID();
        ResourceLocation resultItemId = BuiltInRegistries.ITEM.getKey(produced.getItem());
        CookedDishEvidence evidence = new CookedDishEvidence(
                dishId, record.competitionId, record.currentRound, record.playerId,
                record.currentRecipe(), resultItemId, pending.station(), player.serverLevel().getGameTime(),
                record.playerRoundIngredients, CookedDishEvidence.Producer.PLAYER,
                CookeryIntegration.quality(produced), requiredOperations,
                record.playerVerifiedOperations,
                Math.max(1, record.stateTicks - record.playerCookingStartedTick),
                true, true, false);
        splitAndMark(player, produced, evidence, producedNearby);
        record.dishEvidence.add(evidence);
        record.currentPlayerDishId = dishId;
        record.playerProducedCount++;
        data.setDirty();
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(record.platedSubmission
                ? "message.culinary_cataclysm.dish_verified_plating"
                : "message.culinary_cataclysm.dish_verified"));
        LOGGER.info("比赛菜品来源已验证：competition={}, round={}, player={}, recipe={}, result={}, quality={}, dish={}",
                record.competitionId, record.currentRound + 1, player.getGameProfile().getName(),
                record.currentRecipe(), resultItemId, evidence.cookeryQuality, dishId);
        return true;
    }

    private static ItemStack trackedResult(CookeryIntegration.StationSnapshot snapshot,
                                           CookeryIntegration.RecipeFacts facts) {
        return snapshot.result().isEmpty() ? facts.result() : snapshot.result();
    }

    private static boolean matchesTrackedResult(ItemStack stack, ItemStack expected) {
        if (!stack.is(expected.getItem())) return false;
        return !com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils.hasQuality(expected)
                || ItemStack.isSameItemSameComponents(stack, expected);
    }

    private static int countExpected(ServerPlayer player, ItemStack expected) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (matchesTrackedResult(stack, expected)) count += stack.getCount();
        }
        return count;
    }

    private static int countNearby(ServerLevel level, BlockPos station, ItemStack expected) {
        int count = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(station).inflate(NEARBY_RESULT_RADIUS),
                entity -> matchesTrackedResult(entity.getItem(), expected))) {
            count += entity.getItem().getCount();
        }
        return count;
    }

    private static ItemStack findUnmarkedExpected(ServerPlayer player, ItemStack expected) {
        ItemStack mainHand = player.getMainHandItem();
        if (matchesTrackedResult(mainHand, expected) && DishProvenance.read(mainHand).isEmpty()) return mainHand;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (matchesTrackedResult(stack, expected) && DishProvenance.read(stack).isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findNearbyUnmarkedExpected(ServerLevel level, BlockPos station, ItemStack expected) {
        AABB box = new AABB(station).inflate(NEARBY_RESULT_RADIUS);
        return level.getEntitiesOfClass(ItemEntity.class, box, entity -> matchesTrackedResult(entity.getItem(), expected)
                        && DishProvenance.read(entity.getItem()).isEmpty()).stream()
                .findFirst().map(ItemEntity::getItem).orElse(ItemStack.EMPTY);
    }

    private static void splitAndMark(ServerPlayer player, ItemStack stack, CookedDishEvidence evidence,
                                     boolean worldDrop) {
        if (stack.getCount() == 1) {
            DishProvenance.mark(stack, evidence);
            return;
        }
        ItemStack signed = stack.copyWithCount(1);
        stack.shrink(1);
        DishProvenance.mark(signed, evidence);
        if (worldDrop) {
            ItemEntity entity = new ItemEntity(player.serverLevel(), player.getX(), player.getY() + 0.5,
                    player.getZ(), signed);
            entity.setDefaultPickUpDelay();
            player.serverLevel().addFreshEntity(entity);
        } else {
            player.getInventory().placeItemBackInInventory(signed);
        }
    }

    private record InteractionKey(UUID playerId, BlockPos station) {
    }

    private record PendingInteraction(UUID competitionId, int round, BlockPos station,
                                      CookeryIntegration.StationSnapshot before, int expectedCountBefore,
                                      int nearbyCountBefore, ItemStack heldBefore, long gameTime) {
        boolean isExtractionCandidate() {
            // 锅的比赛标签证明来源；森罗自己的 FINISHED 状态和非空结果证明它确实完成过烹饪。
            return before.readyToExtract() && !before.result().isEmpty();
        }
    }
}
