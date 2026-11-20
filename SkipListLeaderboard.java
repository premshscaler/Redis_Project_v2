import java.util.*;
import java.util.concurrent.*;

public class SkipListLeaderboard {
    private final Map<String, SkipListTier> tiers = new ConcurrentHashMap<>();
    private final Map<String, String> playerTierMap = new ConcurrentHashMap<>(); // playerId -> tier name
    private final int maxCapacity;
    private long totalHits = 0, totalMisses = 0, evictionCount = 0;

    public SkipListLeaderboard(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        tiers.put("Gold", new SkipListTier("Gold"));
        tiers.put("Silver", new SkipListTier("Silver"));
        tiers.put("Bronze", new SkipListTier("Bronze"));

        // Background sweep mirrors Redis active expiry
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this.activeExpirySweep, 5, 5, TimeUnit.SECONDS);
    }

    public String determineTier(int score) {
        if (score >= 3000) return "Gold";
        if (score >= 1500) return "Silver";
        return "Bronze";
    }

    public synchronized void zadd(String playerId, int score) {
        checkCapacityBeforeInsert();
        String newTier = determineTier(score);
        String oldTier = playerTierMap.get(playerId);
        if (oldTier != null && !oldTier.equals(newTier)) {
            tiers.get(oldTier).zrem(playerId);
        }
        playerTierMap.put(playerId, newTier);
        tiers.get(newTier).zadd(playerId, score);
    }

    public synchronized void zrem(String playerId) {
        String tier = playerTierMap.remove(playerId);
        if (tier != null) {
            tiers.get(tier).zrem(playerId);
        }
    }

    public synchronized Map<String, Object> getPlayerInfo(String playerId) {
        String tier = playerTierMap.get(playerId);
        if (tier == null) {
            totalMisses++;
            return null;
        }
        SkipListTier skipListTier = tiers.get(tier);
        SkipListNode node = skipListTier.playerMap.get(playerId);

        // Passive expiry check on access
        if (node != null && node.ttlExpiry > 0 && System.currentTimeMillis() > node.ttlExpiry) {
            zrem(playerId);
            totalMisses++;
            return null;
        }
        if (node == null) {
            totalMisses++;
            return null;
        }

        totalHits++;
        node.lastAccessTime = System.currentTimeMillis(); // LRU timestamp

        int rank = skipListTier.zrank(playerId);
        int totalInTier = skipListTier.size;
        int percentile = totalInTier <= 1
                ? 100
                : Math.round((1.0f - (rank - 1.0f) / totalInTier) * 100);

        Map<String, Object> info = new HashMap<>();
        info.put("playerId", playerId);
        info.put("tier", tier);
        info.put("score", node.score);
        info.put("rank", rank);
        info.put("percentile", percentile);
        info.put("stringValue", node.stringValue);
        info.put("listValues", node.listValues);
        info.put("hashMap", node.hashMap);
        return info;
    }

    public synchronized void setExpire(String playerId, long seconds) {
        String tier = playerTierMap.get(playerId);
        if (tier != null) {
            SkipListNode node = tiers.get(tier).playerMap.get(playerId);
            if (node != null) {
                node.ttlExpiry = System.currentTimeMillis() + (seconds * 1000);
            }
        }
    }

    public synchronized void setString(String playerId, String val) {
        ensureNodeExists(playerId);
        String tier = playerTierMap.get(playerId);
        SkipListNode node = tiers.get(tier).playerMap.get(playerId);
        node.stringValue = val;
    }

    public synchronized void lpush(String playerId, String val) {
        ensureNodeExists(playerId);
        String tier = playerTierMap.get(playerId);
        SkipListNode node = tiers.get(tier).playerMap.get(playerId);
        node.listValues.add(0, val);
    }

    public synchronized void hset(String playerId, String field, String val) {
        ensureNodeExists(playerId);
        String tier = playerTierMap.get(playerId);
        SkipListNode node = tiers.get(tier).playerMap.get(playerId);
        node.hashMap.put(field, val);
    }

    private void ensureNodeExists(String playerId) {
        if (!playerTierMap.containsKey(playerId)) {
            zadd(playerId, 1000); // default Bronze score
        }
    }

    private void checkCapacityBeforeInsert() {
        if (playerTierMap.size() >= maxCapacity) {
            // Evict LRU node across tiers
            long oldest = Long.MAX_VALUE;
            String victimId = null;
            String victimTier = null;
            for (Map.Entry<String, SkipListTier> tierEntry : tiers.entrySet()) {
                for (SkipListNode node : tierEntry.getValue().playerMap.values()) {
                    if (node.lastAccessTime < oldest) {
                        oldest = node.lastAccessTime;
                        victimId = node.playerId;
                        victimTier = tierEntry.getKey();
                    }
                }
            }
            if (victimId != null) {
                tiers.get(victimTier).zrem(victimId);
                playerTierMap.remove(victimId);
                evictionCount++;
            }
        }
    }

    private final Runnable activeExpirySweep = () -> {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : playerTierMap.entrySet()) {
            String pid = entry.getKey();
            String tier = entry.getValue();
            SkipListNode node = tiers.get(tier).playerMap.get(pid);
            if (node != null && node.ttlExpiry > 0 && now > node.ttlExpiry) {
                zrem(pid);
                evictionCount++;
            }
        }
    };

    public Map<String, Object> getCacheStats() {
        long totalAccess = totalHits + totalMisses;
        double hitRate = totalAccess == 0 ? 0.0 : Math.round(((double) totalHits / totalAccess * 100.0) * 100.0) / 100.0;
        double missRate = totalAccess == 0 ? 0.0 : Math.round(((double) totalMisses / totalAccess * 100.0) * 100.0) / 100.0;
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalHits", totalHits);
        stats.put("totalMisses", totalMisses);
        stats.put("hitRate", hitRate);
        stats.put("missRate", missRate);
        stats.put("evictionCount", evictionCount);
        stats.put("currentKeyCount", playerTierMap.size());
        stats.put("maxCapacity", maxCapacity);
        return stats;
    }

    public Map<String, List<Map<String, Object>>> getAllTiersTop(int limit) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("Gold", tiers.get("Gold").zrange(0, limit - 1));
        result.put("Silver", tiers.get("Silver").zrange(0, limit - 1));
        result.put("Bronze", tiers.get("Bronze").zrange(0, limit - 1));
        return result;
    }

    // ---------------------------------------------------------------
    // Span-based skip list: descending score, ties -> earlier timestamp first
    // ---------------------------------------------------------------
    public static class SkipListTier {
        public final String tierName;
        private static final int MAX_LEVEL = 16;
        private static final double P = 0.5;
        private final Random random = new Random();
        private final SkipListNode head = new SkipListNode(null, 0, 0, MAX_LEVEL);
        public final Map<String, SkipListNode> playerMap = new HashMap<>();
        public int size = 0;
        private int currentMaxLevel = 1;

        public SkipListTier(String tierName) {
            this.tierName = tierName;
        }

        private int randomLevel() {
            int lvl = 1;
            while (random.nextDouble() < P && lvl < MAX_LEVEL) {
                lvl++;
            }
            return lvl;
        }

        public synchronized void zadd(String playerId, int score) {
            if (playerMap.containsKey(playerId)) {
                zrem(playerId);
            }
            int newLevel = randomLevel();
            if (newLevel > currentMaxLevel) {
                currentMaxLevel = newLevel;
            }
            SkipListNode newNode = new SkipListNode(playerId, score, System.currentTimeMillis(), newLevel);
            SkipListNode[] update = new SkipListNode[MAX_LEVEL];
            int[] rankCount = new int[MAX_LEVEL];

            SkipListNode curr = head;
            for (int i = currentMaxLevel - 1; i >= 0; i--) {
                rankCount[i] = (i == currentMaxLevel - 1) ? 0 : rankCount[i + 1];
                while (curr.forward[i] != null
                        && (curr.forward[i].score > score
                        || (curr.forward[i].score == score && curr.forward[i].timestamp < newNode.timestamp))) {
                    rankCount[i] += curr.span[i];
                    curr = curr.forward[i];
                }
                update[i] = curr;
            }

            for (int i = 0; i < newLevel; i++) {
                newNode.forward[i] = update[i].forward[i];
                update[i].forward[i] = newNode;
                newNode.span[i] = update[i].span[i] - (rankCount[0] - rankCount[i]);
                update[i].span[i] = (rankCount[0] - rankCount[i]) + 1;
            }
            for (int i = newLevel; i < currentMaxLevel; i++) {
                update[i].span[i]++;
            }

            playerMap.put(playerId, newNode);
            size++;
        }

        public synchronized void zrem(String playerId) {
            SkipListNode target = playerMap.remove(playerId);
            if (target == null) {
                return;
            }
            SkipListNode[] update = new SkipListNode[MAX_LEVEL];
            SkipListNode curr = head;
            for (int i = currentMaxLevel - 1; i >= 0; i--) {
                while (curr.forward[i] != null
                        && (curr.forward[i].score > target.score
                        || (curr.forward[i].score == target.score && curr.forward[i].timestamp <= target.timestamp))
                        && !curr.forward[i].playerId.equals(playerId)) {
                    curr = curr.forward[i];
                }
                update[i] = curr;
            }
            for (int i = 0; i < currentMaxLevel; i++) {
                if (update[i].forward[i] != null && update[i].forward[i].playerId.equals(playerId)) {
                    update[i].span[i] += update[i].forward[i].span[i] - 1;
                    update[i].forward[i] = update[i].forward[i].forward[i];
                } else {
                    update[i].span[i]--;
                }
            }
            while (currentMaxLevel > 1 && head.forward[currentMaxLevel - 1] == null) {
                currentMaxLevel--;
            }
            size--;
        }

        public synchronized int zrank(String playerId) {
            SkipListNode curr = head;
            int rank = 0;
            for (int i = currentMaxLevel - 1; i >= 0; i--) {
                while (curr.forward[i] != null) {
                    if (curr.forward[i].playerId.equals(playerId)) {
                        rank += curr.span[i];
                        return rank;
                    }
                    if (curr.forward[i].score > playerMap.get(playerId).score
                            || (curr.forward[i].score == playerMap.get(playerId).score
                            && curr.forward[i].timestamp <= playerMap.get(playerId).timestamp)) {
                        rank += curr.span[i];
                        curr = curr.forward[i];
                    } else {
                        break;
                    }
                }
            }
            return -1;
        }

        public List<Map<String, Object>> zrange(int start, int end) {
            List<Map<String, Object>> result = new ArrayList<>();
            SkipListNode curr = head.forward[0];
            int idx = 0;
            while (curr != null) {
                if (idx >= start && idx <= end) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("rank", idx + 1);
                    entry.put("playerId", curr.playerId);
                    entry.put("score", curr.score);
                    entry.put("stringValue", curr.stringValue);
                    result.add(entry);
                }
                curr = curr.forward[0];
                idx++;
            }
            return result;
        }
    }
}