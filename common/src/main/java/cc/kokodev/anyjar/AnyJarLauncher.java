package cc.kokodev.anyjar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loader-agnostic implementation of the "hijack the server's own startup" trick: on first
 * boot, runs an interactive wizard ({@link SetupWizard}) to pick any server software
 * mcjars.app knows about (Velocity, Paper, Fabric, Forge, NeoForge, ...), installs it,
 * then every boot after that just launches the chosen software as a child process with the
 * console wired straight through - and once it exits, exits this JVM with the same code
 * instead of ever letting the real loader/game continue booting.
 *
 * <p>Shared as-is between the Fabric and NeoForge entrypoints; it has no dependency on
 * either loader, and never reveals which of them it's running under.</p>
 */
public final class AnyJarLauncher {
	public static final String MOD_ID = "anyjar";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Path SERVER_JAR = Path.of("server.jar");
	private static final Path PATCHED_LOG_CONFIG = Path.of("velocity-log4j2-patched.xml");

	// Matches Velocity's startup banner, e.g. "Done (0.85s)!"
	private static final Pattern DONE_MESSAGE = Pattern.compile("^Done \\(([0-9.]+s)\\)!$");
	private static final String DONE_REPLACEMENT =
			"Done ($1)! For help, type &quot;help&quot; or &quot;?&quot; "
					+ "(no such commands exist on Velocity; this line only marks the proxy as finished starting)";
	private static final Pattern MSG_TOKEN = Pattern.compile("%msg(\\{[^}]*})?");

	private static final Path SERVER_PROPERTIES = Path.of("server.properties");
	private static final Path VELOCITY_TOML = Path.of("velocity.toml");
	private static final Pattern SERVER_PORT = Pattern.compile("(?m)^\\s*server-port\\s*=\\s*(\\d+)\\s*$");
	private static final Pattern BIND_LINE = Pattern.compile("(?m)^(\\s*bind\\s*=\\s*\")([^\"]*)(\"\\s*)$");

	private AnyJarLauncher() {
	}

	/**
	 * On first boot, runs the setup wizard and saves the choice. Every boot: installs the
	 * chosen software if needed, launches it, blocks on it, then {@link System#exit} with
	 * its exit code. Never returns normally.
	 */
	public static void run() {
		int exitCode;
		try {
			LaunchConfig config = LaunchConfig.exists() ? LaunchConfig.load() : new SetupWizard(LOGGER).run();

			LOGGER.info("Starting {}...", config.type);
			ensureServerInstalled(config);

			if (isVelocity(config)) {
				syncVelocityPort();
			}

			exitCode = runServer(config);
		} catch (Exception e) {
			LOGGER.error("Failed to launch server software", e);
			exitCode = 1;
		}

		LOGGER.info("Server process exited with code {}, shutting down.", exitCode);
		System.exit(exitCode);
	}

	private static boolean isVelocity(LaunchConfig config) {
		return "VELOCITY".equalsIgnoreCase(config.type);
	}

	private static void ensureServerInstalled(LaunchConfig config) throws IOException, InterruptedException {
		if (Files.exists(SERVER_JAR) && Files.size(SERVER_JAR) > 0) {
			LOGGER.info("server.jar already present, skipping install.");
			return;
		}

		LOGGER.info("Installing {} ({})...", config.type, config.buildName);
		InstallSteps.run(LOGGER, config.installationSteps());

		if (!Files.exists(SERVER_JAR) || Files.size(SERVER_JAR) == 0) {
			throw new IOException("Install finished but server.jar was not produced.");
		}
	}

