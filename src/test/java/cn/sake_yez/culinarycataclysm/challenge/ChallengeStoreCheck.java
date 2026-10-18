package cn.sake_yez.culinarycataclysm.challenge;

import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisChallengeCodec;
import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisChallengeRegistryCheck;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/** Executable checks for the current Challenge Store format and Ignis state. */
public final class ChallengeStoreCheck {
    public static void main(String[] args) {
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        CompetitionRecord record = new CompetitionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                dimension, new BlockPos(0, 64, 0), new BlockPos(-10, 62, -24),
                List.of(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_pot/test")));
        record.state = CompetitionState.BUILDING;
        record.buildCursor = 419;
        record.cleanupTargetCount = 700;
        record.preservedBattlefieldBlocks.add(new BlockPos(0, 64, 0).asLong());
        record.blazeInventory.put(ResourceLocation.withDefaultNamespace("iron_ingot"), 13);

        ChallengeStore store = new ChallengeStore();
        Challenge running = IgnisChallengeCodec.fromRecord(record, null, 10);
        require(running.lifecycle() == ChallengeLifecycle.RUNNING, "building lifecycle");
        require(!running.chapterData().hasUUID("CompetitionId"), "public identity duplicated");
        require(running.chapterDataVersion() == 2 && !running.chapterData().contains("OriginalBattlefieldFloor")
                && !running.chapterData().contains("PlatedSubmission"), "obsolete Ignis data persisted");
        store.put(running);
        store.associateBoss(running.id(), record.ignisId);
        require(store.byPlayer(record.playerId).orElseThrow().id().equals(running.id()), "player lookup");
        require(store.byBoss(record.ignisId).orElseThrow().id().equals(running.id()), "boss lookup");

        CompoundTag saved = store.save(new CompoundTag(), null);
        require(!saved.contains("ByPlayer") && !saved.contains("ByBoss"), "index persisted");
        store = ChallengeStore.load(saved, null);
        require(store.byPlayer(record.playerId).orElseThrow().id().equals(running.id()), "player index rebuild");
        require(store.byBoss(record.ignisId).isEmpty(), "boss index persisted");
        CompetitionRecord restored = IgnisChallengeCodec.toRecord(store.byId(running.id()).orElseThrow());
        require(restored.buildCursor == 419 && restored.cleanupTargetCount == 700, "build cursor lost");
        require(restored.battlefieldOrigin.equals(record.battlefieldOrigin), "arena position lost");
        require(restored.preservedBattlefieldBlocks.equals(record.preservedBattlefieldBlocks),
                "altar preservation lost");
        require(restored.blazeInventory.equals(record.blazeInventory), "chef inventory lost");
        store.associateBoss(running.id(), restored.ignisId);
        require(store.byBoss(record.ignisId).orElseThrow().id().equals(running.id()), "boss replay");

        UUID competingId = UUID.randomUUID();
        Challenge competing = challenge(competingId, record.playerId, dimension);
        ChallengeStore currentStore = store;
        expectRejected(() -> currentStore.put(competing), "duplicate active player accepted");
        require(store.byId(competingId).isEmpty(), "rejected challenge changed store");

        record.state = CompetitionState.INTERRUPTED;
        record.interruptedState = CompetitionState.CLEANUP;
        Challenge paused = IgnisChallengeCodec.fromRecord(record, store.byId(running.id()).orElseThrow(), 25);
        require(paused.lifecycle() == ChallengeLifecycle.PAUSED
                && paused.result() == ChallengeResult.ABORTED, "paused cleanup mapping");
        store.put(paused);
        record.state = CompetitionState.COMPLETED;
        Challenge ended = IgnisChallengeCodec.fromRecord(record, paused, 30);
        store.archive(IgnisChallengeCodec.history(ended, record));
        require(store.active().isEmpty() && store.history().size() == 1, "archive failed");
        require(store.byPlayer(record.playerId).isEmpty() && store.byBoss(record.ignisId).isEmpty(),
                "archived challenge remained indexed");
        require(!store.save(new CompoundTag(), null).getList("History", 10).getCompound(0)
                .contains("ChapterData"), "runtime state leaked into history");
        store = ChallengeStore.load(store.save(new CompoundTag(), null), null);
        require(store.historical(record.competitionId).isPresent() && store.active().isEmpty(),
                "history round-trip failed");
        expectRejected(() -> currentStore.put(running), "archived challenge reactivated");
        IgnisChallengeRegistryCheck.run();
        System.out.println("Challenge Store checks passed");
    }

    private static Challenge challenge(UUID id, UUID player, ResourceLocation dimension) {
        return new Challenge(id, IgnisChallengeCodec.CHAPTER_ID, dimension, List.of(player),
                ChallengeLifecycle.RUNNING, ChallengeResult.UNRESOLVED,
                0, 0, 0, 0, IgnisChallengeCodec.CHAPTER_DATA_VERSION, new CompoundTag());
    }

    private static void expectRejected(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
