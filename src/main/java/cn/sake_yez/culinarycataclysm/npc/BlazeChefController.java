package cn.sake_yez.culinarycataclysm.npc;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionSavedData;
import cn.sake_yez.culinarycataclysm.competition.CookedDishEvidence;
import cn.sake_yez.culinarycataclysm.competition.DishProvenance;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 脚本化厨师：直接调用森罗 1.6.0 公开锅具接口，不伪造完成计时或成品。 */
public final class BlazeChefController {
    private static final String CHEF_KEY = "CulinaryCataclysmChef";
    private static final int STATE_TIMEOUT = 20 * 90;
    private static final int MOVE_STALL_TIMEOUT = 20 * 10;
    private static final int COOKING_STALL_TIMEOUT = 20 * 45;
    private static final int COOKING_TIMEOUT = 20 * 300;
    private static final double FLIGHT_SPEED = 0.12;
    private static final double ARRIVAL_DISTANCE_SQR = 0.15 * 0.15;
    private static final Logger LOGGER = LoggerFactory.getLogger("culinary_cataclysm/blaze_chef");

    private BlazeChefController() {
    }

    public static void spawn(ServerLevel level, CompetitionRecord record) {
        if (record.blazeChefId != null && level.getEntity(record.blazeChefId) != null) return;
        for (Mob existing : level.getEntitiesOfClass(Mob.class,
                new AABB(record.blazeKitchenOrigin).inflate(16.0),
                mob -> mob.getPersistentData().hasUUID("Competition")
                        && mob.getPersistentData().getUUID("Competition").equals(record.competitionId))) {
            record.blazeChefId = existing.getUUID();
            return;
        }
        Entity entity = EntityType.BLAZE.create(level);
        if (!(entity instanceof Mob blaze)) return;
        BlockPos station = record.blazeChefStandPos();
        blaze.moveTo(station.getX() + 0.5, station.getY(), station.getZ() + 0.5, 180.0F, 0.0F);
        blaze.setNoAi(true);
        blaze.setNoGravity(true);
        blaze.stopInPlace();
        blaze.setInvulnerable(true);
        blaze.setPersistenceRequired();
        blaze.setCustomName(Component.translatable("entity.culinary_cataclysm.blaze_chef"));
        blaze.setCustomNameVisible(true);
        blaze.addTag("culinary_cataclysm_chef");
        blaze.getPersistentData().putBoolean(CHEF_KEY, true);
        blaze.getPersistentData().putUUID("Competition", record.competitionId);
        if (level.addFreshEntity(blaze)) record.blazeChefId = blaze.getUUID();
    }

    public static void beginRound(CompetitionRecord record) {
        record.blazeWorkState = WorkState.FETCHING;
        record.blazeWorkTicks = 0;
        record.blazeIngredientCursor = 0;
        record.blazeRoundProvisioned = false;
        record.currentBlazeDishId = null;
        record.blazePlatedDishId = null;
        record.blazeRouteIndex = 0;
        record.blazeMoveStallTicks = 0;
        record.blazeCookingProgress = "";
        record.blazeCookingStallTicks = 0;
        record.blazeFailureReason = "";
    }

