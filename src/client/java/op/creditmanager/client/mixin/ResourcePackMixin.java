package op.creditmanager.client.mixin;

import net.minecraft.resource.ResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

@Mixin(ResourcePackManager.class)
public class ResourcePackMixin {

    @Inject(method = "setEnabledProfiles", at = @At("HEAD"), cancellable = true)
    private void onSetEnabledProfiles(Collection<String> profiles, CallbackInfo ci) {
        List<String> neu = new ArrayList<>(profiles);
        String creditPack = "builtin/creditmanager_resources";

        if (neu.contains(creditPack)) {
            neu.remove(creditPack);
            neu.add(creditPack);
            ci.cancel();
            ((ResourcePackManager)(Object)this).setEnabledProfiles(neu);
        }
    }
}