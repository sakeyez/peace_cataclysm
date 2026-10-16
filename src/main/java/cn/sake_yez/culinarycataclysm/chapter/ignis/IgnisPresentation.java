package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class IgnisPresentation {
    private IgnisPresentation() {
    }

    public static void showRoundProgress(ServerLevel level, CompetitionRecord record) {
        ItemStack expected = CookeryIntegration.expectedDish(level.getServer(), record.currentRecipe());
        announce(level, record, Component.translatable("message.culinary_cataclysm.round",
                record.currentRound + 1, record.recipes.size(), expected.getHoverName(), record.playerTotal, record.blazeTotal));
    }

    public static void showRoundResult(ServerLevel level, CompetitionRecord record) {
        int playerScore = record.playerScores.get(record.playerScores.size() - 1);
        int blazeScore = record.blazeScores.get(record.blazeScores.size() - 1);
        announce(level, record, Component.translatable("message.culinary_cataclysm.round_result",
                playerScore, blazeScore, record.playerTotal, record.blazeTotal));
    }

    public static void announce(ServerLevel level, CompetitionRecord record, Component message) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(record.arenaCenter.getX(), record.arenaCenter.getY(), record.arenaCenter.getZ()) <= 96.0 * 96.0) {
                player.sendSystemMessage(Component.translatable("message.culinary_cataclysm.ignis_prefix").append(message));
            }
        }
    }

    public static Component statusText(CompetitionRecord record) {
        return Component.literal("比赛 " + record.competitionId + " | " + record.state + " | 轮次 "
                + (record.currentRound + 1) + "/" + record.recipes.size() + " | "
                + record.playerTotal + " : " + record.blazeTotal + " | NPC " + record.blazeWorkState);
    }
}
