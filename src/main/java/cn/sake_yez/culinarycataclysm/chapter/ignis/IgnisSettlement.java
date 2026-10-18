package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRewardHandler;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class IgnisSettlement {
    private IgnisSettlement() {
    }

    public static void tickFinalResult(ServerLevel level, IgnisChallengeRegistry data, CompetitionRecord record) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(record.playerId);
        if (!record.finalResultResolved && player != null) {
            Component result;
            if (record.playerTotal > record.blazeTotal) {
                CompetitionRewardHandler.grant(player, record);
                result = Component.translatable("message.culinary_cataclysm.final_win", record.playerTotal, record.blazeTotal);
            } else if (record.playerTotal < record.blazeTotal) {
                result = Component.translatable("message.culinary_cataclysm.final_loss", record.playerTotal, record.blazeTotal);
            } else {
                result = Component.translatable("message.culinary_cataclysm.final_draw", record.playerTotal);
            }
            IgnisPresentation.announce(level, record, result);
            record.finalResultResolved = true;
            data.changed(record);
        }
        if (record.finalResultResolved && record.stateTicks >= 200) {
            record.cleanupTargetCount = record.buildCursor;
            record.buildCursor = 0;
            record.state = CompetitionState.CLEANUP;
            record.stateTicks = 0;
            data.changed(record);
        }
    }
}
