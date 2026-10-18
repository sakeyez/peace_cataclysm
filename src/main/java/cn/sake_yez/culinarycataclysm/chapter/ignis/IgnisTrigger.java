package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.boss.IgnisPeaceController;
import cn.sake_yez.culinarycataclysm.build.ArenaPlacementValidator;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeStore;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IgnisTrigger {
    private static final ResourceLocation ALTAR = ResourceLocation.fromNamespaceAndPath("cataclysm", "altar_of_fire");
    private static final ResourceLocation ASHES = ResourceLocation.fromNamespaceAndPath("cataclysm", "burning_ashes");

    private final Map<GlobalPos, PendingSummon> pendingSummons = new HashMap<>();
    private final IgnisBuildController buildController;

    public IgnisTrigger(IgnisBuildController buildController) {
        this.buildController = buildController;
    }

    public void tick(MinecraftServer server) {
        pendingSummons.entrySet().removeIf(entry -> {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            return level == null || level.getGameTime() > entry.getValue().expiresAt();
        });
    }

    public void trackAltarClick(ServerPlayer player, InteractionHand hand, BlockPos pos, ItemStack held) {
        if (hand == InteractionHand.MAIN_HAND
                && !player.isShiftKeyDown()
                && ALTAR.equals(BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(pos).getBlock()))
                && ASHES.equals(BuiltInRegistries.ITEM.getKey(held.getItem()))
                && altarSlotEmpty(player.serverLevel(), pos)) {
            GlobalPos altar = GlobalPos.of(player.serverLevel().dimension(), pos.immutable());
            pendingSummons.putIfAbsent(altar, new PendingSummon(player.getUUID(), player.serverLevel().getGameTime() + 180));
        }
    }

    public void onIgnisSpawn(ServerLevel level, Entity ignis) {
        if (!IgnisPeaceController.isIgnis(ignis)) return;
        IgnisPeaceController.altarHome(ignis).ifPresent(home -> {
            PendingSummon pending = pendingSummons.remove(home);
            if (pending == null || level.getGameTime() > pending.expiresAt()) return;
            ServerPlayer summoner = level.getServer().getPlayerList().getPlayer(pending.playerId());
            if (summoner == null || summoner.serverLevel() != level
                    || summoner.distanceToSqr(home.pos().getX() + 0.5, home.pos().getY() + 0.5,
                        home.pos().getZ() + 0.5) > 64.0 * 64.0) return;
            startFromAltar(level, summoner, ignis, home.pos());
        });
    }

    public void tryStartFromInteraction(ServerPlayer player, Entity ignis) {
        IgnisPeaceController.altarHome(ignis).ifPresent(home -> {
            if (home.dimension().equals(player.serverLevel().dimension())
                    && player.distanceToSqr(home.pos().getX() + 0.5, home.pos().getY() + 0.5,
                        home.pos().getZ() + 0.5) <= 64.0 * 64.0
                    && ALTAR.equals(BuiltInRegistries.BLOCK.getKey(
                        player.serverLevel().getBlockState(home.pos()).getBlock()))) {
                startFromAltar(player.serverLevel(), player, ignis, home.pos());
            }
        });
    }

    private void startFromAltar(ServerLevel level, ServerPlayer player, Entity ignis, BlockPos altarPos) {
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(level);
        ChallengeStore store = ChallengeStore.get(level);
        if (store.byPlayer(player.getUUID()).isPresent() || store.byBoss(ignis.getUUID()).isPresent()) {
            player.sendSystemMessage(Component.translatable("message.culinary_cataclysm.already_active"));
            return;
        }
        if (!IgnisDependencies.requiredRegistriesPresent()) {
            player.sendSystemMessage(Component.translatable("message.culinary_cataclysm.dependencies_missing"));
            return;
        }
        List<ResourceLocation> recipes;
        try {
            recipes = IgnisRecipeSelection.resolveConfiguredRecipes(level.getServer());
        } catch (IOException exception) {
            player.sendSystemMessage(Component.literal("比赛菜谱配置错误：" + exception.getMessage()));
            return;
        }
        // 玩家从祭坛南侧望向北侧；场地沿用大厅地板。
        // 焰魔留在祭坛上，不跟随厨房移动。
        BlockPos origin = altarPos.offset(-10, -2, -24);
        BlockPos center = altarPos.above();
        CompetitionRecord record = new CompetitionRecord(UUID.randomUUID(), player.getUUID(), ignis.getUUID(),
                level.dimension().location(), center, origin, recipes);
        final ArenaPlacementValidator.Result placement;
        try {
            placement = buildController.prepare(level, record);
        } catch (IOException exception) {
            player.sendSystemMessage(Component.literal("场地模板读取失败：" + exception.getMessage()));
            return;
        }
        if (!placement.valid()) {
            player.sendSystemMessage(placement.message());
            return;
        }
        if (!ServingTable.canBuild(level, record)) {
            player.sendSystemMessage(Component.literal("比赛桌位置被占用，请清空两侧九格玻璃正中间上方的餐桌与摆盘空间。"));
            return;
        }
        record.state = CompetitionState.OPENING;
        IgnisPeaceController.apply(ignis, record.competitionId);
        data.put(record);
        IgnisPresentation.announce(level, record, Component.translatable("message.culinary_cataclysm.opening"));
        player.sendSystemMessage(Component.literal("已启动比赛 " + record.competitionId));
    }

    private static boolean altarSlotEmpty(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return false;
        try {
            Object item = blockEntity.getClass().getMethod("getItem", int.class).invoke(blockEntity, 0);
            return item instanceof ItemStack stack && stack.isEmpty();
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private record PendingSummon(UUID playerId, long expiresAt) {
    }
}
