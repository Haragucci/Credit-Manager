package op.creditmanager.client.core;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.PlayerCreditData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CreditManager {


    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final CreditRepository repository;

    public CreditManager(CreditRepository repository) {
        if (repository == null) {
            throw new IllegalStateException("CreditRepository darf nicht NULL sein (Initialisierungsfehler)");
        }
        this.repository = repository;
    }


    public CreditEntry createCredit(String gläubiger, String schuldner, double betrag,
                                    Long fälligkeit, String bezeichnung, String notiz) throws CreditException {

        validateNames(gläubiger, schuldner);

        if (betrag <= 0)
            throw new CreditException("Der Betrag muss größer als 0 sein.");

        String dealName = CreditEntry.buildDealName(schuldner, gläubiger, bezeichnung);

        boolean exists = repository.getAllCredits().stream()
                .anyMatch(e -> dealName.equalsIgnoreCase(e.getDealName())
                        && !STATUS_CANCELLED.equals(e.getStatus()));

        if (exists) {
            throw new CreditException("Ein aktiver Deal mit dem Namen \"" + dealName + "\" existiert bereits.");
        }

        CreditEntry entry = new CreditEntry(
                UUID.randomUUID(),
                dealName,
                gläubiger.toLowerCase(),
                schuldner.toLowerCase(),
                betrag,
                fälligkeit,
                notiz
        );


        PlayerCreditData gläubigerData = repository.getOrCreatePlayer(gläubiger);
        gläubigerData.addCreditorCredit(entry.getId());
        repository.savePlayer(gläubigerData);

        PlayerCreditData schuldnerData = repository.getOrCreatePlayer(schuldner);
        schuldnerData.addDebtorCredit(entry.getId());
        repository.savePlayer(schuldnerData);

        repository.saveCredit(entry);
        return entry;
    }


    public Payment addMoneyPayment(UUID dealId, String vonSpieler, double betrag) throws CreditException {

        CreditEntry entry = getSafeCredit(dealId);

        validateActive(entry);
        validateAmount(betrag);

        double rest = entry.getRemainingAmount();
        double finalAmount = Math.min(betrag, rest);

        Payment payment = new Payment(
                dealId,
                safe(vonSpieler),
                entry.getCreditor(),
                finalAmount,
                null,
                "MANUELL"
        );

        entry.addPayment(payment);
        repository.saveCredit(entry);
        repository.savePayment(payment);

        return payment;
    }

    public Payment addItemPayment(UUID dealId, String vonSpieler,
                                  List<String> items, double wert,
                                  String nbt) throws CreditException {

        CreditEntry entry = getSafeCredit(dealId);

        validateActive(entry);
        validateAmount(wert);

        double finalValue = Math.min(wert, entry.getRemainingAmount());

        Payment payment = new Payment(
                dealId,
                safe(vonSpieler),
                entry.getCreditor(),
                finalValue,
                items,
                "MANUELL"
        );

        payment.setItemNbt(nbt);

        entry.addPayment(payment);
        repository.saveCredit(entry);
        repository.savePayment(payment);

        return payment;
    }

    public CreditEntry deleteCredit(UUID dealId) throws CreditException {

        CreditEntry entry = getSafeCredit(dealId);

        repository.deleteCredit(dealId);

        return entry;
    }

    public List<CreditEntry> getOpenCreditsAsDebtor(String player) {
        return repository.getCreditsByDebtor(player).stream()
                .filter(e -> STATUS_OPEN.equals(e.getStatus()) || STATUS_PARTIAL.equals(e.getStatus()))
                .toList();
    }

    public List<CreditEntry> getAllCreditsAsDebtor(String player) {
        return repository.getCreditsByDebtor(player);
    }

    public List<CreditEntry> getOpenCreditsAsCreditor(String player) {
        return repository.getCreditsByCreditor(player).stream()
                .filter(e -> STATUS_OPEN.equals(e.getStatus()) || STATUS_PARTIAL.equals(e.getStatus()))
                .toList();
    }

    public List<CreditEntry> getAllCreditsAsCreditor(String player) {
        return repository.getCreditsByCreditor(player);
    }

    public void deletePayment(UUID paymentId) throws CreditException {
        Payment payment = repository.getAllPayments().stream()
                .filter(p -> p.getId().equals(paymentId))
                .findFirst()
                .orElseThrow(() -> new CreditException("Zahlung nicht gefunden: " + paymentId));

        CreditEntry entry = getSafeCredit(payment.getCreditId());
        entry.removePayment(paymentId);
        repository.saveCredit(entry);
        repository.deletePayment(paymentId);
    }

    public List<CreditEntry> getCreditsForPlayer(String player) {
        List<CreditEntry> all = new ArrayList<>();
        all.addAll(repository.getCreditsByDebtor(player));
        all.addAll(repository.getCreditsByCreditor(player));

        all.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return all;
    }

    public List<String> getDealNamesForPlayer(String player) {

        if (player == null || player.isBlank()) {
            return List.of();
        }

        return getCreditsForPlayer(player).stream()
                .filter(e -> !STATUS_CANCELLED.equals(e.getStatus()))
                .map(CreditEntry::getDealName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    public Optional<CreditEntry> findCredit(String input) {
        if (input == null || input.isBlank()) return Optional.empty();

        try {
            return repository.findCreditById(UUID.fromString(input));
        } catch (Exception ignored) {}

        if (input.length() >= 6 && input.length() < 36 && !input.contains("-")) {
            Optional<CreditEntry> shortId = repository.findCreditByShortId(input);
            if (shortId.isPresent()) return shortId;
        }

        Optional<CreditEntry> byName = repository.findCreditByName(input);
        if (byName.isPresent()) return byName;

        return repository.findCreditByNamePrefix(input);
    }

    public List<Payment> getPaymentsForCredit(UUID dealId) {
        return repository.getPaymentsByCreditId(dealId);
    }

    private CreditEntry getSafeCredit(UUID id) throws CreditException {
        return repository.findCreditById(id)
                .orElseThrow(() -> new CreditException("Deal nicht gefunden: " + id));
    }

    private void validateActive(CreditEntry entry) throws CreditException {
        if (STATUS_PAID.equals(entry.getStatus()) || STATUS_CANCELLED.equals(entry.getStatus())) {
            throw new CreditException("Deal ist abgeschlossen oder storniert.");
        }
    }

    private void validateAmount(double amount) throws CreditException {
        if (amount <= 0) {
            throw new CreditException("Betrag muss > 0 sein.");
        }
    }

    private void validateNames(String a, String b) throws CreditException {
        if (a == null || a.isBlank()) throw new CreditException("Ungültiger Gläubiger");
        if (b == null || b.isBlank()) throw new CreditException("Ungültiger Schuldner");
        if (a.equalsIgnoreCase(b)) throw new CreditException("Selber Spieler nicht erlaubt");
    }

    private String safe(String name) {
        return name == null ? "unknown" : name.toLowerCase();
    }

    public Payment addItemPaymentForced(UUID dealId, String vonSpieler, List<String> items, double verrechnungswert, String itemNbt) throws CreditException { CreditEntry eintrag = repository.findCreditById(dealId) .orElseThrow(() -> new CreditException("Deal nicht gefunden: " + dealId)); if ("PAID".equals(eintrag.getStatus()) || "CANCELLED".equals(eintrag.getStatus())) throw new CreditException("Dieser Deal ist bereits abgeschlossen oder storniert."); if (items == null || items.isEmpty()) throw new CreditException("Keine Items angegeben."); if (verrechnungswert <= 0) throw new CreditException("Der Verrechnungswert muss größer als 0 sein."); double tatsächlicherWert = Math.min(verrechnungswert, eintrag.getRemainingAmount()); Payment zahlung = new Payment(dealId, vonSpieler.toLowerCase(), eintrag.getCreditor(), tatsächlicherWert, items, "MANUELL"); zahlung.setItemNbt(itemNbt); eintrag.addPayment(zahlung); repository.saveCredit(eintrag); repository.savePayment(zahlung); return zahlung; }

    public static class CreditException extends Exception {
        public CreditException(String message) {
            super(message);
        }
    }
}