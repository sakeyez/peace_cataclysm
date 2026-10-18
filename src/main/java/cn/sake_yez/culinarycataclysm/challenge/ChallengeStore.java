package cn.sake_yez.culinarycataclysm.challenge;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The single writable SavedData for active challenges and compact history. */
public final class ChallengeStore extends SavedData {
    private static final String NAME = "culinary_cataclysm_challenges";
    private static final int STORE_FORMAT_VERSION = 1;
    private static final Factory<ChallengeStore> FACTORY = new Factory<>(
            ChallengeStore::new, ChallengeStore::load, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Challenge> active = new LinkedHashMap<>();
    private final Map<UUID, ChallengeHistory> history = new LinkedHashMap<>();
    private final ActiveChallengeIndex index = new ActiveChallengeIndex();

    public static ChallengeStore get(ServerLevel level) {
        ServerLevel storageLevel = level.getServer().getLevel(Level.OVERWORLD);
        if (storageLevel == null) storageLevel = level;
        return storageLevel.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Collection<Challenge> active() { return java.util.List.copyOf(active.values()); }
    public Collection<ChallengeHistory> history() { return java.util.List.copyOf(history.values()); }
    public Optional<Challenge> byId(UUID id) { return Optional.ofNullable(active.get(id)); }
    public Optional<Challenge> byPlayer(UUID playerId) { return index.byPlayer(playerId).flatMap(this::byId); }
    public Optional<Challenge> byBoss(UUID bossId) { return index.byBoss(bossId).flatMap(this::byId); }
    public Optional<ChallengeHistory> historical(UUID id) { return Optional.ofNullable(history.get(id)); }
    public boolean contains(UUID id) { return active.containsKey(id) || history.containsKey(id); }

    public void put(Challenge challenge) {
        if (challenge.lifecycle() == ChallengeLifecycle.ENDED || history.containsKey(challenge.id())) {
            throw new IllegalArgumentException("Cannot activate ended or archived challenge " + challenge.id());
        }
        index.replace(active.get(challenge.id()), challenge);
        active.put(challenge.id(), challenge);
        setDirty();
    }

    /** Chapters replay their persisted Boss associations after the active records are loaded. */
    public void associateBoss(UUID challengeId, UUID bossId) {
        if (!active.containsKey(challengeId)) {
            throw new IllegalArgumentException("Boss association has no active challenge " + challengeId);
        }
        index.associateBoss(challengeId, bossId);
    }

    public void archive(ChallengeHistory record) {
        Challenge removed = active.remove(record.id());
        if (removed != null) index.remove(removed);
        history.put(record.id(), record);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("StoreFormatVersion", STORE_FORMAT_VERSION);
        ListTag activeTags = new ListTag();
        for (Challenge challenge : active.values()) activeTags.add(challenge.save());
        tag.put("Active", activeTags);
        ListTag historyTags = new ListTag();
        for (ChallengeHistory record : history.values()) historyTags.add(record.save());
        tag.put("History", historyTags);
        return tag;
    }

    static ChallengeStore load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("StoreFormatVersion") != STORE_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported challenge store format: " + tag.getInt("StoreFormatVersion"));
        }
        ChallengeStore store = new ChallengeStore();
        ListTag activeTags = tag.getList("Active", Tag.TAG_COMPOUND);
        for (int i = 0; i < activeTags.size(); i++) {
            Challenge challenge = Challenge.load(activeTags.getCompound(i));
            if (challenge.lifecycle() == ChallengeLifecycle.ENDED || store.active.putIfAbsent(challenge.id(), challenge) != null) {
                throw new IllegalStateException("Invalid active challenge " + challenge.id());
            }
        }
        ListTag historyTags = tag.getList("History", Tag.TAG_COMPOUND);
        for (int i = 0; i < historyTags.size(); i++) {
            ChallengeHistory record = ChallengeHistory.load(historyTags.getCompound(i));
            if (store.active.containsKey(record.id()) || store.history.putIfAbsent(record.id(), record) != null) {
                throw new IllegalStateException("Duplicate challenge history " + record.id());
            }
        }
        store.index.rebuild(store.active.values());
        return store;
    }
}
