package cn.sake_yez.culinarycataclysm.challenge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Versioned public envelope. Only the owning chapter interprets chapterData. */
public final class Challenge {
    public static final int FORMAT_VERSION = 1;

    private final UUID id;
    private final ResourceLocation chapterId;
    private final ResourceLocation dimension;
    private final List<UUID> participants;
    private final ChallengeLifecycle lifecycle;
    private final ChallengeResult result;
    private final long createdAt;
    private final long startedAt;
    private final long resolvedAt;
    private final long endedAt;
    private final int chapterDataVersion;
    private final CompoundTag chapterData;

    public Challenge(UUID id, ResourceLocation chapterId, ResourceLocation dimension, List<UUID> participants,
                     ChallengeLifecycle lifecycle, ChallengeResult result, long createdAt, long startedAt,
                     long resolvedAt, long endedAt, int chapterDataVersion, CompoundTag chapterData) {
        this.id = id;
        this.chapterId = chapterId;
        this.dimension = dimension;
        this.participants = List.copyOf(participants);
        this.lifecycle = lifecycle;
        this.result = result;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.resolvedAt = resolvedAt;
        this.endedAt = endedAt;
        this.chapterDataVersion = chapterDataVersion;
        this.chapterData = chapterData.copy();
    }

    public UUID id() { return id; }
    public ResourceLocation chapterId() { return chapterId; }
    public ResourceLocation dimension() { return dimension; }
    public List<UUID> participants() { return participants; }
    public ChallengeLifecycle lifecycle() { return lifecycle; }
    public ChallengeResult result() { return result; }
    public long createdAt() { return createdAt; }
    public long startedAt() { return startedAt; }
    public long resolvedAt() { return resolvedAt; }
    public long endedAt() { return endedAt; }
    public int chapterDataVersion() { return chapterDataVersion; }
    public CompoundTag chapterData() { return chapterData.copy(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("FormatVersion", FORMAT_VERSION);
        tag.putUUID("ChallengeId", id);
        tag.putString("ChapterId", chapterId.toString());
        tag.putString("Dimension", dimension.toString());
        ListTag players = new ListTag();
        for (UUID participant : participants) {
            CompoundTag player = new CompoundTag();
            player.putUUID("Id", participant);
            players.add(player);
        }
        tag.put("Participants", players);
        tag.putString("Lifecycle", lifecycle.name());
        tag.putString("Result", result.name());
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("StartedAt", startedAt);
        tag.putLong("ResolvedAt", resolvedAt);
        tag.putLong("EndedAt", endedAt);
        tag.putInt("ChapterDataVersion", chapterDataVersion);
        tag.put("ChapterData", chapterData.copy());
        return tag;
    }

    public static Challenge load(CompoundTag tag) {
        if (tag.getInt("FormatVersion") != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported challenge format: " + tag.getInt("FormatVersion"));
        }
        ResourceLocation chapterId = ResourceLocation.tryParse(tag.getString("ChapterId"));
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (!tag.hasUUID("ChallengeId") || chapterId == null || dimension == null
                || !tag.contains("ChapterData", Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Incomplete challenge envelope");
        }
        List<UUID> participants = new ArrayList<>();
        ListTag players = tag.getList("Participants", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            if (players.getCompound(i).hasUUID("Id")) participants.add(players.getCompound(i).getUUID("Id"));
        }
        return new Challenge(tag.getUUID("ChallengeId"), chapterId, dimension, participants,
                ChallengeLifecycle.valueOf(tag.getString("Lifecycle")),
                ChallengeResult.valueOf(tag.getString("Result")),
                tag.getLong("CreatedAt"), tag.getLong("StartedAt"), tag.getLong("ResolvedAt"),
                tag.getLong("EndedAt"), tag.getInt("ChapterDataVersion"), tag.getCompound("ChapterData"));
    }
}
