package op.creditmanager.client.model;

import java.util.UUID;

public class TransactionEntry {

    private UUID id;
    private String fromPlayer;
    private String toPlayer;
    private double amount;
    private long timestamp;
    private String rawText;
    private String normalizedText;
    private String source;
    private String hash;
    private String metadata;
    private double linkedAmount;

    public TransactionEntry() {}

    public TransactionEntry(String fromPlayer, String toPlayer, double amount) {
        this.id = UUID.randomUUID();
        this.fromPlayer = fromPlayer;
        this.toPlayer = toPlayer;
        this.amount = amount;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getFromPlayer() { return fromPlayer; }
    public void setFromPlayer(String fromPlayer) { this.fromPlayer = fromPlayer; }

    public String getToPlayer() { return toPlayer; }
    public void setToPlayer(String toPlayer) { this.toPlayer = toPlayer; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getNormalizedText() { return normalizedText; }
    public void setNormalizedText(String normalizedText) { this.normalizedText = normalizedText; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public double getLinkedAmount() { return linkedAmount; }
    public void setLinkedAmount(double linkedAmount) { this.linkedAmount = Math.max(0.0D, linkedAmount); }
    public double getRemainingAmount() { return Math.max(0.0D, amount - linkedAmount); }
    public boolean isFullyLinked() { return getRemainingAmount() <= 0.0001D; }
}
