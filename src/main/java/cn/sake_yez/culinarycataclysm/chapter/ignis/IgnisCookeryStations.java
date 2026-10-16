package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class IgnisCookeryStations {
    private static final String COMPETITION_TAG = "CulinaryCataclysmCompetition";
    private static final String OWNER_TAG = "CulinaryCataclysmOwner";
    private static final String PLAYER_STATION_TAG = "CulinaryCataclysmPlayerStation";

    private IgnisCookeryStations() {
    }

    public static BlockPos playerStation(CompetitionRecord record, CookeryIntegration.RecipeFacts facts) {
        return facts.station() == CookeryIntegration.StationType.STOCKPOT ? record.playerStockpotPos() : record.playerPotPos();
    }

    public static BlockPos blazeStation(CompetitionRecord record, CookeryIntegration.RecipeFacts facts) {
        return facts.station() == CookeryIntegration.StationType.STOCKPOT ? record.blazeStockpotPos() : record.blazePotPos();
    }

    /** 给双厨房的每口炒锅和汤锅写入持久比赛归属。 */
    public static void markCompetitionStations(ServerLevel level, CompetitionRecord record) {
        markStation(level, record.playerPotPos(), record, true);
        markStation(level, record.playerSecondPotPos(), record, true);
        markStation(level, record.playerStockpotPos(), record, true);
        markStation(level, record.blazePotPos(), record, false);
        markStation(level, record.blazeSecondPotPos(), record, false);
        markStation(level, record.blazeStockpotPos(), record, false);
    }

    private static void markStation(ServerLevel level, BlockPos pos, CompetitionRecord record, boolean playerStation) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof PotBlockEntity) && !(blockEntity instanceof StockpotBlockEntity)) return;
        CompoundTag tag = blockEntity.getPersistentData();
        tag.putUUID(COMPETITION_TAG, record.competitionId);
        tag.putUUID(OWNER_TAG, record.playerId);
        tag.putBoolean(PLAYER_STATION_TAG, playerStation);
        blockEntity.setChanged();
    }

    public static boolean isTaggedPlayerStation(ServerLevel level, BlockPos pos, CompetitionRecord record) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof PotBlockEntity) && !(blockEntity instanceof StockpotBlockEntity)) return false;
        CompoundTag tag = blockEntity.getPersistentData();
        return tag.getBoolean(PLAYER_STATION_TAG)
                && tag.hasUUID(COMPETITION_TAG) && record.competitionId.equals(tag.getUUID(COMPETITION_TAG))
                && tag.hasUUID(OWNER_TAG) && record.playerId.equals(tag.getUUID(OWNER_TAG));
    }
}
