package op.creditmanager.client.storage.db;

import op.creditmanager.client.storage.db.DatabaseManager.DatabaseAvailability;

record RuntimeDatabaseState(DatabaseAvailability availability, boolean healthy, boolean writeLocked,
                            boolean openHealthError, BackupCheckpointService.ProtectionState backupProtectionState,
                            long revision) {
    static RuntimeDatabaseState initial() {
        return new RuntimeDatabaseState(DatabaseAvailability.UNKNOWN, false, true, false,
                BackupCheckpointService.ProtectionState.HEALTHY, 0L);
    }

    boolean safeForWrites() {
        boolean available = availability == DatabaseAvailability.HEALTHY
                || availability == DatabaseAvailability.RESTORED_FROM_BACKUP
                || availability == DatabaseAvailability.BACKUP_PROTECTION_DEGRADED;
        return available && healthy && !writeLocked && !openHealthError && backupProtectionState.writesAllowed();
    }

    boolean requiresUserRecovery() {
        return availability == DatabaseAvailability.PHYSICALLY_CORRUPT
                || availability == DatabaseAvailability.NEEDS_USER_RECOVERY;
    }
}
