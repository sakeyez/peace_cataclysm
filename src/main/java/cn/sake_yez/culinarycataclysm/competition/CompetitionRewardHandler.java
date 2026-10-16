package cn.sake_yez.culinarycataclysm.competition;

import cn.sake_yez.culinarycataclysm.CulinaryCataclysm;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class CompetitionRewardHandler {
    private static final ResourceLocation RECOGNITION = ResourceLocation.fromNamespaceAndPath(
            CulinaryCataclysm.MOD_ID, "ignis_recognition");

    private CompetitionRewardHandler() {
    }

    public static void grant(ServerPlayer player, CompetitionRecord record) {
        if (record.rewardIssued) return;
        player.getPersistentData().putBoolean("CulinaryCataclysmIgnisRecognition", true);
        player.addTag("culinary_cataclysm_ignis_recognized");
        AdvancementHolder advancement = player.serverLevel().getServer().getAdvancements().get(RECOGNITION);
        if (advancement != null) player.getAdvancements().award(advancement, "won_competition");
        record.rewardIssued = true;
    }
}
