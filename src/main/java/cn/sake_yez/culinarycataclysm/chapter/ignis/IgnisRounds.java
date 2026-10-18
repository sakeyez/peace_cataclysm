package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import cn.sake_yez.culinarycataclysm.npc.BlazeChefController;
import net.minecraft.server.level.ServerLevel;

public final class IgnisRounds {
    private IgnisRounds() {
    }

    public static void startRound(ServerLevel level, IgnisChallengeRegistry data, CompetitionRecord record) {
        ServingTable.clearDishes(level, record);
        record.platedJudgingAtGameTime = 0;
        // 每轮开始时重写锅具归属，确保方块实体重载后仍属于本场比赛。
        IgnisCookeryStations.markCompetitionStations(level, record);
        transition(data, record, CompetitionState.ROUND_ACTIVE);
        record.stationInteractions = 0;
        record.playerVerifiedOperations = 0;
        record.playerCookingStartedTick = 0;
        record.playerRoundIngredients.clear();
        record.currentPlayerDishId = null;
        record.playerPlatedDishId = null;
        BlazeChefController.beginRound(record);
        data.changed(record);
        IgnisPresentation.showRoundProgress(level, record);
    }

    public static void advanceRound(ServerLevel level, IgnisChallengeRegistry data, CompetitionRecord record) {
        record.currentRound++;
        if (record.currentRound >= record.recipes.size()) transition(data, record, CompetitionState.FINAL_RESULT);
        else startRound(level, data, record);
    }

    private static void transition(IgnisChallengeRegistry data, CompetitionRecord record, CompetitionState state) {
        record.state = state;
        record.stateTicks = 0;
        data.changed(record);
    }
}
