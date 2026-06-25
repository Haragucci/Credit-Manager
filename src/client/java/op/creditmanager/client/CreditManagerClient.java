package op.creditmanager.client;

import op.creditmanager.client.command.CreditManagerCommand;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.PaymentDetector;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.DatabaseHealthChecker;
import op.creditmanager.client.storage.db.LegacyJsonMigrationService;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreditManagerClient implements ClientModInitializer {

	public static final String MOD_ID = "creditmanager";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static CreditManager creditManager;
	private static CreditRepository creditRepository;
	private static PaymentDetector paymentDetector;

	@Override
	public void onInitializeClient() {
		LOGGER.info("[CreditManager] Initialisierung...");

		FileManager.initialize();
		ClientConfigManager.reload();
		DatabaseManager database = DatabaseManager.getInstance();
		database.initialize();

		creditRepository = new CreditRepository();
		CreditEventRepository.getInstance().bind(creditRepository);
		creditManager = new CreditManager(creditRepository);
		if (database.isHealthy()) {
			DatabaseHealthChecker.getInstance().check();
			LegacyJsonMigrationService.getInstance().inspectAtStartup();
			creditRepository.load();
			TransactionRepository.getInstance().load();
			CreditEventRepository.getInstance().load();
			paymentDetector = new PaymentDetector(creditManager);
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
			if (creditManager != null) {
				CreditManagerCommand.register(dispatcher, creditManager);
			} else {
				LOGGER.error("CreditManager ist null beim Command-Setup!");
			}
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (paymentDetector != null && ClientConfigManager.isAutomaticPaylogDetection()) {
				paymentDetector.process(message.getString());
			}
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (paymentDetector != null && ClientConfigManager.isAutomaticPaylogDetection()
					&& (!overlay || ClientConfigManager.isDetectPaylogsInOverlay())) {
				paymentDetector.process(message.getString());
			}
		});

		LOGGER.info("[CreditManager] Bereit.");
	}

}
