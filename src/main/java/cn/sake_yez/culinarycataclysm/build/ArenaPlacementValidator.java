/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.Vec3i
 *  net.minecraft.network.chat.Component
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.level.block.entity.BlockEntity
 *  net.minecraft.world.level.block.state.BlockState
 *  net.minecraft.world.level.material.FluidState
 */
package cn.sake_yez.culinarycataclysm.build;

import cn.sake_yez.culinarycataclysm.build.KitchenTemplateManager;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.Blocks;

public final class ArenaPlacementValidator {
    private ArenaPlacementValidator() {
    }

    public static Result validate(ServerLevel level, BlockPos origin,
                                  List<KitchenTemplateManager.TemplateBlock> blocks, BlockPos altar) {
        Set<Long> preserved = new HashSet<>();
        int bottomLayer = blocks.stream().mapToInt(block -> block.relativePos().getY()).min().orElse(0);
        for (KitchenTemplateManager.TemplateBlock templateBlock : blocks) {
            BlockPos target = origin.offset((Vec3i)templateBlock.relativePos());
            if (!level.isInWorldBounds(target) || !level.hasChunkAt(target)) {
                return Result.failure((Component)Component.translatable((String)"message.culinary_cataclysm.arena.unloaded", (Object[])new Object[]{target.toShortString()}));
            }
            BlockState existing = level.getBlockState(target);
            FluidState fluid = level.getFluidState(target);
            BlockEntity blockEntity = level.getBlockEntity(target);
            if (existing.isAir() && fluid.isEmpty() && blockEntity == null) continue;
            if (fluid.isEmpty() && isOriginalAltarBase(altar, target, existing)) {
                preserved.add(target.asLong());
                continue;
            }
            return Result.failure((Component)Component.translatable((String)"message.culinary_cataclysm.arena.occupied", (Object[])new Object[]{target.toShortString()}));
        }
        for (KitchenTemplateManager.TemplateBlock templateBlock : blocks) {
            if (templateBlock.relativePos().getY() != bottomLayer) continue;
            BlockPos support = origin.offset(templateBlock.relativePos()).below();
            if (level.hasChunkAt(support) && !level.getBlockState(support).isAir()
                    && level.getFluidState(support).isEmpty() && level.getBlockEntity(support) == null) continue;
            return Result.failure(Component.translatable("message.culinary_cataclysm.arena.unsupported", support.toShortString()));
        }
        return new Result(true, Component.empty(), Set.copyOf(preserved));
    }

    private static boolean isOriginalAltarBase(BlockPos altar, BlockPos target, BlockState existing) {
        if (target.equals(altar)) {
            return BuiltInRegistries.BLOCK.getKey(existing.getBlock())
                    .equals(ResourceLocation.fromNamespaceAndPath("cataclysm", "altar_of_fire"));
        }
        if (target.getY() != altar.getY() - 1
                || Math.abs(target.getX() - altar.getX()) > 2
                || Math.abs(target.getZ() - altar.getZ()) > 2) return false;
        return existing.is(Blocks.POLISHED_BLACKSTONE)
                || existing.is(Blocks.POLISHED_BLACKSTONE_STAIRS)
                || existing.is(Blocks.BLACKSTONE_STAIRS)
                || existing.is(Blocks.GOLD_BLOCK)
                || existing.is(Blocks.RED_NETHER_BRICKS);
    }

    public record Result(boolean valid, Component message, Set<Long> preservedBlocks) {
        public static Result failure(Component message) {
            return new Result(false, message, Set.of());
        }
    }
}

