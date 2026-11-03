package cc.kokodev.anyjar.fabric;

import cc.kokodev.anyjar.AnyJarLauncher;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric's earliest entrypoint: fires before Minecraft itself starts. All the actual work
 * lives in {@link AnyJarLauncher}, shared with the NeoForge entrypoint; this class is just
 * the Fabric-specific "are we a dedicated server, and should this run at all" glue.
 */
public class AnyJarPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
			return;
		}
		AnyJarLauncher.run();
	}
}
