package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.challenge.Challenge;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeHistory;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeLifecycle;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeResult;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Translates the existing Ignis state without teaching the public store its NBT fields. */
public final class IgnisChallengeCodec {
    public static final ResourceLocation CHAPTER_ID = ResourceLocation.fromNamespaceAndPath(
            "culinary_cataclysm", "ignis_competition");
    public static final int CHAPTER_DATA_VERSION = 1;

    private IgnisChallengeCodec() {
    }

    public static Challenge fromRecord(CompetitionRecord record, Challenge previous, long gameTime) {
        ChallengeLifecycle lifecycle = lifecycle(record);
        ChallengeResult result = result(record);
        long createdAt = previous == null ? gameTime : previous.createdAt();
        long startedAt = previous == null ? gameTime : previous.startedAt();
        long resolvedAt = previous == null ? 0 : previous.resolvedAt();
        long endedAt = previous == null ? 0 : previous.endedAt();
        if (result != ChallengeResult.UNRESOLVED && resolvedAt == 0) resolvedAt = gameTime;
        if (lifecycle == ChallengeLifecycle.ENDED && endedAt == 0) endedAt = gameTime;

        CompoundTag chapterData = record.save();
        chapterData.remove("CompetitionId");
        chapterData.remove("PlayerId");
        chapterData.remove("Dimension");
        return new Challenge(record.competitionId, CHAPTER_ID, record.dimension, List.of(record.playerId),
                lifecycle, result, createdAt, startedAt, resolvedAt, endedAt,
                CHAPTER_DATA_VERSION, chapterData);
    }

    public static CompetitionRecord toRecord(Challenge challenge) {
        if (!CHAPTER_ID.equals(challenge.chapterId()) || challenge.chapterDataVersion() != CHAPTER_DATA_VERSION
                || challenge.participants().size() != 1) {
            throw new IllegalStateException("Unsupported Ignis challenge " + challenge.id());
        }
        CompoundTag data = challenge.chapterData();
        data.putUUID("CompetitionId", challenge.id());
        data.putUUID("PlayerId", challenge.participants().get(0));
        data.putString("Dimension", challenge.dimension().toString());
        return CompetitionRecord.load(data);
    }

    public static ChallengeHistory history(Challenge challenge, CompetitionRecord record) {
        CompoundTag summary = new CompoundTag();
        summary.putInt("PlayerTotal", record.playerTotal);
        summary.putInt("BlazeTotal", record.blazeTotal);
        summary.putBoolean("RewardIssued", record.rewardIssued);
        summary.putBoolean("FinalResultResolved", record.finalResultResolved);
        return new ChallengeHistory(challenge, summary);
    }

    private static ChallengeLifecycle lifecycle(CompetitionRecord record) {
        return switch (record.state) {
            case COMPLETED -> ChallengeLifecycle.ENDED;
            case CLEANUP -> ChallengeLifecycle.CLEANUP;
            case INTERRUPTED -> ChallengeLifecycle.PAUSED;
            default -> ChallengeLifecycle.RUNNING;
        };
    }

    private static ChallengeResult result(CompetitionRecord record) {
        if (record.finalResultResolved) {
            return record.playerTotal > record.blazeTotal ? ChallengeResult.SUCCESS : ChallengeResult.FAILURE;
        }
        if (record.state == CompetitionState.CLEANUP || record.state == CompetitionState.COMPLETED
                || (record.state == CompetitionState.INTERRUPTED
                    && record.interruptedState == CompetitionState.CLEANUP)) {
            return ChallengeResult.ABORTED;
        }
        return ChallengeResult.UNRESOLVED;
    }
}
