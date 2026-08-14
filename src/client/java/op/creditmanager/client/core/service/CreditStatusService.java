package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManagerCore.CreditException;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.money.CreditStatusRules;

import java.util.List;

public final class CreditStatusService {
    public boolean isActive(CreditEntry entry) {
        return CreditStatusRules.isActive(entry);
    }

    public void requireActive(CreditEntry entry) throws CreditException {
        if (!isActive(entry)) throw new CreditException("Deal ist abgeschlossen oder storniert.");
    }

    public List<CreditEntry> open(List<CreditEntry> entries) {
        return entries.stream().filter(this::isActive).toList();
    }
}
