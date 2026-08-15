package op.creditmanager.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import op.creditmanager.client.command.CreditManagerCommand;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.PaymentDetector;
import op.creditmanager.client.core.PaymentMessageRouter;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.SkinHeadUtil;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;
import op.creditmanager.client.paylog.importer.BankPaylogImportController;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseHealthChecker;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.LegacyJsonMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreditManagerClient implements ClientModInitializer {
    public static final String MOD_ID = "creditmanager";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CreditManager creditManager;
    private static CreditRepository creditRepository;
    private static PaymentDetector paymentDetector;
    private static volatile boolean shuttingDown;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[CreditManager] Initialisierung...");
        shuttingDown = false;

        FileManager.initialize();
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize();
        ClientConfigManager.reload();
        SkinHeadUtil.initializeAsync();

        creditRepository = new CreditRepository();
        creditManager = new CreditManager(creditRepository);
        BankPaylogImportController bankImportController = BankPaylogImportController.getInstance();
        bankImportController.initialize(creditManager);
        paymentDetector = database.isHealthy() ? new PaymentDetector(creditManager) : null;
        if (database.isHealthy()) {
            DatabaseHealthChecker.getInstance().check();
            LegacyJsonMigrationService.getInstance().inspectAtStartup();
            creditRepository.load();
            TransactionRepository.getInstance().load();
            CreditEventRepository.getInstance().load();
        } else {
            TransactionRepository.getInstance().load();
            LOGGER.error("[CreditManager] Datenbank-Recovery erforderlich; Datenzugriffe werden bis zur Wiederherstellung übersprungen.");
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.of(MOD_ID, "global_flyins"), (context, tickCounter) -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.currentScreen == null) {
                        ModernToastManager.getInstance().render(context, client.textRenderer,
                                client.getWindow().getScaledWidth(), -1, -1, tickCounter.getTickProgress(false));
                    }
                });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (creditManager != null) CreditManagerCommand.register(dispatcher, creditManager);
            else LOGGER.error("CreditManager ist null beim Command-Setup!");
        });
        ClientTickEvents.END_CLIENT_TICK.register(bankImportController::tick);

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            runPaymentMessageCallback("incoming CHAT payment message metadata", () -> {
                String eventId = null;
                if (signedMessage != null) {
                    if (signedMessage.signature() != null) eventId = "signature:" + signedMessage.signature();
                    else eventId = "unsigned:" + signedMessage.getSender() + ':' + signedMessage.getTimestamp() + ':' + signedMessage.getSalt();
                }
                long receivedAt = receptionTimestamp == null ? System.currentTimeMillis() : receptionTimestamp.toEpochMilli();
                PaymentMessageRouter.dispatch(paymentDetector, ClientConfigManager.isAutomaticPaylogDetection(),
                        ClientConfigManager.isDetectPaylogsInOverlay(), PaymentMessageRouter.MessageSource.CHAT,
                        message.getString(), receivedAt, eventId, CreditManagerClient::logPaymentMessageFailure);
            });
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> runPaymentMessageCallback(
                "incoming GAME payment message metadata", () -> {
            PaymentMessageRouter.MessageSource source = overlay
                    ? PaymentMessageRouter.MessageSource.OVERLAY : PaymentMessageRouter.MessageSource.GAME;
            PaymentMessageRouter.dispatch(paymentDetector, ClientConfigManager.isAutomaticPaylogDetection(),
                    ClientConfigManager.isDetectPaylogsInOverlay(), source, message.getString(),
                    System.currentTimeMillis(), null, CreditManagerClient::logPaymentMessageFailure);
        }));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PaymentMessageRouter.rotateContext(paymentDetector, serverIdentity(client), CreditManagerClient::logPaymentMessageFailure);
            bankImportController.onJoin();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PaymentMessageRouter.rotateContext(paymentDetector, "disconnected", CreditManagerClient::logPaymentMessageFailure);
            bankImportController.onDisconnect();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            shuttingDown = true;
            paymentDetector = null;
            bankImportController.shutdown();
            CreditManagerShutdownCoordinator.ShutdownResult result = CreditManagerShutdownCoordinator.shutdown();
            if (!result.mutationsStopped()) {
                LOGGER.error("[CreditManager] Angenommene Datenänderungen konnten beim Shutdown nicht vollständig abgeschlossen werden; Backup und Storage-Lease bleiben aktiv.");
            } else if (!result.recoveryStopped()) {
                LOGGER.error("[CreditManager] Kritische Recovery-Aktion lief beim Shutdown weiter; der Storage-Lease bleibt bis zum JVM-Ende gehalten.");
            } else if (!result.backupFlushed()) {
                LOGGER.warn("[CreditManager] Finaler Backup-Checkpoint konnte innerhalb des Shutdown-Limits nicht bestätigt werden; der Storage-Lease bleibt bis zum JVM-Ende gehalten.");
            } else if (!result.queriesStopped() || !result.skinFlushed()) {
                LOGGER.warn("[CreditManager] Hintergrund-Worker konnten beim Shutdown nicht vollständig beendet werden; der Storage-Lease bleibt bis zum JVM-Ende gehalten.");
            }
        });

        LOGGER.info("[CreditManager] Bereit.");
    }

    private static String serverIdentity(MinecraftClient client) {
        if (client == null) return "unknown";
        return PaymentMessageRouter.serverIdentity(client.isInSingleplayer(),
                client.getCurrentServerEntry() == null ? null : client.getCurrentServerEntry().address);
    }

    public static boolean rebindAfterStorageRecovery() {
        if (shuttingDown || creditManager == null || creditRepository == null
                || !DatabaseManager.getInstance().isHealthy()) {
            paymentDetector = null;
            return false;
        }
        try {
            ClientConfigManager.reload();
            creditRepository.load();
            TransactionRepository.getInstance().load();
            CreditEventRepository.getInstance().load();
            PaymentDetector rebound = new PaymentDetector(creditManager);
            PaymentMessageRouter.rotateContext(rebound, serverIdentity(MinecraftClient.getInstance()),
                    CreditManagerClient::logPaymentMessageFailure);
            paymentDetector = rebound;
            return true;
        } catch (RuntimeException exception) {
            paymentDetector = null;
            LOGGER.error("[CreditManager] Runtime-Services konnten nach Storage-Recovery nicht vollständig neu gebunden werden.", exception);
            return false;
        }
    }

    private static void logPaymentMessageFailure(String operation, RuntimeException exception) {
        LOGGER.error("[CreditManager] Fehler bei {}. Die Nachricht wurde isoliert, damit der Netzwerkpfad stabil bleibt.",
                operation, exception);
    }

    static void runPaymentMessageCallback(String operation, Runnable callback) {
        if (shuttingDown) return;
        try {
            callback.run();
        } catch (RuntimeException exception) {
            logPaymentMessageFailure(operation, exception);
        }
    }
}
