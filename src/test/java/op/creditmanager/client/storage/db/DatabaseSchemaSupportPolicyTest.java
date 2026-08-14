package op.creditmanager.client.storage.db;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSchemaSupportPolicyTest {

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7, 8})
    void releasedH2SchemaVersionsShareOneSupportPolicy(int version) {
        assertTrue(DatabaseSchemaSupportPolicy.supports(version));
        assertTrue(DatabaseSchemaSupportPolicy.descriptor(version).supported());
        if (version < DatabaseManager.SCHEMA_VERSION) assertTrue(DatabaseSchemaSupportPolicy.requiresMigration(version));
        else assertFalse(DatabaseSchemaSupportPolicy.requiresMigration(version));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2, 3, 9, 10, 100})
    void unsupportedSchemaVersionsAreRejected(int version) {
        assertFalse(DatabaseSchemaSupportPolicy.supports(version));
        assertFalse(DatabaseSchemaSupportPolicy.requiresMigration(version));
    }
}
