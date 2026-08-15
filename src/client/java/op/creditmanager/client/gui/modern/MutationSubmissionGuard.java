package op.creditmanager.client.gui.modern;

final class MutationSubmissionGuard {
    private boolean active;
    private long sequence;
    private long activeToken;

    boolean tryBegin() {
        return tryBeginToken() >= 0L;
    }

    long tryBeginToken() {
        if (active) return -1L;
        active = true;
        activeToken = ++sequence;
        return activeToken;
    }

    void reset() {
        active = false;
        activeToken = 0L;
    }

    boolean complete(long token) {
        if (!active || token != activeToken) return false;
        reset();
        return true;
    }

    boolean isActive() {
        return active;
    }
}
