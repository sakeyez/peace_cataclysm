package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.challenge.Challenge;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeLifecycle;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeStore;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;

import java.util.Collection;

/** Imports the frozen legacy file by stable competition ID; safe to repeat after a restart. */
public final class IgnisLegacyMigration {
    private IgnisLegacyMigration() {
    }

    public static void importInto(ChallengeStore store, Collection<CompetitionRecord> legacyRecords) {
        for (CompetitionRecord legacy : legacyRecords) {
            if (store.contains(legacy.competitionId)) continue;
            Challenge challenge = IgnisChallengeCodec.fromRecord(legacy, null, 0);
            if (challenge.lifecycle() == ChallengeLifecycle.ENDED) {
                store.archive(IgnisChallengeCodec.history(challenge, legacy));
            } else {
                store.put(challenge);
            }
        }
    }
}
