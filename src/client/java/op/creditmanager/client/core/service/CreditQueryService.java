package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManagerCore;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class CreditQueryService {
    private final CreditRepository repository;
    private final CreditStatusService statuses;

    public CreditQueryService(CreditRepository repository, CreditStatusService statuses) {
        this.repository = repository;
        this.statuses = statuses;
    }

    public List<CreditEntry> openAsDebtor(String player) { return statuses.open(repository.getCreditsByDebtor(player)); }
    public List<CreditEntry> openAsCreditor(String player) { return statuses.open(repository.getCreditsByCreditor(player)); }
    public List<CreditEntry> allAsDebtor(String player) { return repository.getCreditsByDebtor(player); }
    public List<CreditEntry> allAsCreditor(String player) { return repository.getCreditsByCreditor(player); }

    public List<CreditEntry> forPlayer(String player) {
        List<CreditEntry> all = new ArrayList<>();
        all.addAll(repository.getCreditsByDebtor(player));
        all.addAll(repository.getCreditsByCreditor(player));
        all.sort((left, right) -> Long.compare(right.getCreatedAt(), left.getCreatedAt()));
        return all;
    }

    public List<String> dealNamesForPlayer(String player) {
        if (player == null || player.isBlank()) return List.of();
        return forPlayer(player).stream().filter(entry -> !CreditManagerCore.STATUS_CANCELLED.equals(entry.getStatus()))
                .map(CreditEntry::getDealName).filter(name -> name != null && !name.isBlank()).distinct().sorted().toList();
    }

    public Optional<CreditEntry> find(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        try { return repository.findCreditById(UUID.fromString(input)); } catch (IllegalArgumentException ignored) { }
        if (input.length() >= 6 && input.length() < 36 && !input.contains("-")) {
            Optional<CreditEntry> shortId = repository.findCreditByShortId(input);
            if (shortId.isPresent()) return shortId;
        }
        Optional<CreditEntry> byName = repository.findCreditByName(input);
        return byName.isPresent() ? byName : repository.findCreditByNamePrefix(input);
    }

    public List<Payment> paymentsForCredit(UUID dealId) { return repository.getPaymentsByCreditId(dealId); }
}
