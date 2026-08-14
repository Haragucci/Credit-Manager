package op.creditmanager.client.core.service;

public record MutationCommitResult(Status status, long committedRevision) {
    public enum Status {
        NOT_COMMITTED,
        COMMITTED_SYNCED,
        COMMITTED_RELOAD_REQUIRED,
        COMMITTED_DEGRADED
    }

    public boolean committed() {
        return status != Status.NOT_COMMITTED;
    }

    public String userMessage() {
        return switch (status) {
            case NOT_COMMITTED -> "Vorgang wurde nicht gespeichert; der vorherige Datenstand bleibt unverändert.";
            case COMMITTED_SYNCED -> "";
            case COMMITTED_RELOAD_REQUIRED -> "Vorgang wurde gespeichert und die Ansicht aus der Datenbank neu synchronisiert.";
            case COMMITTED_DEGRADED -> "Vorgang wurde gespeichert, aber die lokale Ansicht konnte nicht synchronisiert werden. Weitere Änderungen sind vorübergehend gesperrt.";
        };
    }

    public static MutationCommitResult notCommitted() {
        return new MutationCommitResult(Status.NOT_COMMITTED, -1L);
    }
}
