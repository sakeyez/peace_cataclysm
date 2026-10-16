package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.boss.IgnisPeaceController;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;

public final class IgnisDependencies {
    private IgnisDependencies() {
    }

    public static boolean requiredRegistriesPresent() {
        return ModList.get().isLoaded("cataclysm")
                && ModList.get().isLoaded("create")
                && ModList.get().isLoaded("kaleidoscope_cookery")
                && BuiltInRegistries.ENTITY_TYPE.containsKey(IgnisPeaceController.IGNIS)
                && BuiltInRegistries.BLOCK.containsKey(CookeryIntegration.POT)
                && BuiltInRegistries.BLOCK.containsKey(CookeryIntegration.STOCKPOT)
                && BuiltInRegistries.BLOCK.containsKey(CookeryIntegration.STOVE)
                && BuiltInRegistries.BLOCK.containsKey(CookeryIntegration.CHOPPING_BOARD)
                && BuiltInRegistries.ITEM.containsKey(CookeryIntegration.OIL)
                && BuiltInRegistries.ITEM.containsKey(CookeryIntegration.KITCHEN_SHOVEL)
                && BuiltInRegistries.ITEM.containsKey(CookeryIntegration.STOCKPOT_LID);
    }
}
