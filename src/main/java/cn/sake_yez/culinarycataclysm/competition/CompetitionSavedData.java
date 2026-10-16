package cn.sake_yez.culinarycataclysm.competition;

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

public final class CompetitionSavedData extends SavedData {
    private static final String NAME = "culinary_cataclysm_competitions";
    private static final Factory<CompetitionSavedData> FACTORY = new Factory<>(
            CompetitionSavedData::new,
            CompetitionSavedData::load,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, CompetitionRecord> competitions = new LinkedHashMap<>();

    public static CompetitionSavedData get(ServerLevel level) {
        ServerLevel storageLevel = level.getServer().getLevel(Level.OVERWORLD);
        if (storageLevel == null) storageLevel = level;
        return storageLevel.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Collection<CompetitionRecord> all() {
        return competitions.values();
    }

    public void put(CompetitionRecord record) {
        competitions.put(record.competitionId, record);
        setDirty();
    }

    public Optional<CompetitionRecord> byId(UUID competitionId) {
        return Optional.ofNullable(competitions.get(competitionId));
    }

    public Optional<CompetitionRecord> byPlayer(UUID playerId) {
        return competitions.values().stream().filter(record -> record.playerId.equals(playerId)
                && record.state != CompetitionState.COMPLETED).findFirst();
    }

    public Optional<CompetitionRecord> byIgnis(UUID ignisId) {
        return competitions.values().stream().filter(record -> record.ignisId.equals(ignisId)
                && record.state != CompetitionState.COMPLETED).findFirst();
    }

    public Optional<CompetitionRecord> at(net.minecraft.resources.ResourceLocation dimension, net.minecraft.core.BlockPos pos) {
        return competitions.values().stream().filter(record -> record.dimension.equals(dimension)
                && record.state != CompetitionState.COMPLETED && record.containsArena(pos)).findFirst();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (CompetitionRecord record : competitions.values()) list.add(record.save());
        tag.put("Competitions", list);
        return tag;
    }

    private static CompetitionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CompetitionSavedData data = new CompetitionSavedData();
        ListTag list = tag.getList("Competitions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompetitionRecord record = CompetitionRecord.load(list.getCompound(i));
            data.competitions.put(record.competitionId, record);
        }
        return data;
    }
}
