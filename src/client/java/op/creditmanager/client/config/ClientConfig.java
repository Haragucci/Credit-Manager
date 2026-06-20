package op.creditmanager.client.config;

/**
 * Small, independent client-only configuration. It deliberately contains no
 * credit data so changing GUI preferences can never modify user records.
 */
public class ClientConfig {

    public static final int CURRENT_VERSION = 1;

    private int configVersion = CURRENT_VERSION;
    private GuiMode guiMode = GuiMode.UNSELECTED;
    private boolean automaticPaylogDetection = true;
    private boolean detectPaylogsInOverlay = true;
    private boolean showPaylogNotifications = false;

    public ClientConfig() {
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public GuiMode getGuiMode() {
        return guiMode;
    }

    public void setGuiMode(GuiMode guiMode) {
        this.guiMode = guiMode == null ? GuiMode.UNSELECTED : guiMode;
    }

    public boolean isAutomaticPaylogDetection() {
        return automaticPaylogDetection;
    }

    public void setAutomaticPaylogDetection(boolean automaticPaylogDetection) {
        this.automaticPaylogDetection = automaticPaylogDetection;
    }

    public boolean isDetectPaylogsInOverlay() {
        return detectPaylogsInOverlay;
    }

    public void setDetectPaylogsInOverlay(boolean detectPaylogsInOverlay) {
        this.detectPaylogsInOverlay = detectPaylogsInOverlay;
    }

    public boolean isShowPaylogNotifications() {
        return showPaylogNotifications;
    }

    public void setShowPaylogNotifications(boolean showPaylogNotifications) {
        this.showPaylogNotifications = showPaylogNotifications;
    }

    /** Normalises partially written or older configuration files safely. */
    public void normalize() {
        if (configVersion <= 0) {
            configVersion = CURRENT_VERSION;
        }
        if (guiMode == null) {
            guiMode = GuiMode.UNSELECTED;
        }
    }
}
