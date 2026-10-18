package cn.sake_yez.culinarycataclysm.challenge;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Rebuildable lookup keys for active challenges; never serialized or authoritative. */
public final class ActiveChallengeIndex {
    private final Map<UUID, UUID> byPlayer = new HashMap<>();
    private final Map<UUID, UUID> byBoss = new HashMap<>();
    private final Map<UUID, UUID> bossByChallenge = new HashMap<>();

    public Optional<UUID> byPlayer(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public Optional<UUID> byBoss(UUID bossId) {
        return Optional.ofNullable(byBoss.get(bossId));
    }

    void rebuild(Collection<Challenge> active) {
        byPlayer.clear();
        byBoss.clear();
        bossByChallenge.clear();
        for (Challenge challenge : active) addPlayers(challenge);
    }

    void replace(Challenge previous, Challenge challenge) {
        checkPlayers(challenge);
        if (previous != null) removePlayers(previous);
        addPlayers(challenge);
    }

    void remove(Challenge challenge) {
        removePlayers(challenge);
        UUID bossId = bossByChallenge.remove(challenge.id());
        if (bossId != null) byBoss.remove(bossId, challenge.id());
    }

    void associateBoss(UUID challengeId, UUID bossId) {
        UUID owner = byBoss.get(bossId);
        if (owner != null && !owner.equals(challengeId)) {
            throw new IllegalArgumentException("Boss already belongs to active challenge " + owner);
        }
        UUID previous = bossByChallenge.put(challengeId, bossId);
        if (previous != null && !previous.equals(bossId)) byBoss.remove(previous, challengeId);
        byBoss.put(bossId, challengeId);
    }

    private void addPlayers(Challenge challenge) {
        checkPlayers(challenge);
        for (UUID playerId : challenge.participants()) byPlayer.put(playerId, challenge.id());
    }

    private void checkPlayers(Challenge challenge) {
        for (UUID playerId : challenge.participants()) {
            UUID owner = byPlayer.get(playerId);
            if (owner != null && !owner.equals(challenge.id())) {
                throw new IllegalArgumentException("Player already belongs to active challenge " + owner);
            }
        }
    }

    private void removePlayers(Challenge challenge) {
        for (UUID playerId : challenge.participants()) byPlayer.remove(playerId, challenge.id());
    }
}
