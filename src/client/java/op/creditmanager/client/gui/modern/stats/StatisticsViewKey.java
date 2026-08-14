package op.creditmanager.client.gui.modern.stats;

import java.time.LocalDate;

public record StatisticsViewKey(String player, int periodIndex, LocalDate customStart, LocalDate customEnd,
                                long fromInclusive, long managerRevision, long eventRevision) { }
