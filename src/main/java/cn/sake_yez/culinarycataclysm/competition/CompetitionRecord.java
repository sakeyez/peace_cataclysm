package cn.sake_yez.culinarycataclysm.competition;

import cn.sake_yez.culinarycataclysm.npc.BlazeChefController;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

public final class CompetitionRecord {
    public final UUID competitionId;
    public final UUID playerId;
    public final UUID ignisId;
    public UUID blazeChefId;
    public final ResourceLocation dimension;
    public final BlockPos arenaCenter;
    public final BlockPos playerKitchenOrigin;
    public final BlockPos blazeKitchenOrigin;
    /** 新场地的西北角；旧比赛没有该字段，继续使用双厨房模板。 */
    public BlockPos battlefieldOrigin;
    /** 与新场地重叠的原生祭坛基座方块；建造和清理都不能改动。 */
    public final Set<Long> preservedBattlefieldBlocks = new HashSet<>();
    /** 降低一格时被模板地板覆盖的原始方块状态，赛后逐格还原。 */
    public final Map<Long, CompoundTag> originalBattlefieldFloor = new LinkedHashMap<>();
    /** 新比赛采用双侧并行落块；旧比赛沿用原方块序列以保护 buildCursor。 */
    public boolean twoSidedBuild;
    /** 上一版比赛的逐层镜像螺旋顺序，供已开工存档继续使用。 */
    public boolean spiralBuild;
    /** 新比赛逐层按行镜像施工；与螺旋顺序分开存储以保护旧 buildCursor。 */
    public boolean mirroredBuild;
    /** 新场地直接使用大厅原地板，模板的 y=0 层不施工。 */
    public boolean aboveGroundOnly;
    /** 新比赛在真实食物方块上摆盘；旧存档继续使用原提交方式。 */
    public boolean platedSubmission;
    public boolean servingTableBuilt;
    public UUID playerPlatedDishId;
    public UUID blazePlatedDishId;
    /** 双方菜品均经验证后，最早可以收菜评分的游戏刻；0 表示尚未开始展示。 */
    public long platedJudgingAtGameTime;
    public CompetitionState state = CompetitionState.SUMMONED;
    public CompetitionState interruptedState = CompetitionState.IDLE;
    public int stateTicks;
    public int currentRound;
    public int buildCursor;
    /** 清理的目标数量；-1 兼容旧存档的完整场地清理。 */
    public int cleanupTargetCount = -1;
    public int playerTotal;
    public int blazeTotal;
    public boolean rewardIssued;
    public boolean finalResultResolved;
    public boolean kitchensBuilt;
    /** 旧存档字段，仅保留统计兼容；不再作为生产或评分证明。 */
    public int stationInteractions;
    public int playerVerifiedOperations;
    public int playerCookingStartedTick;
    public int playerProducedCount;
    public int playerSubmittedCount;
    public int blazeProducedCount;
    public int blazeSubmittedCount;
    public UUID currentPlayerDishId;
    public UUID currentBlazeDishId;
    public BlazeChefController.WorkState blazeWorkState = BlazeChefController.WorkState.WAITING;
    public int blazeWorkTicks;
    public int blazeIngredientCursor;
    public boolean blazeRoundProvisioned;
    /** 当前工序的航点及移动/烹饪进度，保存以便重载后继续，而不是重放投料。 */
    public int blazeRouteIndex;
    public int blazeMoveStallTicks;
    public String blazeCookingProgress = "";
    public int blazeCookingStallTicks;
    public String blazeFailureReason = "";
    public final List<ResourceLocation> recipes;
    public final List<Integer> playerScores = new ArrayList<>();
    public final List<Integer> blazeScores = new ArrayList<>();
    public final List<CookedDishEvidence> dishEvidence = new ArrayList<>();
    public final List<ResourceLocation> playerRoundIngredients = new ArrayList<>();
    /** 比赛管理的 NPC 封闭原料账本；玩家无法访问，扣除后不会因中断返还。 */
    public final Map<ResourceLocation, Integer> blazeInventory = new LinkedHashMap<>();

    public CompetitionRecord(UUID competitionId, UUID playerId, UUID ignisId, ResourceLocation dimension,
                             BlockPos arenaCenter, BlockPos playerKitchenOrigin, BlockPos blazeKitchenOrigin,
                             List<ResourceLocation> recipes) {
        this.competitionId = competitionId;
        this.playerId = playerId;
        this.ignisId = ignisId;
        this.dimension = dimension;
        this.arenaCenter = arenaCenter.immutable();
        this.playerKitchenOrigin = playerKitchenOrigin.immutable();
        this.blazeKitchenOrigin = blazeKitchenOrigin.immutable();
        this.recipes = List.copyOf(recipes);
    }

