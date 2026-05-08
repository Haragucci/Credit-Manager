package op.creditmanager.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Payment {

    private UUID id;
    private UUID creditId;
    private String fromPlayer;
    private String toPlayer;
    private Double amount;
    private List<String> items;
    private String itemNbt;
    private long timestamp;
    private String source;

    public Payment(UUID creditId, String fromPlayer, String toPlayer,
                   Double amount, List<String> items, String source) {
        this.id = UUID.randomUUID();
        this.creditId = creditId;
        this.fromPlayer = fromPlayer;
        this.toPlayer = toPlayer;
        this.amount = amount;
        this.items = items != null ? items : new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
        this.source = source;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCreditId() { return creditId; }
    public void setCreditId(UUID creditId) { this.creditId = creditId; }

    public String getFromPlayer() { return fromPlayer; }
    public void setFromPlayer(String fromPlayer) { this.fromPlayer = fromPlayer; }

    public String getToPlayer() { return toPlayer; }
    public void setToPlayer(String toPlayer) { this.toPlayer = toPlayer; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public List<String> getItems() { return items; }
    public void setItems(List<String> items) { this.items = items; }

    public String getItemNbt() { return itemNbt; }
    public void setItemNbt(String itemNbt) { this.itemNbt = itemNbt; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}