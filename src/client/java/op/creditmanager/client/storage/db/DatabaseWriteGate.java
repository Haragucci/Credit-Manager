package op.creditmanager.client.storage.db;

final class DatabaseWriteGate {
    boolean allows(DatabaseWriteMode mode, boolean physicallyAvailable, boolean writeLocked,
                   boolean openHealthError, boolean migrationIncomplete, boolean backupProtectionCritical) {
        if (!physicallyAvailable) return false;
        return switch (mode) {
            case NORMAL -> !writeLocked && !openHealthError && !migrationIncomplete && !backupProtectionCritical;
            case MIGRATION -> !writeLocked;
            case REPAIR -> true;
            case HEALTH_MAINTENANCE -> true;
        };
    }
}
