package cc.kokodev.anyjar.neoforge;

import cc.kokodev.anyjar.AnyJarLauncher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge's earliest easy hook: the mod's own constructor, run while NeoForge is
 * constructing mods during its loading phase (well before the dedicated server binds
 * its port or loads a world). All the actual work lives in {@link AnyJarLauncher},
 * shared as-is with the Fabric entrypoint; this class is just the NeoForge-specific
 * "are we a dedicated server, and should this run at all" glue.
 */
@Mod("anyjar")
public class AnyJarNeoForge {
	public AnyJarNeoForge() {
		if (FMLEnvironment.getDist() != Dist.DEDICATED_SERVER) {
			return;
		}
		AnyJarLauncher.run();
	}
}