    public static void tick(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        if (record.blazeChefId == null || level.getEntity(record.blazeChefId) == null) {
            spawn(level, record);
            data.setDirty();
        }
        if (record.blazeChefId == null) return;
        Entity entity = level.getEntity(record.blazeChefId);
        if (!(entity instanceof Mob chef)) return;
        chef.setNoAi(true);
        chef.setNoGravity(true);
        chef.setInvulnerable(true);
        // 旧存档里已出餐却被等待超时误判为失败的比赛，可以继续提交已签发的菜。
        if (record.blazeWorkState == WorkState.FAILED && record.currentBlazeDishId != null
                && record.evidence(record.currentBlazeDishId).filter(evidence ->
                    evidence.producer == CookedDishEvidence.Producer.BLAZE_CHEF
                        && evidence.round == record.currentRound && !evidence.submitted).isPresent()) {
            transition(record, data, record.platedSubmission && record.blazePlatedDishId == null
                    ? WorkState.SUBMITTING : WorkState.FINISHED);
            LOGGER.info("恢复已完成出餐的烈焰人厨师：competition={}, round={}",
                    record.competitionId, record.currentRound + 1);
        }
        if (record.blazeWorkState == WorkState.WAITING || record.blazeWorkState == WorkState.FINISHED
                || record.blazeWorkState == WorkState.FAILED) {
            stopMoving(chef);
            return;
        }
        record.blazeWorkTicks++;
        CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFactsUnchecked(level.getServer(), record.currentRecipe());
        if (!facts.supported()) {
            fail(record, data, "菜谱不受支持");
            return;
        }
        if (record.blazeWorkState != WorkState.COOKING && record.blazeWorkTicks > STATE_TIMEOUT) {
            fail(record, data, "工序超时");
            return;
        }

        switch (record.blazeWorkState) {
            case FETCHING -> tickFetching(level, data, record, chef, facts);
            case PREPARING -> tickPreparing(level, data, record, chef, facts);
            case MOVING_TO_STATION -> tickMovingToStation(level, data, record, chef, facts);
            case COOKING -> tickCooking(level, data, record, chef, facts);
            case COLLECTING -> tickCollecting(level, data, record, chef, facts);
            case SUBMITTING -> tickSubmitting(level, data, record, chef, facts);
            default -> { }
        }
    }

    private static void tickFetching(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                     Mob chef, CookeryIntegration.RecipeFacts facts) {
        if (!followRoute(level, data, record, chef,
                List.of(flightPoint(record, record.blazePantryPos())), "取料")) return;
        if (!record.blazeRoundProvisioned) {
            // 每轮仅按真实配方配给一次，账本封闭且清理时不掉落，防止中断复制资源。
            for (var ingredient : facts.ingredients()) {
                ItemStack stack = CookeryIntegration.ingredientStack(ingredient);
                if (stack.isEmpty()) {
                    fail(record, data, "配方食材标签为空");
                    return;
                }
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                record.blazeInventory.merge(id, 1, Integer::sum);
            }
            record.blazeInventory.merge(BuiltInRegistries.ITEM.getKey(Items.BOWL), 1, Integer::sum);
            if (facts.station() == CookeryIntegration.StationType.POT) {
                record.blazeInventory.merge(CookeryIntegration.OIL, 1, Integer::sum);
            } else {
                record.blazeInventory.merge(BuiltInRegistries.ITEM.getKey(Items.WATER_BUCKET), 1, Integer::sum);
                record.blazeInventory.merge(CookeryIntegration.STOCKPOT_LID, 1, Integer::sum);
            }
            record.blazeRoundProvisioned = true;
        }
        transition(record, data, WorkState.PREPARING);
    }

    private static void tickPreparing(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                      Mob chef, CookeryIntegration.RecipeFacts facts) {
        stopMoving(chef);
        if (record.blazeWorkTicks < 20) return;
        for (var ingredient : facts.ingredients()) {
            ItemStack stack = CookeryIntegration.ingredientStack(ingredient);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (record.blazeInventory.getOrDefault(id, 0) <= 0) {
                fail(record, data, "NPC 原料账本不足: " + id);
                return;
            }
        }
        transition(record, data, WorkState.MOVING_TO_STATION);
    }

    private static void tickMovingToStation(ServerLevel level, CompetitionSavedData data, CompetitionRecord record, Mob chef,
                                            CookeryIntegration.RecipeFacts facts) {
        BlockPos station = CookeryIntegration.blazeStation(record, facts);
        BlockPos workPos = record.blazeCookingStandPos(station);
        if (followRoute(level, data, record, chef, routeToStation(record, station, workPos), "前往锅具")) {
            transition(record, data, WorkState.COOKING);
        }
    }

