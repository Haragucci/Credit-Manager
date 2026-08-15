package op.creditmanager.client.paylog.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record BankPaylogContainerSnapshot(
        int handlerIdentity,
        int syncId,
        String title,
        List<SlotSnapshot> slots,
        String contentFingerprint,
        String containerFingerprint
) {
    public static BankPaylogContainerSnapshot create(int handlerIdentity, int syncId, String title,
                                                     List<SlotSnapshot> slots) {
        String safeTitle = title == null ? "" : title;
        List<SlotSnapshot> values = slots == null ? List.of() : List.copyOf(slots);
        StringBuilder content = new StringBuilder();
        for (SlotSnapshot slot : values) {
            append(content, Integer.toString(slot.slotId()));
            append(content, slot.itemId());
            append(content, Integer.toString(slot.count()));
            append(content, slot.visibleName());
            for (String line : slot.loreLines()) append(content, line);
            content.append('\u0001');
        }
        String contentFingerprint = sha256(content.toString());
        String containerFingerprint = sha256(handlerIdentity + "\u0000" + syncId + "\u0000"
                + safeTitle + "\u0000" + contentFingerprint);
        return new BankPaylogContainerSnapshot(handlerIdentity, syncId, safeTitle, values,
                contentFingerprint, containerFingerprint);
    }

    public BankPaylogContainerSnapshot {
        title = title == null ? "" : title;
        slots = slots == null ? List.of() : List.copyOf(slots);
        if (contentFingerprint == null || contentFingerprint.isBlank()
                || containerFingerprint == null || containerFingerprint.isBlank()) {
            throw new IllegalArgumentException("Container-Fingerprint fehlt");
        }
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append('\u0000');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", exception);
        }
    }

    public record SlotSnapshot(int slotId, String itemId, int count, String visibleName, List<String> loreLines) {
        public SlotSnapshot {
            if (slotId < 0 || count < 0) throw new IllegalArgumentException("Ungültiger Slot-Snapshot");
            itemId = itemId == null ? "" : itemId;
            visibleName = visibleName == null ? "" : visibleName.trim();
            loreLines = loreLines == null ? List.of() : List.copyOf(loreLines);
        }
    }
}
