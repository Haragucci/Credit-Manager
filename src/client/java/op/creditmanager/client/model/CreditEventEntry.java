package op.creditmanager.client.model;

import java.util.UUID;

public class CreditEventEntry {
    private UUID id;
    private long timestamp;
    private CreditEventType type;
    private UUID creditId;
    private String dealName;
    private String creditor;
    private String debtor;
    private double amount;
    private double paidAmountAfter;
    private double remainingAmountAfter;
    private String note;
    private double amountBefore;
    private double amountAfter;
    private String actor;
    private String source;
    private boolean itemPayment;

    public CreditEventEntry() {
    }

    public CreditEventEntry(CreditEventType type, CreditEntry credit, double amount, double amountBefore,
                            String note, String actor, String source, boolean itemPayment) {
        this.id = UUID.randomUUID();
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.creditId = credit.getId();
        this.dealName = credit.getDealName();
        this.creditor = credit.getCreditor();
        this.debtor = credit.getDebtor();
        this.amount = amount;
        this.paidAmountAfter = credit.getPaidAmount();
        this.remainingAmountAfter = credit.getRemainingAmount();
        this.note = note;
        this.amountBefore = amountBefore;
        this.amountAfter = credit.getRemainingAmount();
        this.actor = actor;
        this.source = source;
        this.itemPayment = itemPayment;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public CreditEventType getType() { return type; }
    public void setType(CreditEventType type) { this.type = type; }
    public UUID getCreditId() { return creditId; }
    public void setCreditId(UUID creditId) { this.creditId = creditId; }
    public String getDealName() { return dealName; }
    public void setDealName(String dealName) { this.dealName = dealName; }
    public String getCreditor() { return creditor; }
    public void setCreditor(String creditor) { this.creditor = creditor; }
    public String getDebtor() { return debtor; }
    public void setDebtor(String debtor) { this.debtor = debtor; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public double getPaidAmountAfter() { return paidAmountAfter; }
    public void setPaidAmountAfter(double paidAmountAfter) { this.paidAmountAfter = paidAmountAfter; }
    public double getRemainingAmountAfter() { return remainingAmountAfter; }
    public void setRemainingAmountAfter(double remainingAmountAfter) { this.remainingAmountAfter = remainingAmountAfter; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public double getAmountBefore() { return amountBefore; }
    public void setAmountBefore(double amountBefore) { this.amountBefore = amountBefore; }
    public double getAmountAfter() { return amountAfter; }
    public void setAmountAfter(double amountAfter) { this.amountAfter = amountAfter; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isItemPayment() { return itemPayment; }
    public void setItemPayment(boolean itemPayment) { this.itemPayment = itemPayment; }
}