    private static void tickCooking(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                    Mob chef, CookeryIntegration.RecipeFacts facts) {
        BlockPos station = CookeryIntegration.blazeStation(record, facts);
        stopMoving(chef);
        lookAt(chef, station);
        if (facts.station() == CookeryIntegration.StationType.POT) {
            tickPot(level, data, record, chef, facts, station);
        } else {
            tickStockpot(level, data, record, chef, facts, station);
        }
        if (record.blazeWorkState == WorkState.COOKING) watchCookingProgress(level, data, record, station);
    }

    private static void tickPot(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                Mob chef, CookeryIntegration.RecipeFacts facts, BlockPos station) {
        if (!(level.getBlockEntity(station) instanceof PotBlockEntity pot) || !pot.hasHeatSource(level)) {
            fail(record, data, "炒锅缺失或无热源");
            return;
        }
        if (record.blazeIngredientCursor == 0) {
            ItemStack oil = takeLedgerItem(record, CookeryIntegration.OIL);
            if (oil.isEmpty() || !pot.onPlaceOil(level, chef, oil)) {
                fail(record, data, "炒锅无法放油");
                return;
            }
            record.blazeIngredientCursor++;
            data.setDirty();
            return;
        }
        int ingredientIndex = record.blazeIngredientCursor - 1;
        if (ingredientIndex < facts.ingredients().size()) {
            if (record.blazeWorkTicks % 10 != 0) return;
            ItemStack ingredient = takeIngredient(record, facts, ingredientIndex);
            if (ingredient.isEmpty() || !pot.addIngredient(level, chef, ingredient)) {
                fail(record, data, "炒锅投料失败");
                return;
            }
            record.blazeIngredientCursor++;
            chef.setItemInHand(InteractionHand.MAIN_HAND, CookeryIntegration.ingredientStack(facts.ingredients().get(ingredientIndex)));
            chef.swing(InteractionHand.MAIN_HAND, true);
            data.setDirty();
            return;
        }
        ItemStack shovel = CookeryIntegration.item(CookeryIntegration.KITCHEN_SHOVEL);
        chef.setItemInHand(InteractionHand.MAIN_HAND, shovel);
        if (pot.getStatus() == PotBlockEntity.PUT_INGREDIENT) {
            pot.onShovelHit(level, chef, shovel);
            chef.swing(InteractionHand.MAIN_HAND, true);
            data.setDirty();
            return;
        }
        if (pot.getStatus() == PotBlockEntity.COOKING && record.blazeWorkTicks % 20 == 0) {
            pot.onShovelHit(level, chef, shovel);
            chef.swing(InteractionHand.MAIN_HAND, true);
        }
        if (pot.getStatus() == PotBlockEntity.FINISHED) transition(record, data, WorkState.COLLECTING);
        if (pot.getStatus() == PotBlockEntity.BURNT) fail(record, data, "炒锅成品烧焦");
    }

