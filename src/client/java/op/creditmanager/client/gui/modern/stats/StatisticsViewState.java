package op.creditmanager.client.gui.modern.stats;

public record StatisticsViewState(Status status, CreditStatistics statistics, String message) {
    public static StatisticsViewState loading() {
        return new StatisticsViewState(Status.LOADING, null, "Statistiken werden geladen…");
    }

    public static StatisticsViewState loaded(CreditStatistics statistics) {
        return new StatisticsViewState(Status.LOADED, statistics, "");
    }

    public static StatisticsViewState empty(CreditStatistics statistics) {
        return new StatisticsViewState(Status.EMPTY, statistics, "Für diesen Zeitraum wurden keine Einträge gefunden.");
    }

    public static StatisticsViewState error() {
        return new StatisticsViewState(Status.ERROR, null, "Statistiken konnten nicht geladen werden.");
    }

    public static StatisticsViewState invalid(String message) {
        return new StatisticsViewState(Status.INVALID, null, message);
    }

    public enum Status { LOADING, LOADED, EMPTY, ERROR, INVALID }
}
