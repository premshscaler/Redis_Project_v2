public class SkipListNode {
    public String playerId;
    public int score;
    public long timestamp;
    public SkipListNode[] forward;
    public int[] span;

    // Extended Redis-like fields
    public String stringValue;
    public java.util.List<String> listValues = new java.util.ArrayList<>();
    public java.util.Map<String, String> hashMap = new java.util.HashMap<>();
    public long ttlExpiry = 0; // epoch millis, 0 means no expiry
    public long lastAccessTime; // used for LRU eviction tracking

    public SkipListNode(String playerId, int score, long timestamp, int level) {
        this.playerId = playerId;
        this.score = score;
        this.timestamp = timestamp;
        this.forward = new SkipListNode[level];
        this.span = new int[level];
        this.lastAccessTime = System.currentTimeMillis();
    }
}