    public ResourceLocation currentRecipe() {
        return recipes.get(Math.min(currentRound, recipes.size() - 1));
    }

    public Optional<CookedDishEvidence> evidence(UUID dishId) {
        if (dishId == null) return Optional.empty();
        return dishEvidence.stream().filter(evidence -> evidence.dishId.equals(dishId)).findFirst();
    }

    public boolean containsArena(BlockPos pos) {
        if (battlefieldOrigin != null) {
            return inside(battlefieldOrigin, pos, 0, 20, 0, 6, 0, 20)
                    || inside(arenaCenter, pos, -2, 2, -3, 5, -2, 2);
        }
        int minX = Math.min(playerKitchenOrigin.getX(), blazeKitchenOrigin.getX()) - 2;
        int maxX = Math.max(playerKitchenOrigin.getX(), blazeKitchenOrigin.getX()) + 10;
        int minZ = Math.min(playerKitchenOrigin.getZ(), blazeKitchenOrigin.getZ()) - 2;
        int maxZ = Math.max(playerKitchenOrigin.getZ(), blazeKitchenOrigin.getZ()) + 10;
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getZ() >= minZ && pos.getZ() <= maxZ
                && pos.getY() >= arenaCenter.getY() - 2 && pos.getY() <= arenaCenter.getY() + 8;
    }

    public boolean isPlayerKitchen(BlockPos pos) {
        if (battlefieldOrigin != null) return inside(battlefieldOrigin, pos, 0, 9, 0, 6, 0, 20);
        return containsKitchen(playerKitchenOrigin, pos);
    }

    public boolean isBlazeKitchen(BlockPos pos) {
        if (battlefieldOrigin != null) return inside(battlefieldOrigin, pos, 11, 20, 0, 6, 0, 20);
        return containsKitchen(blazeKitchenOrigin, pos);
    }

    private static boolean containsKitchen(BlockPos origin, BlockPos pos) {
        return pos.getX() >= origin.getX() && pos.getX() <= origin.getX() + 8
                && pos.getZ() >= origin.getZ() && pos.getZ() <= origin.getZ() + 8
                && pos.getY() >= origin.getY() && pos.getY() <= origin.getY() + 7;
    }

    public boolean isKitchenStation(BlockPos pos) {
        if (battlefieldOrigin != null) return isPlayerStation(pos) || pos.equals(blazePotPos())
                || pos.equals(blazeStockpotPos()) || pos.equals(battlefieldOrigin.offset(13, 4, 9));
        return isStationAt(playerKitchenOrigin, pos) || isStationAt(blazeKitchenOrigin, pos);
    }

    public boolean isPlayerStation(BlockPos pos) {
        if (battlefieldOrigin != null) return pos.equals(playerPotPos()) || pos.equals(playerStockpotPos())
                || pos.equals(playerSecondPotPos())
                || pos.equals(battlefieldOrigin.offset(7, 4, 9));
        return isStationAt(playerKitchenOrigin, pos);
    }

    public BlockPos playerPotPos() {
        if (battlefieldOrigin != null) return battlefieldOrigin.offset(7, 4, 6);
        return playerKitchenOrigin.offset(2, 2, 6);
    }

    public BlockPos playerStockpotPos() {
        if (battlefieldOrigin != null) return battlefieldOrigin.offset(7, 4, 5);
        return playerKitchenOrigin.offset(6, 2, 6);
    }

    public BlockPos playerSecondPotPos() {
        return battlefieldOrigin != null ? battlefieldOrigin.offset(7, 4, 7) : playerPotPos();
    }

    public BlockPos blazePotPos() {
        if (battlefieldOrigin != null) return battlefieldOrigin.offset(13, 4, 6);
        return blazeKitchenOrigin.offset(2, 2, 6);
    }

    public BlockPos blazeStockpotPos() {
        if (battlefieldOrigin != null) return battlefieldOrigin.offset(13, 4, 5);
        return blazeKitchenOrigin.offset(6, 2, 6);
    }

    public BlockPos blazeSecondPotPos() {
        return battlefieldOrigin != null ? battlefieldOrigin.offset(13, 4, 7) : blazePotPos();
    }

    public BlockPos blazeChefStandPos() {
        return battlefieldOrigin != null ? battlefieldOrigin.offset(14, 4, 9) : blazeKitchenOrigin.offset(4, 2, 3);
    }