	/**
	 * Hosting panels write the port they actually want into server.properties
	 * (server-port=...), not into velocity.toml. Before Velocity boots, mirror that
	 * port into velocity.toml's bind address so the two stay in sync. If velocity.toml
	 * doesn't exist yet, seed it with just the bind line; Velocity fills in the rest of
	 * the defaults on first boot. Any failure here is non-fatal, logged, and skipped.
	 */
	private static void syncVelocityPort() {
		try {
			if (!Files.exists(SERVER_PROPERTIES)) {
				LOGGER.debug("No server.properties found, leaving velocity.toml's bind port untouched.");
				return;
			}

			String properties = Files.readString(SERVER_PROPERTIES, StandardCharsets.UTF_8);
			Matcher portMatcher = SERVER_PORT.matcher(properties);
			if (!portMatcher.find()) {
				LOGGER.debug("No server-port found in server.properties, leaving velocity.toml's bind port untouched.");
				return;
			}
			String port = portMatcher.group(1);
			String desiredBind = "0.0.0.0:" + port;

			if (!Files.exists(VELOCITY_TOML)) {
				Files.writeString(VELOCITY_TOML, "bind = \"" + desiredBind + "\"\n", StandardCharsets.UTF_8);
				LOGGER.info("velocity.toml didn't exist yet, seeded it with bind = \"{}\" (from server.properties server-port={}).", desiredBind, port);
				return;
			}

			String toml = Files.readString(VELOCITY_TOML, StandardCharsets.UTF_8);
			Matcher bindMatcher = BIND_LINE.matcher(toml);
			if (!bindMatcher.find()) {
				LOGGER.warn("Could not find a 'bind' line in velocity.toml, leaving it untouched.");
				return;
			}
			String currentBind = bindMatcher.group(2);
			if (currentBind.equals(desiredBind)) {
				LOGGER.debug("velocity.toml's bind ({}) already matches server.properties' server-port, nothing to do.", currentBind);
				return;
			}

			String updatedToml = bindMatcher.replaceFirst("$1" + Matcher.quoteReplacement(desiredBind) + "$3");
			Files.writeString(VELOCITY_TOML, updatedToml, StandardCharsets.UTF_8);
			LOGGER.info("Rewrote velocity.toml bind from \"{}\" to \"{}\" to match server.properties' server-port={}.", currentBind, desiredBind, port);
		} catch (IOException e) {
			LOGGER.warn("Failed to sync velocity.toml's bind port with server.properties", e);
		}
	}

	private static int runServer(LaunchConfig config) throws IOException, InterruptedException {
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		String javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();

		List<String> command = new ArrayList<>();
		command.add(javaExecutable);

		if (isVelocity(config)) {
			Path patchedLogConfig = preparePatchedLogConfig();
			if (patchedLogConfig != null) {
				command.add("-Dlog4j.configurationFile=" + patchedLogConfig.toAbsolutePath());
			}
		}

		command.add("-jar");
		command.add(SERVER_JAR.toString());

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.inheritIO();

		LOGGER.info("Launching: {}", String.join(" ", processBuilder.command()));

		Process process = processBuilder.start();
		Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));

		return process.waitFor();
	}

	/**
	 * Pulls Velocity's own bundled log4j2.xml out of server.jar and rewrites every
	 * pattern layout's {@code %msg} token so the "Done (X.XXs)!" startup line also prints
	 * a fake "For help, type ..." suffix, matching what process managers that only look
	 * for a vanilla-style startup line expect to see. Returns null (and changes nothing)
	 * if the bundled config can't be found or patched for any reason.
	 */
	private static Path preparePatchedLogConfig() {
		try {
			String originalConfig = readJarEntry(SERVER_JAR, "log4j2.xml");
			if (originalConfig == null) {
				LOGGER.warn("Could not find log4j2.xml inside server.jar, startup banner will not be patched.");
				return null;
			}

			Matcher matcher = MSG_TOKEN.matcher(originalConfig);
			if (!matcher.find()) {
				LOGGER.warn("No %msg token found in server.jar's log4j2.xml, startup banner will not be patched.");
				return null;
			}
			matcher.reset();

			StringBuilder patched = new StringBuilder();
			while (matcher.find()) {
				String modifier = matcher.group(1) == null ? "" : matcher.group(1);
				String replacement = "%replace{%msg" + modifier + "}{" + DONE_MESSAGE.pattern() + "}{" + DONE_REPLACEMENT + "}";
				matcher.appendReplacement(patched, Matcher.quoteReplacement(replacement));
			}
			matcher.appendTail(patched);

			Files.writeString(PATCHED_LOG_CONFIG, patched.toString(), StandardCharsets.UTF_8);
			LOGGER.info("Patched Velocity's startup banner via {}", PATCHED_LOG_CONFIG);
			return PATCHED_LOG_CONFIG;
		} catch (Exception e) {
			LOGGER.warn("Failed to patch Velocity's log4j2 config, startup banner will not be patched.", e);
			return null;
		}
	}

	private static String readJarEntry(Path jar, String entryName) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null) {
				return null;
			}
			try (InputStream in = zip.getInputStream(entry)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
	}
}
