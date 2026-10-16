package cn.sake_yez.culinarycataclysm.integration;

import cn.sake_yez.culinarycataclysm.CulinaryCataclysm;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public final class CookeryIntegration {
    public static final ResourceLocation IGNIS = id("cataclysm", "ignis");
    public static final ResourceLocation POT = id("kaleidoscope_cookery", "pot");
    public static final ResourceLocation STOCKPOT = id("kaleidoscope_cookery", "stockpot");
    public static final ResourceLocation STOVE = id("kaleidoscope_cookery", "stove");
    public static final ResourceLocation CHOPPING_BOARD = id("kaleidoscope_cookery", "chopping_board");
    public static final ResourceLocation OIL = id("kaleidoscope_cookery", "oil");
    public static final ResourceLocation KITCHEN_SHOVEL = id("kaleidoscope_cookery", "kitchen_shovel");
    public static final ResourceLocation STOCKPOT_LID = id("kaleidoscope_cookery", "stockpot_lid");
    private static final ResourceLocation ROUND_CONFIG = id(CulinaryCataclysm.MOD_ID, "culinary_competition/rounds.json");
    private static final String COMPETITION_TAG = "CulinaryCataclysmCompetition";
    private static final String OWNER_TAG = "CulinaryCataclysmOwner";
    private static final String PLAYER_STATION_TAG = "CulinaryCataclysmPlayerStation";

    private CookeryIntegration() {
    }

    public static List<ResourceLocation> resolveConfiguredRecipes(MinecraftServer server) throws IOException {
        List<ResourceLocation> result = new ArrayList<>();
        try (Reader reader = server.getResourceManager().openAsReader(ROUND_CONFIG)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (var element : json.getAsJsonArray("rounds")) {
                ResourceLocation recipeId = ResourceLocation.tryParse(element.getAsString());
                if (recipeId == null) throw new IOException("无效菜谱 ID: " + element);
                RecipeFacts facts = recipeFacts(server, recipeId);
                if (!facts.supported()) throw new IOException("菜谱不是受支持的森罗炒锅或汤锅配方: " + recipeId);
                if (facts.result().isEmpty()) throw new IOException("菜谱没有可提交成品: " + recipeId);
                if (facts.ingredients().isEmpty()) throw new IOException("菜谱没有可验证食材: " + recipeId);
                if (!ServingTable.isPlaceableDish(facts.result())) {
                    throw new IOException("菜谱成品不是可单格立体摆盘的森罗食物: " + recipeId);
                }
                result.add(recipeId);
            }
        }
        if (result.isEmpty()) throw new IOException("比赛轮次配置为空");
        return List.copyOf(result);
    }

    public static RecipeFacts recipeFacts(MinecraftServer server, ResourceLocation recipeId) throws IOException {
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId)
                .orElseThrow(() -> new IOException("当前森罗版本不存在菜谱: " + recipeId));
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
        StationType station = stationForSerializer(serializerId);
        List<Ingredient> ingredients = holder.value().getIngredients().stream()
                .filter(ingredient -> !ingredient.isEmpty()).toList();
        return new RecipeFacts(recipeId, serializerId, station,
                holder.value().getResultItem(server.registryAccess()).copy(), ingredients);
    }

    public static RecipeFacts recipeFactsUnchecked(MinecraftServer server, ResourceLocation recipeId) {
        try {
            return recipeFacts(server, recipeId);
        } catch (IOException exception) {
            return RecipeFacts.invalid(recipeId);
        }
    }

    public static ItemStack expectedDish(MinecraftServer server, ResourceLocation recipeId) {
        return recipeFactsUnchecked(server, recipeId).result().copy();
    }

    public static BlockPos playerStation(CompetitionRecord record, RecipeFacts facts) {
        return facts.station() == StationType.STOCKPOT ? record.playerStockpotPos() : record.playerPotPos();
    }

    public static BlockPos blazeStation(CompetitionRecord record, RecipeFacts facts) {
        return facts.station() == StationType.STOCKPOT ? record.blazeStockpotPos() : record.blazePotPos();
    }

    /** 给双厨房的每口炒锅和汤锅写入持久比赛归属。 */
    public static void markCompetitionStations(ServerLevel level, CompetitionRecord record) {
        markStation(level, record.playerPotPos(), record, true);
        markStation(level, record.playerSecondPotPos(), record, true);
        markStation(level, record.playerStockpotPos(), record, true);
        markStation(level, record.blazePotPos(), record, false);
        markStation(level, record.blazeSecondPotPos(), record, false);
        markStation(level, record.blazeStockpotPos(), record, false);
    }

    private static void markStation(ServerLevel level, BlockPos pos, CompetitionRecord record, boolean playerStation) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof PotBlockEntity) && !(blockEntity instanceof StockpotBlockEntity)) return;
        CompoundTag tag = blockEntity.getPersistentData();
        tag.putUUID(COMPETITION_TAG, record.competitionId);
        tag.putUUID(OWNER_TAG, record.playerId);
        tag.putBoolean(PLAYER_STATION_TAG, playerStation);
        blockEntity.setChanged();
    }

    public static boolean isTaggedPlayerStation(ServerLevel level, BlockPos pos, CompetitionRecord record) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof PotBlockEntity) && !(blockEntity instanceof StockpotBlockEntity)) return false;
        CompoundTag tag = blockEntity.getPersistentData();
        return tag.getBoolean(PLAYER_STATION_TAG)
                && tag.hasUUID(COMPETITION_TAG) && record.competitionId.equals(tag.getUUID(COMPETITION_TAG))
                && tag.hasUUID(OWNER_TAG) && record.playerId.equals(tag.getUUID(OWNER_TAG));
    }

    public static StationSnapshot inspectStation(ServerLevel level, BlockPos pos, RecipeFacts expected) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof PotBlockEntity pot) {
            boolean heated = pot.hasHeatSource(level);
            CompoundTag saved = pot.saveWithoutMetadata(level.registryAccess());
            boolean exactRecipeMatches = level.getRecipeManager().byKey(expected.recipeId())
                    .map(holder -> holder.value() instanceof PotRecipe recipe
                            ? recipe.matches(pot.getContainer(), level)
                            : holder.value() instanceof FlexPotRecipe flexRecipe
                            && flexRecipe.matches(pot.getContainer(), level))
                    .orElse(false);
            boolean recipeMatches = expected.station() == StationType.POT
                    && exactRecipeMatches && expected.result().is(pot.getResult().getItem())
                    && QualityUtils.hasQuality(pot.getResult());
            List<ResourceLocation> inputs = itemIds(pot.getInputs());
            return new StationSnapshot(true, StationType.POT, heated, pot.getStatus(), pot.getCurrentTick(),
                    saved.getInt("StirFryCount"), pot.getResult().copy(), inputs,
                    recipeMatches, pot.getStatus() == IPot.FINISHED);
        }
        if (blockEntity instanceof StockpotBlockEntity pot) {
            boolean heated = pot.hasHeatSource(level);
            CompoundTag saved = pot.saveWithoutMetadata(level.registryAccess());
            ResourceLocation actualRecipe = ResourceLocation.tryParse(saved.getString("RecipeId"));
            boolean recipeMatches = expected.station() == StationType.STOCKPOT
                    && expected.recipeId().equals(actualRecipe)
                    && expected.result().is(pot.getResult().getItem())
                    && QualityUtils.hasQuality(pot.getResult());
            return new StationSnapshot(true, StationType.STOCKPOT, heated, pot.getStatus(), -1, -1,
                    pot.getResult().copy(), itemIds(pot.getInputs()), recipeMatches,
                    pot.getStatus() == IStockpot.FINISHED);
        }
        return StationSnapshot.missing(expected.station());
    }

    public static String quality(ItemStack stack) {
        return QualityUtils.getQuality(stack).getSerializedName();
    }

    public static ItemStack ingredientStack(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    public static boolean requiredRegistriesPresent() {
        return ModList.get().isLoaded("cataclysm")
                && ModList.get().isLoaded("create")
                && ModList.get().isLoaded("kaleidoscope_cookery")
                && BuiltInRegistries.ENTITY_TYPE.containsKey(IGNIS)
                && BuiltInRegistries.BLOCK.containsKey(POT)
                && BuiltInRegistries.BLOCK.containsKey(STOCKPOT)
                && BuiltInRegistries.BLOCK.containsKey(STOVE)
                && BuiltInRegistries.BLOCK.containsKey(CHOPPING_BOARD)
                && BuiltInRegistries.ITEM.containsKey(OIL)
                && BuiltInRegistries.ITEM.containsKey(KITCHEN_SHOVEL)
                && BuiltInRegistries.ITEM.containsKey(STOCKPOT_LID);
    }

    public static ItemStack item(ResourceLocation id) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
        return BuiltInRegistries.ITEM.get(id).getDefaultInstance();
    }

    private static List<ResourceLocation> itemIds(List<ItemStack> stacks) {
        List<ResourceLocation> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) result.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        return List.copyOf(result);
    }

    private static StationType stationForSerializer(ResourceLocation serializerId) {
        if (serializerId == null || !"kaleidoscope_cookery".equals(serializerId.getNamespace())) return StationType.UNSUPPORTED;
        return switch (serializerId.getPath()) {
            case "pot", "flex_pot" -> StationType.POT;
            case "stockpot", "flex_stockpot" -> StationType.STOCKPOT;
            default -> StationType.UNSUPPORTED;
        };
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public enum StationType {
        POT,
        STOCKPOT,
        UNSUPPORTED
    }

    public record RecipeFacts(ResourceLocation recipeId, ResourceLocation serializerId, StationType station,
                              ItemStack result, List<Ingredient> ingredients) {
        public RecipeFacts {
            result = result.copy();
            ingredients = List.copyOf(ingredients);
        }

        public boolean supported() {
            return station != StationType.UNSUPPORTED;
        }

        static RecipeFacts invalid(ResourceLocation id) {
            return new RecipeFacts(id, ResourceLocation.withDefaultNamespace("empty"),
                    StationType.UNSUPPORTED, ItemStack.EMPTY, List.of());
        }
    }

    public record StationSnapshot(boolean blockEntityPresent, StationType station, boolean heated, int status,
                                  int remainingTicks, int stirFryCount, ItemStack result,
                                  List<ResourceLocation> ingredients, boolean recipeMatches,
                                  boolean readyToExtract) {
        public StationSnapshot {
            result = result.copy();
            ingredients = List.copyOf(ingredients);
        }

        static StationSnapshot missing(StationType station) {
            return new StationSnapshot(false, station, false, -1, -1, -1,
                    ItemStack.EMPTY, List.of(), false, false);
        }
    }
}
