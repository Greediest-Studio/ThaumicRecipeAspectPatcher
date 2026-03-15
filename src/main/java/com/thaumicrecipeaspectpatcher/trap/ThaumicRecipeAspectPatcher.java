package com.thaumicrecipeaspectpatcher.trap;

import com.thaumicrecipeaspectpatcher.trap.config.TRAPConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main mod class for ThaumicRecipeAspectPatcher.
 *
 * Dependencies note:
 *   - thaumcraft: required on both sides
 *   - jei: OPTIONAL and client-preferring; we use 'after:jei' so the mod
 *     loads fine on dedicated servers without JEI installed.
 *
 * We intentionally do NOT reference TRAPJeiPlugin here - that class imports
 * mezz.jei.api.* and would cause a ClassNotFoundException on any environment
 * where JEI is absent if we forced its classloading during preInit.
 * Instead, TRAPJeiPlugin reads its config from TRAPConfig.INSTANCE (a static
 * field on a JEI-independent class), and JEI itself handles discovering and
 * invoking the plugin via the @JEIPlugin annotation.
 */
@Mod(
    modid    = Tags.MOD_ID,
    name     = Tags.MOD_NAME,
    version  = Tags.VERSION,
    dependencies = "required-after:thaumcraft;after:jei"
)
public class ThaumicRecipeAspectPatcher {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        TRAPConfig cfg = new TRAPConfig();
        cfg.load(event.getModConfigurationDirectory());
        // Store in static field - TRAPJeiPlugin reads this without requiring
        // the main class to hold any JEI reference.
        TRAPConfig.INSTANCE = cfg;
        LOGGER.info("{} pre-init complete. Config loaded.", Tags.MOD_NAME);
    }
}