    public BlockPos blazePantryPos() {
        return battlefieldOrigin != null ? battlefieldOrigin.offset(16, 3, 12) : blazeKitchenOrigin.offset(7, 2, 2);
    }

    public BlockPos blazeCookingStandPos(BlockPos station) {
        return battlefieldOrigin != null ? station.offset(2, 0, 0) : station.offset(0, 0, -2);
    }

    public BlockPos blazeSubmitPos() {
        return battlefieldOrigin != null
                ? battlefieldOrigin.offset(platedSubmission ? 16 : 14, 4, platedSubmission ? 17 : 12)
                : arenaCenter.offset(4, 0, 2);
    }

    public BlockPos playerServingBase() {
        return battlefieldOrigin.offset(3, 3, 17);
    }

    public BlockPos blazeServingBase() {
        return battlefieldOrigin.offset(17, 3, 17);
    }

    private static boolean inside(BlockPos origin, BlockPos pos, int minX, int maxX,
                                  int minY, int maxY, int minZ, int maxZ) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    private static boolean isStationAt(BlockPos origin, BlockPos pos) {
        return pos.equals(origin.offset(2, 2, 6))
                || pos.equals(origin.offset(6, 2, 6))
                || pos.equals(origin.offset(4, 2, 6));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("CompetitionId", competitionId);
        tag.putUUID("PlayerId", playerId);
        tag.putUUID("IgnisId", ignisId);
        if (blazeChefId != null) tag.putUUID("BlazeChefId", blazeChefId);
        tag.putString("Dimension", dimension.toString());
        putPos(tag, "Arena", arenaCenter);
        putPos(tag, "PlayerKitchen", playerKitchenOrigin);
        putPos(tag, "BlazeKitchen", blazeKitchenOrigin);
        if (battlefieldOrigin != null) putPos(tag, "Battlefield", battlefieldOrigin);
        tag.putLongArray("PreservedBattlefieldBlocks", preservedBattlefieldBlocks.stream().mapToLong(Long::longValue).toArray());
        ListTag floorTags = new ListTag();
        originalBattlefieldFloor.forEach((pos, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos);
            entry.put("State", state.copy());
            floorTags.add(entry);
        });
        tag.put("OriginalBattlefieldFloor", floorTags);
        tag.putBoolean("TwoSidedBuild", twoSidedBuild);
        tag.putBoolean("SpiralBuild", spiralBuild);
        tag.putBoolean("MirroredBuild", mirroredBuild);
        tag.putBoolean("AboveGroundOnly", aboveGroundOnly);
        tag.putBoolean("PlatedSubmission", platedSubmission);
        tag.putBoolean("ServingTableBuilt", servingTableBuilt);
        if (playerPlatedDishId != null) tag.putUUID("PlayerPlatedDishId", playerPlatedDishId);
        if (blazePlatedDishId != null) tag.putUUID("BlazePlatedDishId", blazePlatedDishId);
        tag.putLong("PlatedJudgingAtGameTime", platedJudgingAtGameTime);
        tag.putString("State", state.name());
        tag.putString("InterruptedState", interruptedState.name());
        tag.putInt("StateTicks", stateTicks);
        tag.putInt("CurrentRound", currentRound);
        tag.putInt("BuildCursor", buildCursor);
        tag.putInt("CleanupTargetCount", cleanupTargetCount);
        tag.putInt("PlayerTotal", playerTotal);
        tag.putInt("BlazeTotal", blazeTotal);
        tag.putBoolean("RewardIssued", rewardIssued);
        tag.putBoolean("FinalResultResolved", finalResultResolved);
        tag.putBoolean("KitchensBuilt", kitchensBuilt);
        tag.putInt("StationInteractions", stationInteractions);
        tag.putInt("PlayerVerifiedOperations", playerVerifiedOperations);
        tag.putInt("PlayerCookingStartedTick", playerCookingStartedTick);
        tag.putInt("PlayerProducedCount", playerProducedCount);
        tag.putInt("PlayerSubmittedCount", playerSubmittedCount);
        tag.putInt("BlazeProducedCount", blazeProducedCount);
        tag.putInt("BlazeSubmittedCount", blazeSubmittedCount);
        if (currentPlayerDishId != null) tag.putUUID("CurrentPlayerDishId", currentPlayerDishId);
        if (currentBlazeDishId != null) tag.putUUID("CurrentBlazeDishId", currentBlazeDishId);
        tag.putString("BlazeWorkState", blazeWorkState.name());
        tag.putInt("BlazeWorkTicks", blazeWorkTicks);
        tag.putInt("BlazeIngredientCursor", blazeIngredientCursor);
        tag.putBoolean("BlazeRoundProvisioned", blazeRoundProvisioned);
        tag.putInt("BlazeRouteIndex", blazeRouteIndex);
        tag.putInt("BlazeMoveStallTicks", blazeMoveStallTicks);
        tag.putString("BlazeCookingProgress", blazeCookingProgress);
        tag.putInt("BlazeCookingStallTicks", blazeCookingStallTicks);
        tag.putString("BlazeFailureReason", blazeFailureReason);

