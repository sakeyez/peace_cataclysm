package cn.sake_yez.culinarycataclysm.challenge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/** Compact fact record; no running chapter state is retained here. */
public final class ChallengeHistory {
    private final Challenge challenge;
    private final CompoundTag chapterSummary;

    public ChallengeHistory(Challenge challenge, CompoundTag chapterSummary) {
        if (challenge.lifecycle() != ChallengeLifecycle.ENDED) {
            throw new IllegalArgumentException("History requires an ended challenge");
        }
        this.challenge = new Challenge(challenge.id(), challenge.chapterId(), challenge.dimension(),
                challenge.participants(), challenge.lifecycle(), challenge.result(), challenge.createdAt(),
                challenge.startedAt(), challenge.resolvedAt(), challenge.endedAt(), 0, new CompoundTag());
        this.chapterSummary = chapterSummary.copy();
    }

    public UUID id() { return challenge.id(); }
    public ResourceLocation chapterId() { return challenge.chapterId(); }
    public ResourceLocation dimension() { return challenge.dimension(); }
    public List<UUID> participants() { return challenge.participants(); }
    public ChallengeResult result() { return challenge.result(); }
    public CompoundTag chapterSummary() { return chapterSummary.copy(); }

    public CompoundTag save() {
        CompoundTag tag = challenge.save();
        tag.remove("ChapterData");
        tag.remove("ChapterDataVersion");
        tag.put("ChapterSummary", chapterSummary.copy());
        return tag;
    }

    public static ChallengeHistory load(CompoundTag tag) {
        CompoundTag envelope = tag.copy();
        envelope.put("ChapterData", new CompoundTag());
        envelope.putInt("ChapterDataVersion", 0);
        return new ChallengeHistory(Challenge.load(envelope), tag.getCompound("ChapterSummary"));
    }
}
