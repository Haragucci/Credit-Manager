package op.creditmanager.client.model;

import op.creditmanager.client.money.MoneyRules;

import java.util.UUID;

public class CreditEventEntry {
    private UUID id;
    private long timestamp;
    private CreditEventType type;
    private UUID creditId;
    private String dealName;
    private String creditor;
    private String debtor;
    private long amountMinor;
    private long paidAmountAfterMinor;
    private long remainingAmountAfterMinor;
    private String note;
    private long amountBeforeMinor;
    private long amountAfterMinor;
    private String actor;
    private String source;
    private boolean itemPayment;

    public CreditEventEntry() {
    }

    public CreditEventEntry(CreditEventType type, CreditEntry credit, long amountMinor, long amountBeforeMinor,
                            String note, String actor, String source, boolean itemPayment) {
        this.id = UUID.randomUUID();
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.creditId = credit.getId();
        this.dealName = credit.getDealName();
        this.creditor = credit.getCreditor();
        this.debtor = credit.getDebtor();
        this.amountMinor = amountMinor;
        this.paidAmountAfterMinor = credit.getPaidAmountMinor();
        this.remainingAmountAfterMinor = credit.getRemainingAmountMinor();
        this.note = note;
        this.amountBeforeMinor = amountBeforeMinor;
        this.amountAfterMinor = credit.getRemainingAmountMinor();
        this.actor = actor;
        this.source = source;
        this.itemPayment = itemPayment;
    }

    @Deprecated
    public CreditEventEntry(CreditEventType type, CreditEntry credit, double amount, double amountBefore,
                            String note, String actor, String source, boolean itemPayment) {
        this(type, credit, MoneyRules.fromLegacyDouble(amount, false).minorUnits(),
                MoneyRules.fromLegacyDouble(amountBefore, false).minorUnits(), note, actor, source, itemPayment);
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
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public long getPaidAmountAfterMinor() { return paidAmountAfterMinor; }
    public void setPaidAmountAfterMinor(long paidAmountAfterMinor) { this.paidAmountAfterMinor = paidAmountAfterMinor; }
    public long getRemainingAmountAfterMinor() { return remainingAmountAfterMinor; }
    public void setRemainingAmountAfterMinor(long remainingAmountAfterMinor) { this.remainingAmountAfterMinor = remainingAmountAfterMinor; }
    @Deprecated public double getAmount() { return MoneyRules.toDisplayDouble(amountMinor); }
    @Deprecated public void setAmount(double amount) { this.amountMinor = MoneyRules.fromLegacyDouble(amount, false).minorUnits(); }
    @Deprecated public double getPaidAmountAfter() { return MoneyRules.toDisplayDouble(paidAmountAfterMinor); }
    @Deprecated public void setPaidAmountAfter(double paidAmountAfter) { this.paidAmountAfterMinor = MoneyRules.fromLegacyDouble(paidAmountAfter, false).minorUnits(); }
    @Deprecated public double getRemainingAmountAfter() { return MoneyRules.toDisplayDouble(remainingAmountAfterMinor); }
    @Deprecated public void setRemainingAmountAfter(double remainingAmountAfter) { this.remainingAmountAfterMinor = MoneyRules.fromLegacyDouble(remainingAmountAfter, false).minorUnits(); }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public long getAmountBeforeMinor() { return amountBeforeMinor; }
    public void setAmountBeforeMinor(long amountBeforeMinor) { this.amountBeforeMinor = amountBeforeMinor; }
    public long getAmountAfterMinor() { return amountAfterMinor; }
    public void setAmountAfterMinor(long amountAfterMinor) { this.amountAfterMinor = amountAfterMinor; }
    @Deprecated public double getAmountBefore() { return MoneyRules.toDisplayDouble(amountBeforeMinor); }
    @Deprecated public void setAmountBefore(double amountBefore) { this.amountBeforeMinor = MoneyRules.fromLegacyDouble(amountBefore, false).minorUnits(); }
    @Deprecated public double getAmountAfter() { return MoneyRules.toDisplayDouble(amountAfterMinor); }
    @Deprecated public void setAmountAfter(double amountAfter) { this.amountAfterMinor = MoneyRules.fromLegacyDouble(amountAfter, false).minorUnits(); }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isItemPayment() { return itemPayment; }
    public void setItemPayment(boolean itemPayment) { this.itemPayment = itemPayment; }
}
