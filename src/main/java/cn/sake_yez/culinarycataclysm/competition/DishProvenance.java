package cn.sake_yez.culinarycataclysm.competition;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/** 将权威记录的 UUID 写入成品。复制物品只会复制索引，无法复制服务端记录的可用状态。 */
public final class DishProvenance {
    private static final String ROOT = "CulinaryCataclysmDish";
    private static final String DISH_ID = "DishId";
    private static final String COMPETITION_ID = "CompetitionId";
    private static final String ROUND = "Round";

    private DishProvenance() {
    }

    public static void mark(ItemStack stack, CookedDishEvidence evidence) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag proof = new CompoundTag();
            proof.putUUID(DISH_ID, evidence.dishId);
            proof.putUUID(COMPETITION_ID, evidence.competitionId);
            proof.putInt(ROUND, evidence.round);
            tag.put(ROOT, proof);
        });
    }

    public static Optional<Marker> read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag root = data.copyTag();
        if (!root.contains(ROOT, CompoundTag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag proof = root.getCompound(ROOT);
        if (!proof.hasUUID(DISH_ID) || !proof.hasUUID(COMPETITION_ID)) return Optional.empty();
        return Optional.of(new Marker(proof.getUUID(DISH_ID), proof.getUUID(COMPETITION_ID), proof.getInt(ROUND)));
    }

    public record Marker(UUID dishId, UUID competitionId, int round) {
    }
}
