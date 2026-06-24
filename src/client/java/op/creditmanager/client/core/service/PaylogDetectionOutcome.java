package op.creditmanager.client.core.service;

import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.TransactionEntry;

public record PaylogDetectionOutcome(
        TransactionEntry paylog,
        CreditManager.PaylogLinkResult linkResult,
        boolean matchingDealFound,
        boolean automaticallyLinked,
        boolean overpayClosedDeal,
        boolean multipleMatchingDeals,
        String userMessage
) {
}
