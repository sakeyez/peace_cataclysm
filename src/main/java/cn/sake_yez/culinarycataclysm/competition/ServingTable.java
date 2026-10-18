package cn.sake_yez.culinarycataclysm.competition;

import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.decoration.TableBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.TableBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** 两张带红桌布的诡异木餐桌，各承载一个真实食物方块；来源证明由比赛 SavedData 持有。 */
public final class ServingTable {
    private static final ResourceLocation WARPED_TABLE = ResourceLocation.fromNamespaceAndPath(
            "kaleidoscope_cookery", "table_warped");
    private ServingTable() {
    }

    public static List<BlockPos> bases(CompetitionRecord record) {
        return List.of(record.playerServingBase(), record.blazeServingBase());
    }

    public static boolean canBuild(ServerLevel level, CompetitionRecord record) {
        return bases(record).stream().allMatch(pos -> level.hasChunkAt(pos)
                && level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty()
                && level.getBlockEntity(pos) == null && level.getBlockState(pos.above()).isAir());
    }

    public static boolean build(ServerLevel level, CompetitionRecord record) {
        record.servingTableBuilt = bases(record).stream()
                .allMatch(pos -> isReady(level, pos) && level.getBlockState(pos.above()).isAir());
        return record.servingTableBuilt;
    }

    public static boolean isReady(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(WARPED_TABLE)
                && state.hasProperty(TableBlock.HAS_CARPET) && state.getValue(TableBlock.HAS_CARPET)
                && level.getBlockEntity(pos) instanceof TableBlockEntity table
                && table.getColor() == DyeColor.RED;
    }

    public static boolean isPlaceableDish(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock().getClass() == FoodBiteBlock.class;
    }

    public static boolean matchesDish(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (!isPlaceableDish(stack)) return false;
        BlockState state = level.getBlockState(pos);
        return state.is(((BlockItem) stack.getItem()).getBlock())
                && state.hasProperty(FoodBiteBlock.QUALITY)
                && state.getValue(FoodBiteBlock.QUALITY) == QualityUtils.getQuality(stack).getId();
    }

    public static boolean placeBlazeDish(ServerLevel level, CompetitionRecord record, Mob chef, ItemStack stack) {
        BlockPos pos = record.blazeServingBase().above();
        if (!record.servingTableBuilt || !isReady(level, record.blazeServingBase())
                || !level.getBlockState(pos).isAir() || !isPlaceableDish(stack)) return false;
        FoodBiteBlock block = (FoodBiteBlock) ((BlockItem) stack.getItem()).getBlock();
        BlockState state = block.defaultBlockState()
                .setValue(FoodBiteBlock.FACING, Direction.WEST)
                .setValue(FoodBiteBlock.QUALITY, QualityUtils.getQuality(stack).getId());
        if (!state.canSurvive(level, pos) || !level.setBlockAndUpdate(pos, state)) return false;
        chef.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        record.blazePlatedDishId = record.currentBlazeDishId;
        return true;
    }

    public static void clearDishes(ServerLevel level, CompetitionRecord record) {
        if (record.playerPlatedDishId != null) {
            BlockPos pos = record.playerServingBase().above();
            if (level.getBlockState(pos).getBlock() instanceof FoodBiteBlock) level.removeBlock(pos, false);
            record.playerPlatedDishId = null;
        }
        if (record.blazePlatedDishId != null) {
            BlockPos pos = record.blazeServingBase().above();
            if (level.getBlockState(pos).getBlock() instanceof FoodBiteBlock) level.removeBlock(pos, false);
            record.blazePlatedDishId = null;
        }
    }

    public static void cleanup(ServerLevel level, CompetitionRecord record) {
        clearDishes(level, record);
        record.servingTableBuilt = false;
    }
}
