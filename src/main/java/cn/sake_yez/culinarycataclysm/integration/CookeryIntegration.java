package cn.sake_yez.culinarycataclysm.integration;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CookeryIntegration {
    public static final ResourceLocation POT = id("kaleidoscope_cookery", "pot");
    public static final ResourceLocation STOCKPOT = id("kaleidoscope_cookery", "stockpot");
    public static final ResourceLocation STOVE = id("kaleidoscope_cookery", "stove");
    public static final ResourceLocation CHOPPING_BOARD = id("kaleidoscope_cookery", "chopping_board");
    public static final ResourceLocation OIL = id("kaleidoscope_cookery", "oil");
    public static final ResourceLocation KITCHEN_SHOVEL = id("kaleidoscope_cookery", "kitchen_shovel");
    public static final ResourceLocation STOCKPOT_LID = id("kaleidoscope_cookery", "stockpot_lid");
    private CookeryIntegration() {
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
