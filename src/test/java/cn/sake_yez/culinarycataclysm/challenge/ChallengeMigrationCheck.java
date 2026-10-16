package cn.sake_yez.culinarycataclysm.challenge;

import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisChallengeCodec;
import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisLegacyMigration;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small executable migration check with no test-framework dependency. */
public final class ChallengeMigrationCheck {
    public static void main(String[] args) throws IOException {
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        CompetitionRecord record = new CompetitionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                dimension, new BlockPos(0, 64, 0), new BlockPos(-8, 62, 0), new BlockPos(8, 62, 0),
                List.of(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_pot/test")));
        record.state = CompetitionState.BUILDING;
        record.buildCursor = 419;
        record.cleanupTargetCount = 700;
        record.battlefieldOrigin = new BlockPos(-10, 62, -24);
        record.mirroredBuild = true;
        record.blazeInventory.put(ResourceLocation.withDefaultNamespace("iron_ingot"), 13);

        Challenge migrated = IgnisChallengeCodec.fromRecord(record, null, 0);
        require(migrated.lifecycle() == ChallengeLifecycle.RUNNING, "building lifecycle");
        require(!migrated.chapterData().hasUUID("CompetitionId"), "public identity duplicated in chapter data");
        CompetitionRecord restored = IgnisChallengeCodec.toRecord(Challenge.load(migrated.save()));
        require(restored.buildCursor == 419 && restored.cleanupTargetCount == 700, "build cursor lost");
        require(restored.mirroredBuild && restored.battlefieldOrigin.equals(record.battlefieldOrigin), "build mode lost");
        require(restored.blazeInventory.equals(record.blazeInventory), "chef inventory lost");

        ChallengeStore store = new ChallengeStore();
        IgnisLegacyMigration.importInto(store, List.of(record));
        require(store.contains(record.competitionId) && store.active().size() == 1, "first import failed");
        IgnisLegacyMigration.importInto(store, List.of(record));
        require(store.active().size() == 1, "repeat import duplicated an active challenge");
        store = ChallengeStore.load(store.save(new CompoundTag(), null), null);
        require(store.active().size() == 1 && store.history().isEmpty(), "active store round-trip failed");

        record.state = CompetitionState.INTERRUPTED;
        record.interruptedState = CompetitionState.CLEANUP;
        Challenge pausedCleanup = IgnisChallengeCodec.fromRecord(record, store.byId(record.competitionId).orElseThrow(), 25);
        require(pausedCleanup.lifecycle() == ChallengeLifecycle.PAUSED
                && pausedCleanup.result() == ChallengeResult.ABORTED, "paused cleanup mapping");
        store.put(pausedCleanup);
        record.state = CompetitionState.COMPLETED;
        Challenge completed = IgnisChallengeCodec.fromRecord(record, pausedCleanup, 30);
        store.archive(IgnisChallengeCodec.history(completed, record));
        require(store.active().isEmpty() && store.history().size() == 1, "archive failed");
        CompoundTag saved = store.save(new CompoundTag(), null);
        CompoundTag history = saved.getList("History", 10).getCompound(0);
        require(!history.contains("ChapterData") && !history.contains("BlazeInventory"), "runtime state leaked into history");
        store = ChallengeStore.load(saved, null);
        require(store.active().isEmpty() && store.historical(record.competitionId).isPresent(), "history round-trip failed");
        IgnisLegacyMigration.importInto(store, List.of(record));
        require(store.active().isEmpty(), "legacy record resurrected after archive");

        CompetitionRecord oldCompleted = new CompetitionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                dimension, record.arenaCenter, record.playerKitchenOrigin, record.blazeKitchenOrigin, record.recipes);
        oldCompleted.state = CompetitionState.COMPLETED;
        oldCompleted.finalResultResolved = true;
        oldCompleted.playerTotal = 90;
        oldCompleted.blazeTotal = 70;
        IgnisLegacyMigration.importInto(store, List.of(oldCompleted));
        require(store.active().isEmpty() && store.historical(oldCompleted.competitionId).orElseThrow().result()
                == ChallengeResult.SUCCESS, "completed legacy result was not archived");
        if (args.length == 3 && args[0].equals("--migrated")) {
            verifyMigratedFile(Path.of(args[1]), Path.of(args[2]));
        } else {
            for (String file : args) verifyRealLegacyFile(Path.of(file));
        }
        System.out.println("Challenge migration checks passed");
    }

