package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import cn.sake_yez.culinarycataclysm.npc.BlazeChefController;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.Optional;

public final class IgnisDiagnostics {
    private IgnisDiagnostics() {
    }

    public static int diagnose(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Optional<CompetitionRecord> optional = IgnisChallengeRegistry.get(source.getLevel()).byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        CompetitionRecord record = optional.get();
        CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFactsUnchecked(source.getServer(), record.currentRecipe());
        BlockPos playerStationPos = IgnisCookeryStations.playerStation(record, facts);
        BlockPos blazeStationPos = IgnisCookeryStations.blazeStation(record, facts);
        var playerStation = CookeryIntegration.inspectStation(source.getLevel(), playerStationPos, facts);
        var blazeStation = CookeryIntegration.inspectStation(source.getLevel(), blazeStationPos, facts);
        boolean playerStationTagged = IgnisCookeryStations.isTaggedPlayerStation(source.getLevel(), playerStationPos, record);
        boolean canContinue = facts.supported() && playerStation.blockEntityPresent() && playerStation.heated()
                && blazeStation.blockEntityPresent() && blazeStation.heated()
                && record.blazeWorkState != BlazeChefController.WorkState.FAILED;
        source.sendSuccess(() -> Component.literal("料理比赛诊断：" + (canContinue ? "可继续" : "需要管理员检查")
                + " | 当前菜谱=" + record.currentRecipe()
                + " | 玩家锅具=" + playerStation.blockEntityPresent() + "/热源=" + playerStation.heated()
                + " | NPC=" + record.blazeWorkState
                + (record.blazeFailureReason.isEmpty() ? "" : "/原因=" + record.blazeFailureReason)
                + " | 有效生产=" + (record.playerProducedCount + record.blazeProducedCount)
                + " | 已提交=" + (record.playerSubmittedCount + record.blazeSubmittedCount)), false);
        if (source.hasPermission(2)) {
            source.sendSuccess(() -> Component.literal("管理诊断：依赖 " + versions()
                    + " | 注册表=" + IgnisDependencies.requiredRegistriesPresent()
                    + " | 玩家锅=" + playerStationPos + "/标签=" + playerStationTagged
                    + "/状态=" + playerStation.status() + "/完成=" + playerStation.readyToExtract()
                    + "/配方=" + playerStation.recipeMatches() + "/结果=" + itemId(playerStation.result())
                    + " | NPC锅=" + blazeStationPos + "/状态=" + blazeStation.status()
                    + "/配方=" + blazeStation.recipeMatches()
                    + " | NPC航点=" + record.blazeRouteIndex
                    + "/移动停滞=" + record.blazeMoveStallTicks
                    + "/烹饪停滞=" + record.blazeCookingStallTicks
                    + " | 证据记录=" + record.dishEvidence.size()), false);
        }
        return canContinue ? 1 : 0;
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "空" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String versions() {
        return "Cataclysm=" + version("cataclysm") + ", Create=" + version("create")
                + ", Kaleidoscope Cookery=" + version("kaleidoscope_cookery")
                + ", NeoForge=" + version("neoforge");
    }

    private static String version(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("未加载");
    }
}
