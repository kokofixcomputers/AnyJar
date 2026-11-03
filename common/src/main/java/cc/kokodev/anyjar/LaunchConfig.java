package cc.kokodev.anyjar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The server software choice made once by {@link SetupWizard}, persisted so later boots
 * skip straight to launching it. Delete this file to be asked again.
 */
final class LaunchConfig {
	static final Path FILE = Path.of("launcher.properties");

	final String type;
	final String mcVersion; // null if not applicable to this software (e.g. Velocity)
	final String buildName;
	final String installationJson; // the chosen build's raw "installation" steps, mcjars.app format

	private LaunchConfig(String type, String mcVersion, String buildName, String installationJson) {
		this.type = type;
		this.mcVersion = mcVersion;
		this.buildName = buildName;
		this.installationJson = installationJson;
	}

	static boolean exists() {
		return Files.exists(FILE);
	}

	static LaunchConfig load() throws IOException {
		Properties props = new Properties();
		try (var in = Files.newInputStream(FILE)) {
			props.load(in);
		}
		return new LaunchConfig(
				props.getProperty("type"),
				props.getProperty("mcVersion"),
				props.getProperty("buildName"),
				props.getProperty("installation")
		);
	}

	@SuppressWarnings("unchecked")
	static LaunchConfig of(String type, String mcVersion, Map<String, Object> build) {
		String buildName = String.valueOf(build.get("name"));
		String installationJson = Json.write(build.get("installation"));
		return new LaunchConfig(type, mcVersion, buildName, installationJson);
	}

	@SuppressWarnings("unchecked")
	List<Object> installationSteps() {
		return (List<Object>) Json.parse(installationJson);
	}

	void save() throws IOException {
		Properties props = new Properties();
		props.setProperty("type", type);
		if (mcVersion != null) {
			props.setProperty("mcVersion", mcVersion);
		}
		props.setProperty("buildName", buildName);
		props.setProperty("installation", installationJson);
		try (var out = Files.newOutputStream(FILE)) {
			props.store(out, "Chosen server software. Delete this file to be asked again on next boot.");
		}
	}
}
