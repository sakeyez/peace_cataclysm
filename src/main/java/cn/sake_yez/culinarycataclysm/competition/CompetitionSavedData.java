package cn.sake_yez.culinarycataclysm.competition;

import cn.sake_yez.culinarycataclysm.challenge.Challenge;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeLifecycle;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeStore;
import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisChallengeCodec;
import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisLegacyMigration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Compatibility view for the existing Ignis controllers. ChallengeStore owns all new writes. */
public final class CompetitionSavedData {
    private static final Map<MinecraftServer, CompetitionSavedData> BY_SERVER = new WeakHashMap<>();

    private final ChallengeStore store;
    private final WeakReference<MinecraftServer> server;
    private final Map<UUID, CompetitionRecord> competitions = new LinkedHashMap<>();

    private CompetitionSavedData(ServerLevel level) {
        this.store = ChallengeStore.get(level);
        this.server = new WeakReference<>(level.getServer());
        IgnisLegacyMigration.importInto(store, LegacyCompetitions.get(level).all());
        for (Challenge challenge : store.active()) {
            if (IgnisChallengeCodec.CHAPTER_ID.equals(challenge.chapterId())) {
                CompetitionRecord record = IgnisChallengeCodec.toRecord(challenge);
                competitions.put(record.competitionId, record);
            }
        }
    }

    public static synchronized CompetitionSavedData get(ServerLevel level) {
        return BY_SERVER.computeIfAbsent(level.getServer(), ignored -> new CompetitionSavedData(level));
    }

    /** Only active Ignis records are exposed to legacy tick and region queries. */
    public Collection<CompetitionRecord> all() {
        return competitions.values();
    }

    public void put(CompetitionRecord record) {
        if (store.contains(record.competitionId)) {
            throw new IllegalArgumentException("Challenge ID already exists: " + record.competitionId);
        }
        commit(record);
        competitions.put(record.competitionId, record);
    }

    public Optional<CompetitionRecord> byId(UUID competitionId) {
        return Optional.ofNullable(competitions.get(competitionId));
    }

    public Optional<CompetitionRecord> byPlayer(UUID playerId) {
        return competitions.values().stream().filter(record -> record.playerId.equals(playerId)).findFirst();
    }

    public Optional<CompetitionRecord> byIgnis(UUID ignisId) {
        return competitions.values().stream().filter(record -> record.ignisId.equals(ignisId)).findFirst();
    }

    public Optional<CompetitionRecord> at(ResourceLocation dimension, BlockPos pos) {
        return competitions.values().stream().filter(record -> record.dimension.equals(dimension)
                && record.containsArena(pos)).findFirst();
    }

    /** Existing controllers mutate records in place; this commits their current state to the new authority. */
    public void setDirty() {
        Iterator<CompetitionRecord> iterator = competitions.values().iterator();
        while (iterator.hasNext()) {
            CompetitionRecord record = iterator.next();
            commit(record);
            if (record.state == CompetitionState.COMPLETED) iterator.remove();
        }
    }

    private void commit(CompetitionRecord record) {
        long gameTime = 0;
        MinecraftServer current = server.get();
        if (current != null && current.overworld() != null) gameTime = current.overworld().getGameTime();
        Challenge challenge = IgnisChallengeCodec.fromRecord(record,
                store.byId(record.competitionId).orElse(null), gameTime);
        if (challenge.lifecycle() == ChallengeLifecycle.ENDED) {
            store.archive(IgnisChallengeCodec.history(challenge, record));
        } else {
            store.put(challenge);
        }
    }

    /** Old SavedData is read once for migration and never marked dirty or written again. */
    private static final class LegacyCompetitions extends SavedData {
        private static final String NAME = "culinary_cataclysm_competitions";
        private static final Factory<LegacyCompetitions> FACTORY = new Factory<>(
                LegacyCompetitions::new, LegacyCompetitions::load, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
        private final Map<UUID, CompetitionRecord> records = new LinkedHashMap<>();

        static LegacyCompetitions get(ServerLevel level) {
            ServerLevel storageLevel = level.getServer().getLevel(Level.OVERWORLD);
            if (storageLevel == null) storageLevel = level;
            return storageLevel.getDataStorage().computeIfAbsent(FACTORY, NAME);
        }

        Collection<CompetitionRecord> all() { return records.values(); }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (CompetitionRecord record : records.values()) list.add(record.save());
            tag.put("Competitions", list);
            return tag;
        }

        private static LegacyCompetitions load(CompoundTag tag, HolderLookup.Provider registries) {
            LegacyCompetitions data = new LegacyCompetitions();
            ListTag list = tag.getList("Competitions", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompetitionRecord record = CompetitionRecord.load(list.getCompound(i));
                data.records.put(record.competitionId, record);
            }
            return data;
        }
    }
}
