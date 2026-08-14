package op.creditmanager.client.core.service;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;

public final class CreditEventFactory {
    public CreditEventEntry create(CreditEventType type, CreditEntry entry, long amountMinor, long amountBeforeMinor,
                                   String note, String actor, String source, boolean itemPayment) {
        return new CreditEventEntry(type, entry, amountMinor, amountBeforeMinor, note, actor, source, itemPayment);
    }
}
