package op.creditmanager.client;

import op.creditmanager.client.command.CreditManagerCommand;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.PaymentDetector;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.storage.FileManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreditManagerClient implements ClientModInitializer {

	public static final String MOD_ID = "assets/creditmanager";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static CreditManager creditManager;
	private static CreditRepository creditRepository;
	private static PaymentDetector paymentDetector;

	@Override
	public void onInitializeClient() {
		LOGGER.info("[CreditManager] Initialisierung...");

		FabricLoader.getInstance().getModContainer("creditmanager").ifPresent(container -> {
			ResourceManagerHelper.registerBuiltinResourcePack(
					Identifier.of("creditmanager", "resources"),
					container,
					ResourcePackActivationType.ALWAYS_ENABLED
			);
		});

		FileManager.initialize();

		creditRepository = new CreditRepository();
		creditRepository.load();

		creditManager = new CreditManager(creditRepository);

		TransactionRepository.getInstance().load();

		paymentDetector = new PaymentDetector();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			if (creditManager != null) {
				CreditManagerCommand.register(dispatcher, creditManager);
			} else {
				LOGGER.error("CreditManager ist null beim Command-Setup!");
			}
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			if (paymentDetector != null) {
				paymentDetector.process(message.getString());
			}
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && paymentDetector != null) {
				paymentDetector.process(message.getString());
			}
		});

		LOGGER.info("[CreditManager] Bereit.");
	}
}