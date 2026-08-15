package op.creditmanager.client.gui.modern;

final class UiCompletionGeneration {
    private long generation;

    long capture() {
        return generation;
    }

    boolean isCurrent(long token) {
        return token == generation;
    }

    void invalidate() {
        generation++;
    }
}
