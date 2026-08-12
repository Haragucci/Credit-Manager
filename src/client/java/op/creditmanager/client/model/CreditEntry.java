package op.creditmanager.client.model;

import op.creditmanager.client.money.CreditStatusRules;
import op.creditmanager.client.money.MoneyRules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CreditEntry {

    private UUID id;
    private String dealName;
    private String creditor;
    private String debtor;
    private long amountMinor;
    private long paidAmountMinor;
    private long createdAt;
    private Long dueDate;
    private String status;
    private List<Payment> payments;
    private String note;
    private Long completedAt;
    private boolean archived;

    public CreditEntry() {
        this.payments = new ArrayList<>();
    }

    public CreditEntry(UUID id, String dealName, String creditor, String debtor,
                       long amountMinor, Long dueDate, String note) {
        this.id = id;
        this.dealName = dealName;
        this.creditor = creditor;
        this.debtor = debtor;
        this.amountMinor = amountMinor;
        this.paidAmountMinor = 0L;
        this.createdAt = System.currentTimeMillis();
        this.dueDate = dueDate;
        this.status = "OPEN";
        this.payments = new ArrayList<>();
        this.note = note;
    }

    @Deprecated
    public CreditEntry(UUID id, String dealName, String creditor, String debtor,
                       double amount, Long dueDate, String note) {
        this(id, dealName, creditor, debtor, MoneyRules.fromLegacyDouble(amount, true).minorUnits(), dueDate, note);
    }

    public static String buildDealName(String debtor, String creditor, String label) {
        String base = debtor + "-" + creditor;
        if (label != null && !label.isBlank()) {
            String sanitized = label.trim()
                    .replaceAll("\\s+", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            if (!sanitized.isBlank()) return (base + "-" + sanitized).toLowerCase();
        }
        return base.toLowerCase();
    }

    public long getRemainingAmountMinor() {
        return Math.max(0L, Math.subtractExact(amountMinor, paidAmountMinor));
    }

    public void addPayment(Payment payment) {
        payments.add(payment);
        paidAmountMinor = Math.addExact(paidAmountMinor, payment.getAmountMinor());
        updateStatus();
    }

    public void removePayment(UUID paymentId) {
        Payment p = payments.stream()
                .filter(pay -> pay.getId().equals(paymentId))
                .findFirst().orElse(null);
        if (p != null) {
            payments.remove(p);
            paidAmountMinor = Math.max(0L, Math.subtractExact(paidAmountMinor, p.getAmountMinor()));
            if (!isManuallyFinal()) updateStatus();
        }
    }

    private void updateStatus() {
        status = CreditStatusRules.derive(amountMinor, paidAmountMinor);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDealName() { return dealName; }
    public void setDealName(String dealName) { this.dealName = dealName; }

    public String getCreditor() { return creditor; }
    public void setCreditor(String creditor) { this.creditor = creditor; }

    public String getDebtor() { return debtor; }
    public void setDebtor(String debtor) { this.debtor = debtor; }

    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public long getPaidAmountMinor() { return paidAmountMinor; }
    public void setPaidAmountMinor(long paidAmountMinor) { this.paidAmountMinor = paidAmountMinor; }
    @Deprecated public double getAmount() { return MoneyRules.toDisplayDouble(amountMinor); }
    @Deprecated public void setAmount(double amount) { this.amountMinor = MoneyRules.fromLegacyDouble(amount, true).minorUnits(); }
    @Deprecated public double getPaidAmount() { return MoneyRules.toDisplayDouble(paidAmountMinor); }
    @Deprecated public void setPaidAmount(double paidAmount) { this.paidAmountMinor = MoneyRules.fromLegacyDouble(paidAmount, false).minorUnits(); }
    @Deprecated public double getRemainingAmount() { return MoneyRules.toDisplayDouble(getRemainingAmountMinor()); }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getDueDate() { return dueDate; }
    public void setDueDate(Long dueDate) { this.dueDate = dueDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Payment> getPayments() { return payments; }
    public void setPayments(List<Payment> payments) { this.payments = payments; }

    public void replacePayments(List<Payment> payments) {
        this.payments = payments == null ? new ArrayList<>() : new ArrayList<>(payments);
        long total = 0L;
        for (Payment payment : this.payments) total = Math.addExact(total, payment.getAmountMinor());
        this.paidAmountMinor = total;
        if (!isManuallyFinal()) updateStatus();
    }

    public void refreshPaymentState() {
        if (!isManuallyFinal()) updateStatus();
    }

    private boolean isManuallyFinal() {
        return CreditStatusRules.isManualFinal(status);
    }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }

}
