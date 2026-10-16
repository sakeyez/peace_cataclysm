package cn.sake_yez.culinarycataclysm.competition;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 服务端权威的单份比赛菜品生产证明。物品上的 UUID 只是索引，不能替代此记录。 */
public final class CookedDishEvidence {
    public final UUID dishId;
    public final UUID competitionId;
    public final int round;
    public final UUID authorId;
    public final ResourceLocation recipeId;
    public final ResourceLocation resultItemId;
    public final BlockPos station;
    public final long producedGameTime;
    public final List<ResourceLocation> consumedIngredients;
    public final Producer producer;
    public final String cookeryQuality;
    public final int requiredOperations;
    public final int verifiedOperations;
    public final int elapsedTicks;
    public final boolean heated;
    public final boolean realCookeryStation;
    public boolean submitted;

    public CookedDishEvidence(UUID dishId, UUID competitionId, int round, UUID authorId,
                              ResourceLocation recipeId, ResourceLocation resultItemId, BlockPos station,
                              long producedGameTime, List<ResourceLocation> consumedIngredients,
                              Producer producer, String cookeryQuality, int requiredOperations,
                              int verifiedOperations, int elapsedTicks, boolean heated,
                              boolean realCookeryStation, boolean submitted) {
        this.dishId = dishId;
        this.competitionId = competitionId;
        this.round = round;
        this.authorId = authorId;
        this.recipeId = recipeId;
        this.resultItemId = resultItemId;
        this.station = station.immutable();
        this.producedGameTime = producedGameTime;
        this.consumedIngredients = List.copyOf(consumedIngredients);
        this.producer = producer;
        this.cookeryQuality = cookeryQuality;
        this.requiredOperations = requiredOperations;
        this.verifiedOperations = verifiedOperations;
        this.elapsedTicks = elapsedTicks;
        this.heated = heated;
        this.realCookeryStation = realCookeryStation;
        this.submitted = submitted;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("DishId", dishId);
        tag.putUUID("CompetitionId", competitionId);
        tag.putInt("Round", round);
        tag.putUUID("AuthorId", authorId);
        tag.putString("RecipeId", recipeId.toString());
        tag.putString("ResultItemId", resultItemId.toString());
        CompetitionRecord.putPos(tag, "Station", station);
        tag.putLong("ProducedGameTime", producedGameTime);
        ListTag ingredients = new ListTag();
        for (ResourceLocation id : consumedIngredients) {
            CompoundTag ingredient = new CompoundTag();
            ingredient.putString("Id", id.toString());
            ingredients.add(ingredient);
        }
        tag.put("ConsumedIngredients", ingredients);
        tag.putString("Producer", producer.name());
        tag.putString("CookeryQuality", cookeryQuality);
        tag.putInt("RequiredOperations", requiredOperations);
        tag.putInt("VerifiedOperations", verifiedOperations);
        tag.putInt("ElapsedTicks", elapsedTicks);
        tag.putBoolean("Heated", heated);
        tag.putBoolean("RealCookeryStation", realCookeryStation);
        tag.putBoolean("Submitted", submitted);
        return tag;
    }

    public static CookedDishEvidence load(CompoundTag tag) {
        List<ResourceLocation> ingredients = new ArrayList<>();
        ListTag list = tag.getList("ConsumedIngredients", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getCompound(i).getString("Id"));
            if (id != null) ingredients.add(id);
        }
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("RecipeId"));
        ResourceLocation result = ResourceLocation.tryParse(tag.getString("ResultItemId"));
        Producer producer;
        try {
            producer = Producer.valueOf(tag.getString("Producer"));
        } catch (IllegalArgumentException exception) {
            producer = Producer.PLAYER;
        }
        return new CookedDishEvidence(
                tag.getUUID("DishId"), tag.getUUID("CompetitionId"), tag.getInt("Round"),
                tag.getUUID("AuthorId"), requireId(recipe), requireId(result),
                CompetitionRecord.getPos(tag, "Station"), tag.getLong("ProducedGameTime"),
                ingredients, producer, tag.getString("CookeryQuality"),
                tag.getInt("RequiredOperations"), tag.getInt("VerifiedOperations"),
                tag.getInt("ElapsedTicks"), tag.getBoolean("Heated"),
                tag.getBoolean("RealCookeryStation"), tag.getBoolean("Submitted"));
    }

    private static ResourceLocation requireId(ResourceLocation id) {
        return id == null ? ResourceLocation.withDefaultNamespace("air") : id;
    }

    public enum Producer {
        PLAYER,
        BLAZE_CHEF
    }
}