        ListTag recipeTags = new ListTag();
        for (ResourceLocation id : recipes) {
            CompoundTag recipeTag = new CompoundTag();
            recipeTag.putString("Id", id.toString());
            recipeTags.add(recipeTag);
        }
        tag.put("Recipes", recipeTags);
        tag.putIntArray("PlayerScores", playerScores);
        tag.putIntArray("BlazeScores", blazeScores);

        ListTag evidenceTags = new ListTag();
        for (CookedDishEvidence evidence : dishEvidence) evidenceTags.add(evidence.save());
        tag.put("DishEvidence", evidenceTags);
        ListTag playerIngredientTags = new ListTag();
        for (ResourceLocation id : playerRoundIngredients) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", id.toString());
            playerIngredientTags.add(entry);
        }
        tag.put("PlayerRoundIngredients", playerIngredientTags);

        ListTag inventoryTags = new ListTag();
        blazeInventory.forEach((id, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", id.toString());
            entry.putInt("Count", count);
            inventoryTags.add(entry);
        });
        tag.put("BlazeInventory", inventoryTags);
        return tag;
    }

    public static CompetitionRecord load(CompoundTag tag) {
        List<ResourceLocation> recipes = new ArrayList<>();
        ListTag recipeTags = tag.getList("Recipes", Tag.TAG_COMPOUND);
        for (int i = 0; i < recipeTags.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(recipeTags.getCompound(i).getString("Id"));
            if (id != null) recipes.add(migrateRecipeId(id));
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        CompetitionRecord record = new CompetitionRecord(
                tag.getUUID("CompetitionId"), tag.getUUID("PlayerId"), tag.getUUID("IgnisId"),
                dimension == null ? ResourceLocation.withDefaultNamespace("overworld") : dimension,
                getPos(tag, "Arena"), getPos(tag, "PlayerKitchen"), getPos(tag, "BlazeKitchen"), recipes);
        if (tag.contains("Battlefield", Tag.TAG_COMPOUND)) record.battlefieldOrigin = getPos(tag, "Battlefield");
        for (long pos : tag.getLongArray("PreservedBattlefieldBlocks")) record.preservedBattlefieldBlocks.add(pos);
        ListTag floorTags = tag.getList("OriginalBattlefieldFloor", Tag.TAG_COMPOUND);
        for (int i = 0; i < floorTags.size(); i++) {
            CompoundTag entry = floorTags.getCompound(i);
            if (entry.contains("State", Tag.TAG_COMPOUND)) {
                record.originalBattlefieldFloor.put(entry.getLong("Pos"), entry.getCompound("State").copy());
            }
        }
        record.twoSidedBuild = tag.getBoolean("TwoSidedBuild");
        record.spiralBuild = tag.getBoolean("SpiralBuild");
        record.mirroredBuild = tag.getBoolean("MirroredBuild");
        record.aboveGroundOnly = tag.getBoolean("AboveGroundOnly");
        record.platedSubmission = tag.getBoolean("PlatedSubmission");
        record.servingTableBuilt = tag.getBoolean("ServingTableBuilt");
        if (tag.hasUUID("PlayerPlatedDishId")) record.playerPlatedDishId = tag.getUUID("PlayerPlatedDishId");
        if (tag.hasUUID("BlazePlatedDishId")) record.blazePlatedDishId = tag.getUUID("BlazePlatedDishId");
        record.platedJudgingAtGameTime = tag.getLong("PlatedJudgingAtGameTime");
        if (tag.hasUUID("BlazeChefId")) record.blazeChefId = tag.getUUID("BlazeChefId");
        try {
            record.state = CompetitionState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            record.state = CompetitionState.INTERRUPTED;
        }
        try {
            record.interruptedState = CompetitionState.valueOf(tag.getString("InterruptedState"));
        } catch (IllegalArgumentException ignored) {
            record.interruptedState = CompetitionState.IDLE;
        }
        record.stateTicks = tag.getInt("StateTicks");
        record.currentRound = tag.getInt("CurrentRound");
        record.buildCursor = tag.getInt("BuildCursor");
        if (tag.contains("CleanupTargetCount", Tag.TAG_INT)) {
            record.cleanupTargetCount = tag.getInt("CleanupTargetCount");
        }
        record.playerTotal = tag.getInt("PlayerTotal");
        record.blazeTotal = tag.getInt("BlazeTotal");
        record.rewardIssued = tag.getBoolean("RewardIssued");
        record.finalResultResolved = tag.getBoolean("FinalResultResolved");
        record.kitchensBuilt = tag.getBoolean("KitchensBuilt");
        record.stationInteractions = tag.getInt("StationInteractions");
        record.playerVerifiedOperations = tag.getInt("PlayerVerifiedOperations");
        record.playerCookingStartedTick = tag.getInt("PlayerCookingStartedTick");
        record.playerProducedCount = tag.getInt("PlayerProducedCount");
        record.playerSubmittedCount = tag.getInt("PlayerSubmittedCount");
        record.blazeProducedCount = tag.getInt("BlazeProducedCount");
        record.blazeSubmittedCount = tag.getInt("BlazeSubmittedCount");
        if (tag.hasUUID("CurrentPlayerDishId")) record.currentPlayerDishId = tag.getUUID("CurrentPlayerDishId");
        if (tag.hasUUID("CurrentBlazeDishId")) record.currentBlazeDishId = tag.getUUID("CurrentBlazeDishId");
        try {
            record.blazeWorkState = BlazeChefController.WorkState.valueOf(tag.getString("BlazeWorkState"));
        } catch (IllegalArgumentException ignored) {
            record.blazeWorkState = BlazeChefController.WorkState.WAITING;
        }
        record.blazeWorkTicks = tag.getInt("BlazeWorkTicks");
        record.blazeIngredientCursor = tag.getInt("BlazeIngredientCursor");
        record.blazeRoundProvisioned = tag.getBoolean("BlazeRoundProvisioned");
        record.blazeRouteIndex = tag.getInt("BlazeRouteIndex");
        record.blazeMoveStallTicks = tag.getInt("BlazeMoveStallTicks");
        record.blazeCookingProgress = tag.getString("BlazeCookingProgress");
        record.blazeCookingStallTicks = tag.getInt("BlazeCookingStallTicks");
        record.blazeFailureReason = tag.getString("BlazeFailureReason");
        for (int score : tag.getIntArray("PlayerScores")) record.playerScores.add(score);
        for (int score : tag.getIntArray("BlazeScores")) record.blazeScores.add(score);

        ListTag evidenceTags = tag.getList("DishEvidence", Tag.TAG_COMPOUND);
        for (int i = 0; i < evidenceTags.size(); i++) {
            record.dishEvidence.add(CookedDishEvidence.load(evidenceTags.getCompound(i)));
        }
        ListTag playerIngredientTags = tag.getList("PlayerRoundIngredients", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerIngredientTags.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(playerIngredientTags.getCompound(i).getString("Id"));
            if (id != null) record.playerRoundIngredients.add(id);
        }
        ListTag inventoryTags = tag.getList("BlazeInventory", Tag.TAG_COMPOUND);
        for (int i = 0; i < inventoryTags.size(); i++) {
            CompoundTag entry = inventoryTags.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("Id"));
            if (id != null && entry.getInt("Count") > 0) record.blazeInventory.put(id, entry.getInt("Count"));
        }
        return record;
    }

    private static ResourceLocation migrateRecipeId(ResourceLocation id) {
        String path = id.getPath();
        if (path.equals("stockpot/tomato_beef_brisket_soup")) {
            return ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_stockpot/borscht");
        }
        if (path.startsWith("pot/")) {
            return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "flex_pot/" + path.substring("pot/".length()));
        }
        if (path.startsWith("stockpot/")) {
            return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "flex_stockpot/" + path.substring("stockpot/".length()));
        }
        return id;
    }

    static void putPos(CompoundTag tag, String key, BlockPos pos) {
        CompoundTag value = new CompoundTag();
        value.putInt("X", pos.getX());
        value.putInt("Y", pos.getY());
        value.putInt("Z", pos.getZ());
        tag.put(key, value);
    }

    static BlockPos getPos(CompoundTag tag, String key) {
        CompoundTag value = tag.getCompound(key);
        return new BlockPos(value.getInt("X"), value.getInt("Y"), value.getInt("Z"));
    }
}