    private static void verifyMigratedFile(Path migratedPath, Path legacyPath) throws IOException {
        CompoundTag migratedRoot = NbtIo.readCompressed(migratedPath, NbtAccounter.unlimitedHeap());
        ChallengeStore store = ChallengeStore.load(migratedRoot.getCompound("data"), null);
        ListTag historyTags = migratedRoot.getCompound("data").getList("History", Tag.TAG_COMPOUND);
        for (int i = 0; i < historyTags.size(); i++) {
            require(!historyTags.getCompound(i).contains("ChapterData"), "runtime history retained chapter state");
        }
        CompoundTag legacyRoot = NbtIo.readCompressed(legacyPath, NbtAccounter.unlimitedHeap());
        ListTag oldTags = legacyRoot.getCompound("data").getList("Competitions", Tag.TAG_COMPOUND);
        List<CompetitionRecord> oldRecords = new ArrayList<>();
        for (int i = 0; i < oldTags.size(); i++) oldRecords.add(CompetitionRecord.load(oldTags.getCompound(i)));
        require(store.active().size() + store.history().size() == oldRecords.size(), "runtime migration count changed");
        for (CompetitionRecord old : oldRecords) {
            if (old.state == CompetitionState.COMPLETED) {
                ChallengeHistory historical = store.historical(old.competitionId).orElseThrow();
                require(historical.result() == IgnisChallengeCodec.fromRecord(old, null, 0).result(),
                        "completed runtime result changed");
            } else {
                Challenge challenge = store.byId(old.competitionId).orElse(null);
                if (challenge == null) {
                    ChallengeHistory historical = store.historical(old.competitionId).orElse(null);
                    require(old.state == CompetitionState.CLEANUP && historical != null,
                            "active legacy challenge disappeared during runtime migration");
                    require(historical.result() == IgnisChallengeCodec.fromRecord(old, null, 0).result(),
                            "cleanup result changed during runtime migration");
                } else {
                    require(challenge.result() == IgnisChallengeCodec.fromRecord(old, null, 0).result(),
                            "active runtime result changed");
                    CompetitionRecord restored = IgnisChallengeCodec.toRecord(challenge);
                    require(restored.playerId.equals(old.playerId) && restored.ignisId.equals(old.ignisId),
                            "runtime identity changed");
                    require(restored.state == old.state && restored.interruptedState == old.interruptedState,
                            "runtime recovery phase changed");
                    require(restored.buildCursor == old.buildCursor
                            && restored.cleanupTargetCount == old.cleanupTargetCount, "runtime build cursor changed");
                    require(restored.blazeInventory.equals(old.blazeInventory)
                            && restored.dishEvidence.size() == old.dishEvidence.size(), "runtime ledger changed");
                }
            }
        }
        int active = store.active().size();
        int history = store.history().size();
        IgnisLegacyMigration.importInto(store, oldRecords);
        require(store.active().size() == active && store.history().size() == history,
                "runtime restart would import legacy records again");
        System.out.println("Runtime SavedData verified: " + migratedPath + " (active=" + active
                + ", history=" + history + ")");
    }

    private static void verifyRealLegacyFile(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompound("data");
        ListTag tags = data.getList("Competitions", Tag.TAG_COMPOUND);
        List<CompetitionRecord> records = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) records.add(CompetitionRecord.load(tags.getCompound(i)));
        ChallengeStore store = new ChallengeStore();
        IgnisLegacyMigration.importInto(store, records);
        int active = store.active().size();
        int history = store.history().size();
        require(active + history == records.size(), "real legacy record count changed: " + path);
        store = ChallengeStore.load(store.save(new CompoundTag(), null), null);
        IgnisLegacyMigration.importInto(store, records);
        require(store.active().size() == active && store.history().size() == history,
                "real legacy file imported twice: " + path);
        for (CompetitionRecord record : records) {
            if (record.state == CompetitionState.COMPLETED) {
                require(store.historical(record.competitionId).isPresent(), "legacy history missing: " + path);
            } else {
                CompetitionRecord restored = IgnisChallengeCodec.toRecord(store.byId(record.competitionId).orElseThrow());
                CompoundTag before = record.save();
                CompoundTag after = restored.save();
                long[] beforePreserved = before.getLongArray("PreservedBattlefieldBlocks");
                long[] afterPreserved = after.getLongArray("PreservedBattlefieldBlocks");
                java.util.Arrays.sort(beforePreserved);
                java.util.Arrays.sort(afterPreserved);
                before.putLongArray("PreservedBattlefieldBlocks", beforePreserved);
                after.putLongArray("PreservedBattlefieldBlocks", afterPreserved);
                if (!after.equals(before)) {
                    List<String> changed = new ArrayList<>();
                    for (String key : before.getAllKeys()) {
                        if (!java.util.Objects.equals(before.get(key), after.get(key))) changed.add(key);
                    }
                    require(false, "legacy Ignis state changed: " + path + " fields=" + changed);
                }
            }
        }
        String phases = records.stream().map(record -> record.state + ":" + record.buildCursor)
                .collect(java.util.stream.Collectors.joining(","));
        System.out.println("Legacy NBT verified: " + path + " (active=" + active + ", history=" + history
                + ", phases=" + phases + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
