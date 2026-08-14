package op.creditmanager.client.model;

import op.creditmanager.client.money.MoneyRules;

import java.util.UUID;

public class TransactionEntry {

    private UUID id;
    private String fromPlayer;
    private String toPlayer;
    private long amountMinor;
    private long timestamp;
    private String rawText;
    private String normalizedText;
    private String source;
    private String hash;
    private String metadata;
    private long linkedAmountMinor;

    public TransactionEntry() {}

    public TransactionEntry(String fromPlayer, String toPlayer, long amountMinor) {
        this.id = UUID.randomUUID();
        this.fromPlayer = fromPlayer;
        this.toPlayer = toPlayer;
        this.amountMinor = amountMinor;
        this.timestamp = System.currentTimeMillis();
    }

    @Deprecated
    public TransactionEntry(String fromPlayer, String toPlayer, double amount) {
        this(fromPlayer, toPlayer, MoneyRules.fromLegacyDouble(amount, true).minorUnits());
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getFromPlayer() { return fromPlayer; }
    public void setFromPlayer(String fromPlayer) { this.fromPlayer = fromPlayer; }

    public String getToPlayer() { return toPlayer; }
    public void setToPlayer(String toPlayer) { this.toPlayer = toPlayer; }

    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    @Deprecated public double getAmount() { return MoneyRules.toDisplayDouble(amountMinor); }
    @Deprecated public void setAmount(double amount) { this.amountMinor = MoneyRules.fromLegacyDouble(amount, true).minorUnits(); }

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

    public long getLinkedAmountMinor() { return linkedAmountMinor; }
    public void setLinkedAmountMinor(long linkedAmountMinor) { this.linkedAmountMinor = Math.max(0L, linkedAmountMinor); }
    public long getRemainingAmountMinor() { return Math.max(0L, Math.subtractExact(amountMinor, linkedAmountMinor)); }
    public boolean isFullyLinked() { return getRemainingAmountMinor() == 0L; }
    @Deprecated public double getLinkedAmount() { return MoneyRules.toDisplayDouble(linkedAmountMinor); }
    @Deprecated public void setLinkedAmount(double linkedAmount) { this.linkedAmountMinor = MoneyRules.fromLegacyDouble(Math.max(0D, linkedAmount), false).minorUnits(); }
    @Deprecated public double getRemainingAmount() { return MoneyRules.toDisplayDouble(getRemainingAmountMinor()); }
}
