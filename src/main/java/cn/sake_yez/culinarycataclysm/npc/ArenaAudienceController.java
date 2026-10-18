package cn.sake_yez.culinarycataclysm.npc;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Keeps competition-owned villagers on the arena seating tiers. */
public final class ArenaAudienceController {
    private static final String AUDIENCE_KEY = "CulinaryCataclysmAudience";
    private static final String COMPETITION_KEY = "CulinaryCataclysmCompetition";
    private static final String SEAT_KEY = "CulinaryCataclysmSeat";
    // guanzhong.nbt: the altar is (36, 3, 33), and the battlefield origin is (26, 1, 9).
    // The 26 grass blocks mark the middle tier. Extra seats use the existing lower and upper tiers.
    // This schematic is only a coordinate reference: no grass or other blocks are placed here.
    private static final List<Seat> SEATS = List.of(
            // Grass markers on the middle tier (x, floor y, z in the reference schematic).
            new Seat(24, 12, 5), new Seat(48, 12, 5),
            new Seat(18, 12, 7), new Seat(55, 12, 8),
            new Seat(16, 12, 9), new Seat(12, 12, 13), new Seat(11, 12, 14),
            new Seat(60, 12, 14), new Seat(61, 12, 15),
            new Seat(63, 12, 19), new Seat(8, 12, 20), new Seat(64, 12, 20),
            new Seat(64, 12, 21), new Seat(65, 12, 26),
            new Seat(64, 12, 45), new Seat(8, 12, 46), new Seat(63, 12, 46),
            new Seat(61, 12, 51), new Seat(11, 12, 52), new Seat(60, 12, 53),
            new Seat(16, 12, 57), new Seat(56, 12, 57),
            new Seat(18, 12, 58), new Seat(54, 12, 58),
            new Seat(23, 12, 61), new Seat(48, 12, 61),
            // Spread a smaller crowd across the lower seating tier.
            new Seat(23, 5, 5), new Seat(49, 5, 5),
            new Seat(17, 5, 7), new Seat(56, 5, 8),
            new Seat(11, 5, 13), new Seat(61, 5, 14),
            new Seat(7, 5, 20), new Seat(65, 5, 20),
            new Seat(7, 5, 46), new Seat(65, 5, 45),
            new Seat(10, 5, 52), new Seat(62, 5, 51),
            new Seat(22, 5, 61), new Seat(49, 5, 61),
            // Sparse spectators on the upper tier and its corner landings.
            new Seat(24, 18, 5), new Seat(48, 18, 5),
            new Seat(17, 18, 9), new Seat(55, 18, 9),
            new Seat(7, 18, 26), new Seat(65, 18, 26),
            new Seat(8, 18, 45), new Seat(61, 18, 51),
            new Seat(12, 18, 52), new Seat(54, 18, 58));

    private ArenaAudienceController() {
    }

    public static boolean isAudience(Entity entity) {
        return entity instanceof Villager
                && entity.getPersistentData().getBoolean(AUDIENCE_KEY)
                && entity.getPersistentData().hasUUID(COMPETITION_KEY);
    }

    public static void maintain(ServerLevel level, CompetitionRecord record) {
        boolean[] occupied = new boolean[SEATS.size()];
        for (Villager villager : audience(level, record)) {
            int seat = villager.getPersistentData().getInt(SEAT_KEY);
            if (seat < 0 || seat >= occupied.length || occupied[seat]) {
                villager.discard();
            } else {
                Seat position = SEATS.get(seat);
                BlockPos feet = feet(record, position);
                if (!canStand(level, feet)) {
                    villager.discard();
                    continue;
                }
                occupied[seat] = true;
                if (villager.distanceToSqr(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5) > 0.25) {
                    villager.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                            position.yaw(), 0);
                }
                villager.setYHeadRot(position.yaw());
                villager.setYBodyRot(position.yaw());
            }
        }
        for (int i = 0; i < SEATS.size(); i++) {
            if (occupied[i]) continue;
            Seat seat = SEATS.get(i);
            BlockPos feet = feet(record, seat);
            if (!canStand(level, feet)) continue;
            Villager villager = EntityType.VILLAGER.create(level);
            if (villager == null) continue;
            villager.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, seat.yaw(), 0);
            villager.setYHeadRot(seat.yaw());
            villager.setYBodyRot(seat.yaw());
            villager.setNoAi(true);
            villager.setInvulnerable(true);
            villager.setPersistenceRequired();
            villager.getPersistentData().putBoolean(AUDIENCE_KEY, true);
            villager.getPersistentData().putUUID(COMPETITION_KEY, record.competitionId);
            villager.getPersistentData().putInt(SEAT_KEY, i);
            if (level.noCollision(villager)) level.addFreshEntity(villager);
        }
    }

    public static void cleanup(ServerLevel level, CompetitionRecord record) {
        for (Villager villager : audience(level, record)) villager.discard();
    }

    private static List<Villager> audience(ServerLevel level, CompetitionRecord record) {
        AABB bounds = new AABB(record.battlefieldOrigin.offset(-23, 2, -8))
                .expandTowards(66, 20, 64);
        return level.getEntitiesOfClass(Villager.class, bounds,
                villager -> isAudience(villager)
                        && villager.getPersistentData().getUUID(COMPETITION_KEY).equals(record.competitionId));
    }

    private static BlockPos feet(CompetitionRecord record, Seat seat) {
        // The template's origin is two blocks below the altar; one block above the floor cancels that y offset.
        return record.battlefieldOrigin.offset(seat.x() - 26, seat.floorY(), seat.z() - 9);
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        BlockPos floor = feet.below();
        return level.isInWorldBounds(feet) && level.hasChunkAt(feet)
                && !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                && level.getFluidState(floor).isEmpty()
                && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir();
    }

    private record Seat(int x, int floorY, int z) {
        private float yaw() {
            return (float) Math.toDegrees(Math.atan2(x - 36, 33 - z));
        }
    }
}
