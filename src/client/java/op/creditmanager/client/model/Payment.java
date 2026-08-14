package op.creditmanager.client.model;

import op.creditmanager.client.money.MoneyRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Payment {

    private UUID id;
    private UUID creditId;
    private String fromPlayer;
    private String toPlayer;
    private long amountMinor;
    private PaymentKind paymentKind;
    private List<String> items;
    private String itemNbt;
    private List<String> itemNbtEntries;
    private long timestamp;
    private String source;
    private UUID paylogId;
    private String note;

    public Payment() {
        this.items = new ArrayList<>();
        this.itemNbtEntries = new ArrayList<>();
        this.paymentKind = PaymentKind.MONEY;
    }

    public Payment(UUID creditId, String fromPlayer, String toPlayer,
                   long amountMinor, List<String> items, String source) {
        this.id = UUID.randomUUID();
        this.creditId = creditId;
        this.fromPlayer = fromPlayer;
        this.toPlayer = toPlayer;
        this.amountMinor = amountMinor;
        this.items = items != null ? items : new ArrayList<>();
        this.paymentKind = this.items.isEmpty() ? PaymentKind.MONEY : PaymentKind.ITEM;
        this.itemNbtEntries = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
        this.source = source;
    }

    @Deprecated
    public Payment(UUID creditId, String fromPlayer, String toPlayer,
                   Double amount, List<String> items, String source) {
        this(creditId, fromPlayer, toPlayer,
                amount == null ? 0L : MoneyRules.fromLegacyDouble(amount, false).minorUnits(), items, source);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCreditId() { return creditId; }
    public void setCreditId(UUID creditId) { this.creditId = creditId; }

    public String getFromPlayer() { return fromPlayer; }
    public void setFromPlayer(String fromPlayer) { this.fromPlayer = fromPlayer; }

    public String getToPlayer() { return toPlayer; }
    public void setToPlayer(String toPlayer) { this.toPlayer = toPlayer; }

    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    @Deprecated public Double getAmount() { return MoneyRules.toDisplayDouble(amountMinor); }
    @Deprecated public void setAmount(Double amount) { this.amountMinor = amount == null ? 0L : MoneyRules.fromLegacyDouble(amount, false).minorUnits(); }

    public PaymentKind getPaymentKind() { return paymentKind == null ? (getItems().isEmpty() ? PaymentKind.MONEY : PaymentKind.ITEM) : paymentKind; }
    public void setPaymentKind(PaymentKind paymentKind) { this.paymentKind = paymentKind == null ? PaymentKind.MONEY : paymentKind; }

    public List<String> getItems() {
        if (items == null) items = new ArrayList<>();
        return items;
    }
    public void setItems(List<String> items) { this.items = items != null ? items : new ArrayList<>(); }

    public String getItemNbt() { return itemNbt; }
    public void setItemNbt(String itemNbt) { this.itemNbt = itemNbt; }

    public List<String> getItemNbtEntries() {
        if (itemNbtEntries == null) itemNbtEntries = new ArrayList<>();
        return itemNbtEntries;
    }

    public void setItemNbtEntries(List<String> itemNbtEntries) {
        this.itemNbtEntries = itemNbtEntries != null ? new ArrayList<>(itemNbtEntries) : new ArrayList<>();
    }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public UUID getPaylogId() { return paylogId; }
    public void setPaylogId(UUID paylogId) { this.paylogId = paylogId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
