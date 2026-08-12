package op.creditmanager.client.storage.db;

@FunctionalInterface
interface DatabaseFaultInjector {
    DatabaseFaultInjector NONE = point -> { };

    void hit(FailurePoint point) throws Exception;

    enum FailurePoint {
        AFTER_CREDIT_UPSERT,
        AFTER_PAYMENT_UPSERT_BEFORE_EVENT,
        BEFORE_PAYLOG_AGGREGATE_REFRESH,
        AFTER_PAYLOG_AGGREGATE_REFRESH_BEFORE_COMMIT,
        LEGACY_AFTER_DOMAIN_INSERT,
        BEFORE_REPAIR_COMMIT,
        STARTUP_AFTER_OPEN_BEFORE_SCHEMA_VALIDATION,
        HEALTH_AFTER_CREDITS_BEFORE_PAYMENTS
    }
}
