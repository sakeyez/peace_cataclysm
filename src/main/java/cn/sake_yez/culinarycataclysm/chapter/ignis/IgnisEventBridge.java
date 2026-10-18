package cn.sake_yez.culinarycataclysm.chapter.ignis;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge 入口；Ignis 的玩法和状态调度由章节持有。 */
public final class IgnisEventBridge {
    private final IgnisChapter ignis = new IgnisChapter();

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ignis.onRegisterCommands(event);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ignis.onServerTick(event);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ignis.onRightClickBlock(event);
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ignis.onEntityInteract(event);
    }

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        ignis.onIncomingDamage(event);
    }

    @SubscribeEvent
    public void onMobGriefing(EntityMobGriefingEvent event) {
        ignis.onMobGriefing(event);
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        ignis.onEntityJoin(event);
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        ignis.onBreak(event);
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        ignis.onPlace(event);
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        ignis.onExplosion(event);
    }

    @SubscribeEvent
    public void onPiston(PistonEvent.Pre event) {
        ignis.onPiston(event);
    }

    @SubscribeEvent
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        ignis.onFluidPlace(event);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ignis.onLogout(event);
    }
}
