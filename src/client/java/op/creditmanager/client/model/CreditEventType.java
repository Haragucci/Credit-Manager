package op.creditmanager.client.model;

/** Independent history types for credits; deliberately unrelated to Paylogs. */
public enum CreditEventType {
    CREDIT_CREATED,
    PAYMENT_ADDED,
    PAYMENT_DELETED,
    CREDIT_DELETED,
    CREDIT_PAID,
    CREDIT_PARTIAL
}
