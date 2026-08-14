package op.creditmanager.client.storage.db;

final class DatabaseSchemaSupportPolicy {
    static final int MINIMUM_SUPPORTED_VERSION = 4;

    private DatabaseSchemaSupportPolicy() { }

    static boolean supports(int version) {
        return version >= MINIMUM_SUPPORTED_VERSION && version <= DatabaseManager.SCHEMA_VERSION;
    }

    static boolean requiresMigration(int version) {
        return supports(version) && version < DatabaseManager.SCHEMA_VERSION;
    }

    static HistoricalSchemaDescriptor descriptor(int version) {
        return switch (version) {
            case 4 -> new HistoricalSchemaDescriptor(4, "1.1.0-beta", "b402d38", true);
            case 5 -> new HistoricalSchemaDescriptor(5, "1.1.1 development", "e75baef", true);
            case 6 -> new HistoricalSchemaDescriptor(6, "1.1.1 development", "ce656f2", true);
            case 7 -> new HistoricalSchemaDescriptor(7, "1.1.1-beta", "6a965c7", true);
            case 8 -> new HistoricalSchemaDescriptor(8, "1.1.3-beta", "0565119", true);
            default -> new HistoricalSchemaDescriptor(version, "unreleased or unknown", "", false);
        };
    }

    record HistoricalSchemaDescriptor(int version, String release, String sourceCommit, boolean supported) { }
}
