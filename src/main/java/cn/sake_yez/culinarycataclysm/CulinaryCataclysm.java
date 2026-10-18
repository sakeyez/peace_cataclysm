package cn.sake_yez.culinarycataclysm;

import cn.sake_yez.culinarycataclysm.chapter.ignis.IgnisEventBridge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(CulinaryCataclysm.MOD_ID)
public final class CulinaryCataclysm {
    public static final String MOD_ID = "culinary_cataclysm";

    public CulinaryCataclysm(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(new IgnisEventBridge());
    }
}
