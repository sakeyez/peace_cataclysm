/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.Vec3i
 *  net.minecraft.core.particles.ParticleOptions
 *  net.minecraft.core.particles.ParticleTypes
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.NbtUtils
 *  net.minecraft.nbt.Tag
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.sounds.SoundEvents
 *  net.minecraft.sounds.SoundSource
 *  net.minecraft.util.Mth
 *  net.minecraft.world.entity.Display$BlockDisplay
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.EntityType
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.entity.BlockEntity
 *  net.minecraft.world.level.block.state.BlockState
 */
package cn.sake_yez.culinarycataclysm.build;

import cn.sake_yez.culinarycataclysm.build.ArenaPlacementValidator;
import cn.sake_yez.culinarycataclysm.build.KitchenTemplateManager;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class MagicSchematicBuilder {
    private static final int FLIGHT_TICKS = 32;
    private static final int DROP_HEIGHT = 16;
    private static final BlockPos BLAZE_STOOL_OFFSET = new BlockPos(14, 3, 9);
    private static final ResourceLocation STOOL_ID = ResourceLocation.fromNamespaceAndPath(
            "kaleidoscope_cookery", "cook_stool_crimson");
    private final Map<UUID, BuildPlan> plans = new HashMap<UUID, BuildPlan>();
    private final Map<UUID, List<BuildTarget>> targetPlans = new HashMap<>();
    private final Map<UUID, List<FlyingBlock>> flights = new HashMap<UUID, List<FlyingBlock>>();

    public ArenaPlacementValidator.Result prepare(ServerLevel level, CompetitionRecord record) throws IOException {
        BuildPlan plan = this.loadPlan(level, record);
        if (record.battlefieldOrigin != null) {
            ArenaPlacementValidator.Result result = ArenaPlacementValidator.validate(level, record.battlefieldOrigin,
                    battlefieldBlocks(plan, record), record.arenaCenter.below());
            if (result.valid()) {
                record.preservedBattlefieldBlocks.addAll(result.preservedBlocks());
                record.originalBattlefieldFloor.putAll(result.originalFloor());
            }
            return result;
        }
        ArenaPlacementValidator.Result playerResult = ArenaPlacementValidator.validate(level, record.playerKitchenOrigin, plan.playerBlocks());
        if (!playerResult.valid()) {
            return playerResult;
        }
        return ArenaPlacementValidator.validate(level, record.blazeKitchenOrigin, plan.blazeBlocks());
    }

    public BuildResult tick(ServerLevel level, CompetitionRecord record) {
        BuildPlan plan;
        try {
            plan = this.loadPlan(level, record);
        }
        catch (IOException exception) {
            return BuildResult.ERROR;
        }
        List<BuildTarget> targets = this.targetPlans.computeIfAbsent(record.competitionId,
                ignored -> buildTargets(plan, record));
        int total = targets.size();
        List<FlyingBlock> active = this.flights.computeIfAbsent(record.competitionId, ignored -> new ArrayList<>());
        Iterator<FlyingBlock> iterator = active.iterator();
        while (iterator.hasNext()) {
            FlyingBlock flight = iterator.next();
            ++flight.age;
            boolean omittedStool = isBlazeStoolTarget(record, flight.templateBlock);
            if (flight.age < FLIGHT_TICKS && !omittedStool) {
                Vec3 position = flightPosition(flight, (double)flight.age / FLIGHT_TICKS);
                if (flight.display != null && !flight.display.isRemoved()) {
                    flight.display.teleportTo(position.x, position.y, position.z);
                }
                if (flight.age % 2 == 0) {
                    level.sendParticles(ParticleTypes.FLAME, position.x + 0.5, position.y + 0.5,
                            position.z + 0.5, 1, 0.12, 0.12, 0.12, 0.005);
                }
                if (flight.age == FLIGHT_TICKS - 2) {
                    level.sendParticles(ParticleTypes.END_ROD, flight.target.getX() + 0.5,
                            flight.target.getY() + 0.5, flight.target.getZ() + 0.5,
                            2, 0.2, 0.35, 0.2, 0.02);
                }
            }
            if (flight.age < FLIGHT_TICKS) continue;
            if (flight.display != null && !flight.display.isRemoved()) {
                flight.display.discard();
            }
            if (omittedStool) {
                removeBlazeStool(level, record);
                if (!canPlace(level, record, flight.target)) return BuildResult.BLOCKED;
                ++record.buildCursor;
                iterator.remove();
                continue;
            }
            if (!canPlace(level, record, flight.target)) {
                return BuildResult.BLOCKED;
            }
            // setBlock 对“目标本来就是同一状态”的方块返回 false；地板嵌入旧场地时这是正常落位。
            if (!level.setBlock(flight.target, flight.templateBlock.state(), 3)
                    && !level.getBlockState(flight.target).equals(flight.templateBlock.state())) {
                return BuildResult.ERROR;
            }
            MagicSchematicBuilder.loadBlockEntity(level, flight.target, flight.templateBlock.blockEntityTag());
            level.sendParticles(ParticleTypes.LAVA, flight.target.getX() + 0.5,
                    flight.target.getY() + 0.6, flight.target.getZ() + 0.5,
                    2, 0.15, 0.15, 0.15, 0.01);
            if (record.buildCursor % 8 == 0) {
                level.playSound(null, flight.target, SoundEvents.FIRECHARGE_USE,
                        SoundSource.BLOCKS, 0.45f, 0.9f + level.random.nextFloat() * 0.3f);
            }
            ++record.buildCursor;
            iterator.remove();
        }
        int nextCursor = record.buildCursor + active.size();
        int maxFlights = record.twoSidedBuild ? FLIGHT_TICKS * 2 : FLIGHT_TICKS;
        if ((record.spiralBuild || record.mirroredBuild) && record.battlefieldOrigin != null) {
            if (nextCursor < total) {
                BuildTarget first = targets.get(nextCursor);
                // 当前层全部落位后才开始下一层，镜像方块始终同刻起飞。
                boolean sameLayer = active.isEmpty() || active.get(0).templateBlock.relativePos().getY()
                        == first.templateBlock.relativePos().getY();
                int groupSize = isMirroredPair(targets, nextCursor) ? 2 : 1;
                if (sameLayer && active.size() + groupSize <= maxFlights) {
                    for (int i = 0; i < groupSize; i++) {
                        BuildTarget target = targets.get(nextCursor + i);
                        if (!canPlace(level, record, target.pos)) return BuildResult.BLOCKED;
                    }
                    if (active.isEmpty() || record.stateTicks % 24 == 1) playBuildQuake(level, record);
                    for (int i = 0; i < groupSize; i++) {
                        launchFlight(level, record, targets.get(nextCursor + i), active);
                    }
                }
            }
        } else {
            // 旧比赛保留原有发射节奏和目标顺序，避免存档中的 buildCursor 失效。
            int startsThisTick = record.twoSidedBuild ? 2 : 1;
            while (startsThisTick-- > 0 && active.size() < maxFlights && nextCursor < total) {
                BuildTarget target = targets.get(nextCursor);
                if (!canPlace(level, record, target.pos)) return BuildResult.BLOCKED;
                launchFlight(level, record, target, active);
                nextCursor++;
            }
        }
        if (record.buildCursor >= total && active.isEmpty()) {
            this.plans.remove(record.competitionId);
            this.targetPlans.remove(record.competitionId);
            this.flights.remove(record.competitionId);
            return BuildResult.COMPLETE;
        }
        return BuildResult.RUNNING;
    }

    private static boolean isMirroredPair(List<BuildTarget> targets, int index) {
        if (index + 1 >= targets.size()) return false;
        BlockPos left = targets.get(index).templateBlock.relativePos();
        BlockPos right = targets.get(index + 1).templateBlock.relativePos();
        return left.getX() < 10 && right.getX() == 20 - left.getX()
                && right.getY() == left.getY() && right.getZ() == left.getZ();
    }

    private static void launchFlight(ServerLevel level, CompetitionRecord record, BuildTarget target,
                                     List<FlyingBlock> active) {
        if (isBlazeStoolTarget(record, target.templateBlock)) {
            active.add(new FlyingBlock(Vec3.atCenterOf(target.pos), target.pos, target.templateBlock, null));
            return;
        }
        double sideOffset = !record.twoSidedBuild
                || (record.spiralBuild || record.mirroredBuild) && target.templateBlock.relativePos().getX() == 10
                ? 0 : target.fromPlayerSide ? -6 : 6;
        int dropHeight = record.twoSidedBuild ? DROP_HEIGHT : 64;
        Vec3 source = new Vec3(target.pos.getX() + sideOffset, target.pos.getY() + dropHeight, target.pos.getZ());
        Display.BlockDisplay display = spawnFlyingDisplay(level, source, target);
        active.add(new FlyingBlock(source, target.pos, target.templateBlock, display));
        level.sendParticles(ParticleTypes.CLOUD, source.x + 0.5, source.y + 0.5, source.z + 0.5,
                3, 0.25, 0.12, 0.25, 0.02);
    }

    private static void playBuildQuake(ServerLevel level, CompetitionRecord record) {
        // Ignis 原版震屏为 0.15；施工只用 0.035，不触发地形破坏或伤害帧。
        try {
            Class.forName("com.github.L_Ender.cataclysm.entity.effect.ScreenShake_Entity")
                    .getMethod("ScreenShake", Level.class, Vec3.class, float.class, float.class, int.class, int.class)
                    .invoke(null, level, Vec3.atCenterOf(record.battlefieldOrigin.offset(10, 1, 10)),
                            30.0f, 0.035f, 0, 22);
        } catch (ReflectiveOperationException ignored) {
            // 震屏接口不可用时仍可继续施工。
        }
    }

    private static List<BuildTarget> buildTargets(BuildPlan plan, CompetitionRecord record) {
        if (record.battlefieldOrigin != null) {
            if (record.spiralBuild || record.mirroredBuild) return mirroredTargets(plan, record);
            if (!record.twoSidedBuild) {
                ArrayList<BuildTarget> targets = new ArrayList<>();
                for (KitchenTemplateManager.TemplateBlock block : battlefieldBlocks(plan, record)) {
                    BlockPos target = record.battlefieldOrigin.offset((Vec3i) block.relativePos());
                    if (!record.preservedBattlefieldBlocks.contains(target.asLong())) {
                        targets.add(new BuildTarget(target, block, true));
                    }
                }
                return targets;
            }
            ArrayList<BuildTarget> playerSide = new ArrayList<>();
            ArrayList<BuildTarget> blazeSide = new ArrayList<>();
            boolean centerToPlayer = true;
            for (KitchenTemplateManager.TemplateBlock block : battlefieldBlocks(plan, record)) {
                BlockPos target = record.battlefieldOrigin.offset((Vec3i) block.relativePos());
                if (!record.preservedBattlefieldBlocks.contains(target.asLong())) {
                    boolean player = block.relativePos().getX() < 10
                            || block.relativePos().getX() == 10 && centerToPlayer;
                    if (block.relativePos().getX() == 10) centerToPlayer = !centerToPlayer;
                    (player ? playerSide : blazeSide).add(new BuildTarget(target, block, player));
                }
            }
            ArrayList<BuildTarget> targets = new ArrayList<>(playerSide.size() + blazeSide.size());
            for (int i = 0; i < Math.max(playerSide.size(), blazeSide.size()); i++) {
                if (i < playerSide.size()) targets.add(playerSide.get(i));
                if (i < blazeSide.size()) targets.add(blazeSide.get(i));
            }
            return targets;
        }
        ArrayList<BuildTarget> targets = new ArrayList<BuildTarget>(plan.playerBlocks().size() + plan.blazeBlocks().size());
        int max = Math.max(plan.playerBlocks().size(), plan.blazeBlocks().size());
        for (int index = 0; index < max; ++index) {
            KitchenTemplateManager.TemplateBlock block;
            if (index < plan.playerBlocks().size()) {
                block = plan.playerBlocks().get(index);
                targets.add(new BuildTarget(record.playerKitchenOrigin.offset((Vec3i)block.relativePos()), block, true));
            }
            if (index >= plan.blazeBlocks().size()) continue;
            block = plan.blazeBlocks().get(index);
            targets.add(new BuildTarget(record.blazeKitchenOrigin.offset((Vec3i)block.relativePos()), block, false));
        }
        return targets;
    }

    private static List<BuildTarget> mirroredTargets(BuildPlan plan, CompetitionRecord record) {
        Map<Integer, Map<BlockPos, BuildTarget>> layers = new TreeMap<>();
        for (KitchenTemplateManager.TemplateBlock block : battlefieldBlocks(plan, record)) {
            BlockPos relative = block.relativePos();
            BlockPos target = record.battlefieldOrigin.offset((Vec3i)relative);
            if (record.preservedBattlefieldBlocks.contains(target.asLong())) continue;
            layers.computeIfAbsent(relative.getY(), ignored -> new HashMap<>())
                    .put(relative, new BuildTarget(target, block, relative.getX() < 10));
        }
        List<BuildTarget> result = new ArrayList<>();
        for (Map.Entry<Integer, Map<BlockPos, BuildTarget>> layer : layers.entrySet()) {
            int y = layer.getKey();
            Map<BlockPos, BuildTarget> pending = layer.getValue();
            if (record.spiralBuild) {
                // 已开工比赛沿用上一版螺旋顺序，避免存档中的 buildCursor 指向其他方块。
                for (int ring = 0; ring < 5; ring++) {
                    int minX = ring;
                    int maxX = 9 - ring;
                    int minZ = ring;
                    int maxZ = 20 - ring;
                    for (int x = minX; x <= maxX; x++) appendMirrored(pending, result, x, y, minZ);
                    for (int z = minZ + 1; z <= maxZ; z++) appendMirrored(pending, result, maxX, y, z);
                    for (int x = maxX - 1; x >= minX; x--) appendMirrored(pending, result, x, y, maxZ);
                    for (int z = maxZ - 1; z > minZ; z--) appendMirrored(pending, result, minX, y, z);
                }
            } else {
                // 新比赛按行推进，两侧同一位置的方块紧邻排列并同刻落下。
                for (int z = 0; z <= 20; z++) {
                    for (int x = 0; x < 10; x++) appendMirrored(pending, result, x, y, z);
                }
            }
            // 中线没有镜像伙伴，从正上方独立落下，不打断左右的成对施工。
            for (int z = 0; z <= 20; z++) {
                BuildTarget center = pending.remove(new BlockPos(10, y, z));
                if (center != null) result.add(center);
            }
            // 兼容模板未来增加边界外方块，保证目标不因排序被遗漏。
            pending.values().stream()
                    .sorted((a, b) -> {
                        int z = Integer.compare(a.templateBlock.relativePos().getZ(), b.templateBlock.relativePos().getZ());
                        return z != 0 ? z : Integer.compare(a.templateBlock.relativePos().getX(), b.templateBlock.relativePos().getX());
                    }).forEach(result::add);
        }
        return result;
    }

    private static void appendMirrored(Map<BlockPos, BuildTarget> pending, List<BuildTarget> result,
                                       int x, int y, int z) {
        BuildTarget left = pending.remove(new BlockPos(x, y, z));
        BuildTarget right = pending.remove(new BlockPos(20 - x, y, z));
        if (left != null) result.add(left);
        if (right != null) result.add(right);
    }

    private static List<KitchenTemplateManager.TemplateBlock> battlefieldBlocks(
            BuildPlan plan, CompetitionRecord record) {
        return record.aboveGroundOnly
                ? plan.battlefieldBlocks().stream()
                    .filter(block -> block.relativePos().getY() > 0).toList()
                : plan.battlefieldBlocks();
    }

    private static boolean isBlazeStoolTarget(CompetitionRecord record,
                                               KitchenTemplateManager.TemplateBlock block) {
        return record.battlefieldOrigin != null && block.relativePos().equals(BLAZE_STOOL_OFFSET);
    }

    /** Removes the stool left by an arena built before this template change. */
    public static void removeBlazeStool(ServerLevel level, CompetitionRecord record) {
        if (record.battlefieldOrigin == null) return;
        BlockPos pos = record.battlefieldOrigin.offset(BLAZE_STOOL_OFFSET);
        if (level.hasChunkAt(pos) && STOOL_ID.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()))) {
            level.removeBlock(pos, false);
        }
    }

    private static Vec3 flightPosition(FlyingBlock flight, double progress) {
        double fall = progress * progress * (3.0 - 2.0 * progress);
        return new Vec3(Mth.lerp(fall, flight.source.x, flight.target.getX()),
                Mth.lerp(fall, flight.source.y, flight.target.getY()), flight.source.z);
    }

    private static boolean canPlace(ServerLevel level, CompetitionRecord record, BlockPos target) {
        if (!level.hasChunkAt(target) || !level.getFluidState(target).isEmpty()
                || level.getBlockEntity(target) != null) return false;
        CompoundTag original = record.originalBattlefieldFloor.get(target.asLong());
        return original != null
                ? original.getString("Name").equals(
                    NbtUtils.writeBlockState(level.getBlockState(target)).getString("Name"))
                : level.getBlockState(target).isAir();
    }

    private static Display.BlockDisplay spawnFlyingDisplay(ServerLevel level, Vec3 source, BuildTarget target) {
        Display.BlockDisplay display = (Display.BlockDisplay)EntityType.BLOCK_DISPLAY.create((Level)level);
        if (display == null) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", (Tag)NbtUtils.writeBlockState((BlockState)target.templateBlock.state()));
        tag.putInt("teleport_duration", 1);
        tag.putFloat("view_range", 2.0f);
        tag.putFloat("shadow_radius", 0.25f);
        display.load(tag);
        display.setPos(source.x, source.y, source.z);
        display.getPersistentData().putBoolean("CulinaryCataclysmFlight", true);
        if (!level.addFreshEntity((Entity)display)) {
            return null;
        }
        return display;
    }

    private BuildPlan loadPlan(ServerLevel level, CompetitionRecord record) throws IOException {
        BuildPlan existing = this.plans.get(record.competitionId);
        if (existing != null) {
            return existing;
        }
        BuildPlan loaded = record.battlefieldOrigin != null
                ? new BuildPlan(List.of(), List.of(), KitchenTemplateManager.load(level.getServer(),
                    record.platedSubmission ? KitchenTemplateManager.BATTLEFIELD : KitchenTemplateManager.BATTLEFIELD_LEGACY))
                : new BuildPlan(KitchenTemplateManager.load(level.getServer(), KitchenTemplateManager.PLAYER),
                    KitchenTemplateManager.load(level.getServer(), KitchenTemplateManager.BLAZE), List.of());
        this.plans.put(record.competitionId, loaded);
        return loaded;
    }

    private static void loadBlockEntity(ServerLevel level, BlockPos pos, CompoundTag sourceTag) {
        if (sourceTag == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        CompoundTag tag = sourceTag.copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        blockEntity.loadWithComponents(tag, (HolderLookup.Provider)level.registryAccess());
        blockEntity.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }

    public void cancelVisuals(CompetitionRecord record) {
        List<FlyingBlock> active = this.flights.remove(record.competitionId);
        if (active != null) {
            active.forEach(flight -> {
                if (flight.display != null && !flight.display.isRemoved()) {
                    flight.display.discard();
                }
            });
        }
    }

    public BuildResult cleanup(ServerLevel level, CompetitionRecord record) {
        BuildPlan plan;
        List<FlyingBlock> active = this.flights.remove(record.competitionId);
        if (active != null) {
            active.forEach(flight -> {
                if (flight.display != null && !flight.display.isRemoved()) {
                    flight.display.discard();
                }
            });
        }
        try {
            plan = this.loadPlan(level, record);
        }
        catch (IOException exception) {
            return BuildResult.ERROR;
        }
        List<BuildTarget> targets = this.targetPlans.computeIfAbsent(record.competitionId,
                ignored -> buildTargets(plan, record));
        int total = record.cleanupTargetCount >= 0
                ? Math.min(record.cleanupTargetCount, targets.size()) : targets.size();
        int budget = 0;
        while (budget < 30 && record.buildCursor < total) {
            BuildTarget buildTarget = targets.get(total - 1 - record.buildCursor);
            KitchenTemplateManager.TemplateBlock templateBlock = buildTarget.templateBlock();
            BlockPos target = buildTarget.pos();
            if (!level.hasChunkAt(target)) {
                return BuildResult.BLOCKED;
            }
            if (isBlazeStoolTarget(record, templateBlock)) {
                removeBlazeStool(level, record);
                ++budget;
                ++record.buildCursor;
                continue;
            }
            if (level.getBlockState(target).is(templateBlock.state().getBlock())) {
                CompoundTag original = record.originalBattlefieldFloor.get(target.asLong());
                if (original != null) {
                    BlockState originalState = NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK), original);
                    level.setBlock(target, originalState, 3);
                } else {
                    level.removeBlock(target, false);
                }
                level.sendParticles((ParticleOptions)ParticleTypes.SMOKE, (double)target.getX() + 0.5, (double)target.getY() + 0.5, (double)target.getZ() + 0.5, 2, 0.1, 0.1, 0.1, 0.01);
            }
            ++budget;
            ++record.buildCursor;
        }
        if (record.buildCursor >= total) {
            this.plans.remove(record.competitionId);
            this.targetPlans.remove(record.competitionId);
            return BuildResult.COMPLETE;
        }
        return BuildResult.RUNNING;
    }

    private record BuildPlan(List<KitchenTemplateManager.TemplateBlock> playerBlocks,
                             List<KitchenTemplateManager.TemplateBlock> blazeBlocks,
                             List<KitchenTemplateManager.TemplateBlock> battlefieldBlocks) {
    }

    public static enum BuildResult {
        RUNNING,
        COMPLETE,
        BLOCKED,
        ERROR;

    }

    private static final class FlyingBlock {
        private final Vec3 source;
        private final BlockPos target;
        private final KitchenTemplateManager.TemplateBlock templateBlock;
        private final Display.BlockDisplay display;
        private int age;

        private FlyingBlock(Vec3 source, BlockPos target, KitchenTemplateManager.TemplateBlock templateBlock,
                            Display.BlockDisplay display) {
            this.source = source;
            this.target = target;
            this.templateBlock = templateBlock;
            this.display = display;
        }
    }

    private record BuildTarget(BlockPos pos, KitchenTemplateManager.TemplateBlock templateBlock,
                               boolean fromPlayerSide) {
    }
}

