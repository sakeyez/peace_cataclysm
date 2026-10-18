package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.challenge.Challenge;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeLifecycle;
import cn.sake_yez.culinarycataclysm.challenge.ChallengeStore;
import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/** Mutable Ignis runtime records backed by the single authoritative ChallengeStore. */
public final class IgnisChallengeRegistry {
    private static final Map<MinecraftServer, IgnisChallengeRegistry> BY_SERVER = new WeakHashMap<>();

    private final ChallengeStore store;
    private final LongSupplier gameTime;
    private final Map<UUID, CompetitionRecord> active = new LinkedHashMap<>();

    private IgnisChallengeRegistry(ServerLevel level) {
        this(ChallengeStore.get(level), serverClock(level.getServer()));
    }

    IgnisChallengeRegistry(ChallengeStore store, LongSupplier gameTime) {
        this.store = store;
        this.gameTime = gameTime;
        for (Challenge challenge : store.active()) {
            if (!IgnisChallengeCodec.CHAPTER_ID.equals(challenge.chapterId())) continue;
            CompetitionRecord record = IgnisChallengeCodec.toRecord(challenge);
            active.put(record.competitionId, record);
            store.associateBoss(record.competitionId, record.ignisId);
        }
    }

    public static synchronized IgnisChallengeRegistry get(ServerLevel level) {
        return BY_SERVER.computeIfAbsent(level.getServer(), ignored -> new IgnisChallengeRegistry(level));
    }

    private static LongSupplier serverClock(MinecraftServer server) {
        WeakReference<MinecraftServer> reference = new WeakReference<>(server);
        return () -> {
            MinecraftServer current = reference.get();
            return current == null || current.overworld() == null ? 0 : current.overworld().getGameTime();
        };
    }

    public Collection<CompetitionRecord> all() {
        return active.values();
    }

    public void put(CompetitionRecord record) {
        if (store.contains(record.competitionId)) {
            throw new IllegalArgumentException("Challenge ID already exists: " + record.competitionId);
        }
        if (store.byBoss(record.ignisId).isPresent()) {
            throw new IllegalArgumentException("Boss already belongs to an active challenge " + record.ignisId);
        }
        commit(record);
        active.put(record.competitionId, record);
    }

    public Optional<CompetitionRecord> byId(UUID challengeId) {
        return Optional.ofNullable(active.get(challengeId));
    }

    public Optional<CompetitionRecord> byPlayer(UUID playerId) {
        return store.byPlayer(playerId).flatMap(challenge -> byId(challenge.id()));
    }

    public Optional<CompetitionRecord> byIgnis(UUID ignisId) {
        return store.byBoss(ignisId).flatMap(challenge -> byId(challenge.id()));
    }

    public Optional<CompetitionRecord> at(ResourceLocation dimension, BlockPos pos) {
        return active.values().stream().filter(record -> record.dimension.equals(dimension)
                && record.containsArena(pos)).findFirst();
    }

    /** Commit one changed record; completed challenges leave the runtime cache after archiving. */
    public void changed(CompetitionRecord record) {
        if (active.get(record.competitionId) != record) {
            throw new IllegalArgumentException("Ignis challenge is not active: " + record.competitionId);
        }
        commit(record);
        if (record.state == CompetitionState.COMPLETED) active.remove(record.competitionId);
    }

    private void commit(CompetitionRecord record) {
        Challenge challenge = IgnisChallengeCodec.fromRecord(record,
                store.byId(record.competitionId).orElse(null), gameTime.getAsLong());
        if (challenge.lifecycle() == ChallengeLifecycle.ENDED) {
            store.archive(IgnisChallengeCodec.history(challenge, record));
        } else {
            store.put(challenge);
            store.associateBoss(challenge.id(), record.ignisId);
        }
    }
}
