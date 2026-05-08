package op.creditmanager.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CreditEntry {

    private UUID id;
    private String dealName;
    private String creditor;
    private String debtor;
    private double amount;
    private double paidAmount;
    private long createdAt;
    private Long dueDate;
    private String status;
    private List<Payment> payments;
    private String note;

    public CreditEntry() {
        this.payments = new ArrayList<>();
    }

    public CreditEntry(UUID id, String dealName, String creditor, String debtor,
                       double amount, Long dueDate, String note) {
        this.id = id;
        this.dealName = dealName;
        this.creditor = creditor;
        this.debtor = debtor;
        this.amount = amount;
        this.paidAmount = 0.0;
        this.createdAt = System.currentTimeMillis();
        this.dueDate = dueDate;
        this.status = "OPEN";
        this.payments = new ArrayList<>();
        this.note = note;
    }

    public static String buildDealName(String debtor, String creditor, String label) {
        String base = debtor + "-" + creditor;
        if (label != null && !label.isBlank()) {
            String sanitized = label.trim().replace(" ", "-");
            return (base + "-" + sanitized).toLowerCase();
        }
        return base.toLowerCase();
    }

    public double getRemainingAmount() {
        return Math.max(0, amount - paidAmount);
    }

    public void addPayment(Payment payment) {
        payments.add(payment);
        if (payment.getAmount() != null) {
            paidAmount += payment.getAmount();
        }
        updateStatus();
    }

    public void removePayment(UUID paymentId) {
        Payment p = payments.stream()
                .filter(pay -> pay.getId().equals(paymentId))
                .findFirst().orElse(null);
        if (p != null) {
            payments.remove(p);
            if (p.getAmount() != null) {
                paidAmount = Math.max(0, paidAmount - p.getAmount());
            }
            updateStatus();
        }
    }

    private void updateStatus() {
        if (paidAmount >= amount) {
            status = "PAID";
        } else if (paidAmount > 0) {
            status = "PARTIAL";
        } else {
            status = "OPEN";
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDealName() { return dealName; }
    public void setDealName(String dealName) { this.dealName = dealName; }

    public String getCreditor() { return creditor; }
    public void setCreditor(String creditor) { this.creditor = creditor; }

    public String getDebtor() { return debtor; }
    public void setDebtor(String debtor) { this.debtor = debtor; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = paidAmount; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getDueDate() { return dueDate; }
    public void setDueDate(Long dueDate) { this.dueDate = dueDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Payment> getPayments() { return payments; }
    public void setPayments(List<Payment> payments) { this.payments = payments; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

}