package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.boss.IgnisPeaceController;
import cn.sake_yez.culinarycataclysm.build.ArenaPlacementValidator;
import cn.sake_yez.culinarycataclysm.build.MagicSchematicBuilder;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionSavedData;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import cn.sake_yez.culinarycataclysm.npc.ArenaAudienceController;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.io.IOException;

public final class IgnisBuildController {
    private final MagicSchematicBuilder builder = new MagicSchematicBuilder();

    public ArenaPlacementValidator.Result prepare(ServerLevel level, CompetitionRecord record) throws IOException {
        return builder.prepare(level, record);
    }

    public void cancelVisuals(CompetitionRecord record) {
        builder.cancelVisuals(record);
    }

    public void removeBlazeStool(ServerLevel level, CompetitionRecord record) {
        builder.removeBlazeStool(level, record);
    }

    public void tickBuilding(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        Entity ignis = level.getEntity(record.ignisId);
        if (ignis != null) {
            IgnisPeaceController.setCeremonialSword(ignis, true);
            if (record.twoSidedBuild && record.stateTicks == 1) IgnisPeaceController.playBuildGesture(ignis);
            if (record.twoSidedBuild && record.stateTicks == 25) IgnisPeaceController.stopBuildGesture(ignis);
        }
        int previousCursor = record.buildCursor;
        MagicSchematicBuilder.BuildResult result = builder.tick(level, record);
        if (record.buildCursor != previousCursor) data.setDirty();
        if (result == MagicSchematicBuilder.BuildResult.COMPLETE) {
            if (record.platedSubmission && !ServingTable.build(level, record)) {
                record.interruptedState = CompetitionState.BUILDING;
                record.state = CompetitionState.INTERRUPTED;
                record.stateTicks = 0;
                data.setDirty();
                IgnisPresentation.announce(level, record, Component.literal("餐桌搭建受阻，请检查两侧九格玻璃正中间上方后恢复比赛。"));
                return;
            }
            record.kitchensBuilt = true;
            IgnisCookeryStations.markCompetitionStations(level, record);
            if (ignis != null) {
                IgnisPeaceController.stopBuildGesture(ignis);
                IgnisPeaceController.setCeremonialSword(ignis, false);
            }
            transition(data, record, CompetitionState.READY);
            IgnisPresentation.announce(level, record, Component.translatable("message.culinary_cataclysm.kitchens_ready"));
        } else if (result == MagicSchematicBuilder.BuildResult.BLOCKED || result == MagicSchematicBuilder.BuildResult.ERROR) {
            builder.cancelVisuals(record);
            if (ignis != null) {
                IgnisPeaceController.stopBuildGesture(ignis);
                IgnisPeaceController.setCeremonialSword(ignis, false);
            }
            record.interruptedState = CompetitionState.BUILDING;
            record.state = CompetitionState.INTERRUPTED;
            record.stateTicks = 0;
            data.setDirty();
            IgnisPresentation.announce(level, record, Component.translatable("message.culinary_cataclysm.build_interrupted"));
        }
    }

    public void tickCleanup(ServerLevel level, CompetitionSavedData data, CompetitionRecord record) {
        ArenaAudienceController.cleanup(level, record);
        if (record.platedSubmission) ServingTable.cleanup(level, record);
        MagicSchematicBuilder.BuildResult result = builder.cleanup(level, record);
        if (result == MagicSchematicBuilder.BuildResult.COMPLETE) {
            if (record.blazeChefId != null) {
                Entity chef = level.getEntity(record.blazeChefId);
                if (chef != null) chef.discard();
            }
            record.blazeInventory.clear();
            record.state = CompetitionState.COMPLETED;
            record.stateTicks = 0;
            data.setDirty();
        } else if (result == MagicSchematicBuilder.BuildResult.ERROR) {
            record.interruptedState = CompetitionState.CLEANUP;
            record.state = CompetitionState.INTERRUPTED;
            data.setDirty();
        }
    }

    private static void transition(CompetitionSavedData data, CompetitionRecord record, CompetitionState state) {
        record.state = state;
        record.stateTicks = 0;
        data.setDirty();
    }
}
