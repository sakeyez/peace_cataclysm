/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.HolderGetter
 *  net.minecraft.core.HolderLookup$RegistryLookup
 *  net.minecraft.core.registries.Registries
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.NbtAccounter
 *  net.minecraft.nbt.NbtIo
 *  net.minecraft.nbt.NbtUtils
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.server.MinecraftServer
 *  net.minecraft.world.level.block.state.BlockState
 */
package cn.sake_yez.culinarycataclysm.build;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;

public final class KitchenTemplateManager {
    public static final ResourceLocation BATTLEFIELD = ResourceLocation.fromNamespaceAndPath("culinary_cataclysm", "culinary_kitchens/battlefield.nbt");

    private KitchenTemplateManager() {
    }

    public static List<TemplateBlock> load(MinecraftServer server, ResourceLocation resourceId) throws IOException {
        try (InputStream stream = server.getResourceManager().open(resourceId);){
            CompoundTag root = NbtIo.readCompressed((InputStream)stream, (NbtAccounter)NbtAccounter.create((long)0x800000L));
            ListTag palette = root.getList("palette", 10);
            ListTag blocks = root.getList("blocks", 10);
            ArrayList<TemplateBlock> result = new ArrayList<TemplateBlock>(blocks.size());
            HolderLookup.RegistryLookup blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK);
            for (int i = 0; i < blocks.size(); ++i) {
                CompoundTag blockTag = blocks.getCompound(i);
                int stateIndex = blockTag.getInt("state");
                if (stateIndex < 0 || stateIndex >= palette.size()) {
                    throw new IOException("\u6a21\u677f\u65b9\u5757\u72b6\u6001\u7d22\u5f15\u8d8a\u754c: " + stateIndex);
                }
                BlockPos pos = KitchenTemplateManager.readPosition(blockTag, i);
                BlockState state = NbtUtils.readBlockState((HolderGetter)blockLookup, (CompoundTag)palette.getCompound(stateIndex));
                CompoundTag blockEntityTag = blockTag.contains("nbt", 10) ? blockTag.getCompound("nbt").copy() : null;
                result.add(new TemplateBlock(pos, state, blockEntityTag));
            }
            result.sort(Comparator.<TemplateBlock>comparingInt(block -> block.relativePos().getY()).thenComparingInt(block -> block.relativePos().getZ()).thenComparingInt(block -> block.relativePos().getX()));
            List<TemplateBlock> list = List.copyOf(result);
            return list;
        }
    }

    private static BlockPos readPosition(CompoundTag blockTag, int blockIndex) throws IOException {
        int[] pos;
        if (blockTag.contains("pos", 9)) {
            ListTag pos2 = blockTag.getList("pos", 3);
            if (pos2.size() == 3) {
                return new BlockPos(pos2.getInt(0), pos2.getInt(1), pos2.getInt(2));
            }
        } else if (blockTag.contains("pos", 11) && (pos = blockTag.getIntArray("pos")).length == 3) {
            return new BlockPos(pos[0], pos[1], pos[2]);
        }
        throw new IOException("\u6a21\u677f\u7b2c " + blockIndex + " \u4e2a\u65b9\u5757\u7684 pos \u5fc5\u987b\u5305\u542b\u4e09\u4e2a\u6574\u6570");
    }

    public record TemplateBlock(BlockPos relativePos, BlockState state, CompoundTag blockEntityTag) {
    }
}