    private static void tickStockpot(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                     Mob chef, CookeryIntegration.RecipeFacts facts, BlockPos station) {
        if (!(level.getBlockEntity(station) instanceof StockpotBlockEntity pot) || !pot.hasHeatSource(level)) {
            fail(record, data, "汤锅缺失或无热源");
            return;
        }
        if (record.blazeIngredientCursor == 0) {
            ItemStack water = takeLedgerItem(record, BuiltInRegistries.ITEM.getKey(Items.WATER_BUCKET));
            chef.setItemInHand(InteractionHand.MAIN_HAND, water);
            if (!pot.addSoupBase(level, chef, water)) {
                fail(record, data, "汤锅无法加入汤底");
                return;
            }
            record.blazeIngredientCursor++;
            data.setDirty();
            return;
        }
        int ingredientIndex = record.blazeIngredientCursor - 1;
        if (ingredientIndex < facts.ingredients().size()) {
            if (record.blazeWorkTicks % 10 != 0) return;
            ItemStack ingredient = takeIngredient(record, facts, ingredientIndex);
            if (ingredient.isEmpty() || !pot.addIngredient(level, chef, ingredient)) {
                fail(record, data, "汤锅投料失败");
                return;
            }
            chef.setItemInHand(InteractionHand.MAIN_HAND, CookeryIntegration.ingredientStack(facts.ingredients().get(ingredientIndex)));
            chef.swing(InteractionHand.MAIN_HAND, true);
            record.blazeIngredientCursor++;
            data.setDirty();
            return;
        }
        if (!pot.hasLid()) {
            ItemStack lid = takeLedgerItem(record, CookeryIntegration.STOCKPOT_LID);
            chef.setItemInHand(InteractionHand.MAIN_HAND, lid);
            if (!pot.onLitClick(level, chef, lid)) {
                fail(record, data, "汤锅无法盖盖");
                return;
            }
            data.setDirty();
            return;
        }
        if (pot.getStatus() == StockpotBlockEntity.FINISHED) transition(record, data, WorkState.COLLECTING);
    }

    private static ItemStack takeIngredient(CompetitionRecord record, CookeryIntegration.RecipeFacts facts, int index) {
        ItemStack stack = CookeryIntegration.ingredientStack(facts.ingredients().get(index));
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ItemStack ledger = takeLedgerItem(record, id);
        return ledger.isEmpty() ? ItemStack.EMPTY : stack;
    }

