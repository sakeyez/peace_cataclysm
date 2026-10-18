package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.challenge.Challenge;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeStore;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/** Regression checks for per-record commits and archive removal. */
public final class IgnisChallengeRegistryCheck {
    public static void run() {
        ChallengeStore store = new ChallengeStore();
        IgnisChallengeRegistry registry = new IgnisChallengeRegistry(store, () -> 42);
        CompetitionRecord first = record();
        CompetitionRecord second = record();
        registry.put(first);
        registry.put(second);
        IgnisChallengeRegistry restored = new IgnisChallengeRegistry(store, () -> 42);
        require(restored.byIgnis(first.ignisId).isPresent()
                        && restored.byPlayer(second.playerId).isPresent(),
                "active Ignis records were not replayed into the runtime registry");
        Challenge untouched = store.byId(second.competitionId).orElseThrow();

        first.currentRound = 3;
        registry.changed(first);
        require(store.byId(second.competitionId).orElseThrow() == untouched,
                "changing one competition recommitted another");
        require(IgnisChallengeCodec.toRecord(store.byId(first.competitionId).orElseThrow()).currentRound == 3,
                "changed competition was not saved");

        first.state = CompetitionState.COMPLETED;
        registry.changed(first);
        require(registry.byId(first.competitionId).isEmpty() && store.byId(first.competitionId).isEmpty(),
                "completed competition remained active");
        require(store.historical(first.competitionId).isPresent() && store.byBoss(first.ignisId).isEmpty(),
                "completed competition was not archived and unindexed");
        require(registry.byId(second.competitionId).isPresent(), "another competition was removed");
        try {
            registry.changed(first);
            throw new AssertionError("archived competition accepted another update");
        } catch (IllegalArgumentException expected) {
            // An archived record cannot be committed again.
        }
    }

    private static CompetitionRecord record() {
        return new CompetitionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.withDefaultNamespace("overworld"), new BlockPos(0, 64, 0),
                new BlockPos(-10, 62, -24), List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
