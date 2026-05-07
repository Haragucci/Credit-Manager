package op.creditmanager.client.model;

import java.util.UUID;

public class TransactionEntry {

    private UUID id;
    private String fromPlayer;
    private String toPlayer;
    private double amount;
    private long timestamp;

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
}