    private static ItemStack takeLedgerItem(CompetitionRecord record, ResourceLocation id) {
        int count = record.blazeInventory.getOrDefault(id, 0);
        if (count <= 0 || !BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
        if (count == 1) record.blazeInventory.remove(id);
        else record.blazeInventory.put(id, count - 1);
        return BuiltInRegistries.ITEM.get(id).getDefaultInstance();
    }

    private static void tickCollecting(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                       Mob chef, CookeryIntegration.RecipeFacts facts) {
        BlockPos station = CookeryIntegration.blazeStation(record, facts);
        stopMoving(chef);
        ItemStack result;
        ItemStack bowl = takeLedgerItem(record, BuiltInRegistries.ITEM.getKey(Items.BOWL));
        if (bowl.isEmpty()) {
            fail(record, data, "NPC 缺少出锅容器");
            return;
        }
        if (level.getBlockEntity(station) instanceof PotBlockEntity pot) {
            chef.setItemInHand(InteractionHand.MAIN_HAND, bowl);
            if (!pot.takeOutProduct(level, chef, chef.getMainHandItem())) {
                fail(record, data, "炒锅取出失败");
                return;
            }
            result = chef.getMainHandItem();
        } else if (level.getBlockEntity(station) instanceof StockpotBlockEntity pot) {
            if (pot.hasLid()) {
                chef.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                pot.onLitClick(level, chef, ItemStack.EMPTY);
            }
            chef.setItemInHand(InteractionHand.MAIN_HAND, bowl);
            if (!pot.takeOutProduct(level, chef, chef.getMainHandItem())) {
                fail(record, data, "汤锅取出失败");
                return;
            }
            result = chef.getMainHandItem();
        } else {
            fail(record, data, "取出时锅具不存在");
            return;
        }
        if (!result.is(facts.result().getItem())) {
            fail(record, data, "NPC 实际成品与指定菜谱不符");
            return;
        }
        UUID dishId = UUID.randomUUID();
        List<ResourceLocation> ingredients = new ArrayList<>();
        for (var ingredient : facts.ingredients()) {
            ItemStack stack = CookeryIntegration.ingredientStack(ingredient);
            ingredients.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        int operations = facts.ingredients().size() + (facts.station() == CookeryIntegration.StationType.POT ? 2 : 3);
        CookedDishEvidence evidence = new CookedDishEvidence(dishId, record.competitionId, record.currentRound,
                chef.getUUID(), record.currentRecipe(), BuiltInRegistries.ITEM.getKey(result.getItem()),
                station, level.getGameTime(), ingredients, CookedDishEvidence.Producer.BLAZE_CHEF,
                CookeryIntegration.quality(result), operations, operations,
                record.blazeWorkTicks, true, true, false);
        DishProvenance.mark(result, evidence);
        record.dishEvidence.add(evidence);
        record.currentBlazeDishId = dishId;
        record.blazeProducedCount++;
        transition(record, data, WorkState.SUBMITTING);
    }

    private static void tickSubmitting(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                       Mob chef, CookeryIntegration.RecipeFacts facts) {
        BlockPos station = CookeryIntegration.blazeStation(record, facts);
        if (followRoute(level, data, record, chef, routeToSubmit(record, station), "前往提交区")) {
            if (record.platedSubmission && record.blazePlatedDishId == null) {
                ItemStack dish = chef.getMainHandItem();
                if (DishProvenance.read(dish).filter(marker -> marker.dishId().equals(record.currentBlazeDishId)
                        && marker.competitionId().equals(record.competitionId)
                        && marker.round() == record.currentRound).isEmpty()
                        || !ServingTable.placeBlazeDish(level, record, chef, dish)) {
                    fail(record, data, "烈焰人摆盘失败：餐桌被占用或成品无效");
                    return;
                }
            }
            transition(record, data, WorkState.FINISHED);
        }
    }

    private static Vec3 flightPoint(CompetitionRecord record, BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, record.blazeChefStandPos().getY(), pos.getZ() + 0.5);
    }

    private static List<Vec3> routeToStation(CompetitionRecord record, BlockPos station, BlockPos workPos) {
        Vec3 work = flightPoint(record, workPos);
        if (record.battlefieldOrigin == null) return List.of(work);
        return List.of(aislePoint(record, station.getZ()), work);
    }

    private static List<Vec3> routeToSubmit(CompetitionRecord record, BlockPos station) {
        Vec3 submit = flightPoint(record, record.blazeSubmitPos());
        if (record.battlefieldOrigin == null) {
            // 旧双厨房的 x=0 是侧墙；从厨房北端绕出，不能直穿侧墙去旧提交点。
            double aisleX = record.blazeKitchenOrigin.getX() + 1.5;
            double flightY = record.blazeChefStandPos().getY();
            return List.of(new Vec3(aisleX, flightY, station.getZ() - 1.5),
                    new Vec3(aisleX, flightY, record.blazeKitchenOrigin.getZ() - 1.5), submit);
        }
        return List.of(aislePoint(record, station.getZ()),
                aislePoint(record, record.blazeSubmitPos().getZ()), submit);
    }

    /** 新场地的 x=16 是锅具外侧的空通道。 */
    private static Vec3 aislePoint(CompetitionRecord record, int z) {
        return new Vec3(record.battlefieldOrigin.getX() + 16.5,
                record.blazeChefStandPos().getY(), z + 0.5);
    }

    private static boolean followRoute(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                       Mob chef, List<Vec3> route, String stage) {
        double flightY = record.blazeChefStandPos().getY();
        // 从旧存档的低处储物点恢复时先垂直回到飞行高度，不斜穿柜台。
        if (Math.abs(chef.getY() - flightY) > 0.15) {
            moveStep(level, data, record, chef, new Vec3(chef.getX(), flightY, chef.getZ()), stage);
            return false;
        }
        while (record.blazeRouteIndex < route.size()
                && chef.position().distanceToSqr(route.get(record.blazeRouteIndex)) <= ARRIVAL_DISTANCE_SQR) {
            record.blazeRouteIndex++;
            record.blazeMoveStallTicks = 0;
            data.setDirty();
        }
        if (record.blazeRouteIndex >= route.size()) {
            stopMoving(chef);
            return true;
        }
        moveStep(level, data, record, chef, route.get(record.blazeRouteIndex), stage);
        return false;
    }

    private static void moveStep(ServerLevel level, CompetitionSavedData data, CompetitionRecord record,
                                 Mob chef, Vec3 target, String stage) {
        Vec3 difference = target.subtract(chef.position());
        Vec3 direction = Math.abs(difference.y) > 0.15
                ? new Vec3(0, difference.y, 0)
                : new Vec3(difference.x, 0, difference.z);
        if (direction.lengthSqr() < 1.0E-6) return;
        Vec3 step = direction.scale(Math.min(FLIGHT_SPEED / direction.length(), 1.0));
        double before = chef.position().distanceToSqr(target);
        Vec3 next = chef.position().add(step);
        if (level.hasChunkAt(BlockPos.containing(next))
                && level.noCollision(chef, chef.getBoundingBox().move(step))) {
            chef.move(MoverType.SELF, step);
        }
        chef.setDeltaMovement(Vec3.ZERO);
        chef.getLookControl().setLookAt(target.x, target.y + 0.5, target.z);
        if (before - chef.position().distanceToSqr(target) > 1.0E-4) {
            record.blazeMoveStallTicks = 0;
        } else if (++record.blazeMoveStallTicks >= MOVE_STALL_TIMEOUT) {
            fail(record, data, stage + "受阻，位置=" + chef.blockPosition().toShortString());
        }
    }

    private static void watchCookingProgress(ServerLevel level, CompetitionSavedData data,
                                             CompetitionRecord record, BlockPos station) {
        String progress;
        if (level.getBlockEntity(station) instanceof PotBlockEntity pot) {
            progress = "pot:" + record.blazeIngredientCursor + ':' + pot.getStatus() + ':'
                    + pot.getCurrentTick() + ':' + pot.getInputs().size();
        } else if (level.getBlockEntity(station) instanceof StockpotBlockEntity pot) {
            progress = "stockpot:" + record.blazeIngredientCursor + ':' + pot.getStatus() + ':'
                    + pot.saveWithoutMetadata(level.registryAccess()).getInt("CurrentTick") + ':'
                    + pot.getInputs().size() + ':' + pot.hasLid();
        } else {
            fail(record, data, "烹饪锅具不存在");
            return;
        }
        if (!progress.equals(record.blazeCookingProgress)) {
            record.blazeCookingProgress = progress;
            record.blazeCookingStallTicks = 0;
        } else {
            record.blazeCookingStallTicks++;
        }
        if (record.blazeCookingStallTicks >= COOKING_STALL_TIMEOUT
                || record.blazeWorkTicks >= COOKING_TIMEOUT) {
            fail(record, data, "烹饪无进展，锅具状态=" + progress);
        }
    }

    private static void transition(CompetitionRecord record, CompetitionSavedData data, WorkState state) {
        record.blazeWorkState = state;
        record.blazeWorkTicks = 0;
        record.blazeRouteIndex = 0;
        record.blazeMoveStallTicks = 0;
        record.blazeCookingProgress = "";
        record.blazeCookingStallTicks = 0;
        record.blazeFailureReason = "";
        data.setDirty();
    }

    private static void fail(CompetitionRecord record, CompetitionSavedData data, String reason) {
        record.blazeWorkState = WorkState.FAILED;
        record.blazeWorkTicks = 0;
        record.blazeFailureReason = reason;
        data.setDirty();
        LOGGER.error("烈焰人厨师失败：competition={}, round={}, reason={}",
                record.competitionId, record.currentRound + 1, reason);
    }

    private static void stopMoving(Mob chef) {
        chef.stopInPlace();
        chef.setDeltaMovement(0, 0, 0);
    }

    private static void lookAt(Mob chef, BlockPos target) {
        chef.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
    }

    public enum WorkState {
        WAITING,
        FETCHING,
        PREPARING,
        MOVING_TO_STATION,
        COOKING,
        COLLECTING,
        SUBMITTING,
        FINISHED,
        FAILED
    }
}
