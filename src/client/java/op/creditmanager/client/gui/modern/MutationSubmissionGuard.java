package op.creditmanager.client.gui.modern;

final class MutationSubmissionGuard {
    private boolean active;

    boolean tryBegin() {
        if (active) return false;
        active = true;
        return true;
    }

    void reset() {
        active = false;
    }
}
