package uwu.lopyluna.create_dd;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import uwu.lopyluna.create_dd.infrastructure.ponder.DesirePonderTags;
import uwu.lopyluna.create_dd.infrastructure.ponder.DesiresPonderIndex;
import uwu.lopyluna.create_dd.registry.DesiresParticleTypes;
import uwu.lopyluna.create_dd.registry.DesiresPartialModels;

public class DesireClient {



    public static void onCtorClient(IEventBus modEventBus, IEventBus forgeEventBus) {
        modEventBus.addListener(DesireClient::clientInit);;
        modEventBus.addListener(DesiresParticleTypes::registerFactories);
    }


    public static void clientInit(final FMLClientSetupEvent event) {

        DesirePonderTags.register();
        DesiresPonderIndex.register();
    }
}
