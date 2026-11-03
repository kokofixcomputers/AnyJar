package cc.kokodev.anyjar;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * First-run interactive setup: pick a server software, a version, and (if applicable) a
 * Minecraft version, using the public mcjars.app catalog. The choice is written to
 * {@link LaunchConfig} so every later boot skips straight to launching it.
 */
final class SetupWizard {
	private static final String RESET = "\u001B[0m";
	private static final String BOLD = "\u001B[1m";
	private static final String CYAN = "\u001B[36m";
	private static final String BOLD_CYAN = "\u001B[1;36m";
	private static final String GREEN = "\u001B[32m";
	private static final String BOLD_GREEN = "\u001B[1;32m";
	private static final String YELLOW = "\u001B[33m";
	private static final String BOLD_YELLOW = "\u001B[1;33m";
	private static final String RED = "\u001B[31m";
	private static final String GRAY = "\u001B[90m";

	private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

	private final Logger logger;
	private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
	private final McJarsApi api = new McJarsApi();

	SetupWizard(Logger logger) {
		this.logger = logger;
	}

	@SuppressWarnings("unchecked")
	LaunchConfig run() throws IOException, InterruptedException {
		printBanner();

		Map<String, Object> types = api.types();
		List<String> order = new ArrayList<>(types.keySet());

		printSoftwareMenu(types, order);
		String type = promptSoftware(types, order);
		Map<String, Object> typeInfo = (Map<String, Object>) types.get(type);

		System.out.println();
		System.out.println(BOLD_GREEN + "  ✓ " + RESET + "Selected " + BOLD + displayName(typeInfo) + RESET);

		Map<String, Object> versions = api.versions(type);
		boolean mcVersionApplicable = isMcVersionApplicable(typeInfo);

		String mcVersion = null;
		Map<String, Object> build;

		if (mcVersionApplicable) {
			mcVersion = promptVersion("Minecraft version", sortedByRecency(versions));
			build = promptBuild(type, mcVersion);
		} else {
			String version = promptVersion("Version", sortedByRecency(versions));
			Map<String, Object> versionEntry = (Map<String, Object>) versions.get(version);
			build = (Map<String, Object>) versionEntry.get("latest");
		}

		LaunchConfig config = LaunchConfig.of(type, mcVersion, build);
		config.save();

		printSummary(typeInfo, mcVersion, String.valueOf(build.get("name")));
		return config;
	}

	// ---- menu / prompts ----------------------------------------------------

	@SuppressWarnings("unchecked")
	private void printSoftwareMenu(Map<String, Object> types, List<String> order) {
		List<String> lines = new ArrayList<>();
		lines.add(BOLD_YELLOW + "Choose the server software to run" + RESET);
		lines.add(GRAY + "Type a number or the name (e.g. \"2\" or \"paper\")" + RESET);
		printBox(lines);
		System.out.println();

		for (int i = 0; i < order.size(); i++) {
			Map<String, Object> info = (Map<String, Object>) types.get(order.get(i));
			String index = String.format("%2d", i + 1);
			System.out.println("  " + BOLD_CYAN + "[" + index + "]" + RESET + " " + displayName(info)
					+ GRAY + " - " + String.valueOf(info.get("description")) + RESET);
		}
		System.out.println();
	}

	private String promptSoftware(Map<String, Object> types, List<String> order) throws IOException {
		while (true) {
			String answer = prompt("Software");
			if (answer.isEmpty()) {
				continue;
			}

			try {
				int index = Integer.parseInt(answer);
				if (index >= 1 && index <= order.size()) {
					return order.get(index - 1);
				}
			} catch (NumberFormatException ignored) {
				// not a number, fall through to name matching
			}

			for (String key : order) {
				@SuppressWarnings("unchecked")
				Map<String, Object> info = (Map<String, Object>) types.get(key);
				if (key.equalsIgnoreCase(answer) || String.valueOf(info.get("name")).equalsIgnoreCase(answer)) {
					return key;
				}
			}

			error("Unknown software \"" + answer + "\", pick a number from the list or type its name.");
		}
	}

	private String promptVersion(String label, List<String> recent) throws IOException {
		if (!recent.isEmpty()) {
			System.out.println(GRAY + "  Recent: " + String.join(", ", recent.subList(0, Math.min(8, recent.size()))) + RESET);
		}
		while (true) {
			String answer = prompt(label + " (or \"latest\")");
			if (answer.isEmpty() || answer.equalsIgnoreCase("latest")) {
				return recent.get(0);
			}
			if (recent.contains(answer)) {
				return answer;
			}
			error("Unknown version \"" + answer + "\". Pick one of the versions above, or \"latest\".");
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> promptBuild(String type, String mcVersion) throws IOException, InterruptedException {
		List<Object> builds = api.builds(type, mcVersion);
		if (builds.isEmpty()) {
			throw new IOException("No builds available for " + type + " " + mcVersion);
		}

		List<Map<String, Object>> typed = new ArrayList<>();
		for (Object build : builds) {
			typed.add((Map<String, Object>) build);
		}

		System.out.println(GRAY + "  Recent builds: " + recentBuildNames(typed) + RESET);

		while (true) {
			String answer = prompt("Build (or \"latest\")");
			if (answer.isEmpty() || answer.equalsIgnoreCase("latest")) {
				return typed.get(0);
			}
			String normalized = answer.startsWith("#") ? answer.substring(1) : answer;
			for (Map<String, Object> build : typed) {
				String name = String.valueOf(build.get("name"));
				String number = String.valueOf(build.get("buildNumber"));
				if (name.equalsIgnoreCase(answer) || name.equalsIgnoreCase("#" + normalized) || number.equals(normalized)) {
					return build;
				}
			}
			error("Unknown build \"" + answer + "\". Pick one of the recent builds above, or \"latest\".");
		}
	}

	private String recentBuildNames(List<Map<String, Object>> builds) {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < Math.min(5, builds.size()); i++) {
			names.add(String.valueOf(builds.get(i).get("name")));
		}
		return String.join(", ", names);
	}

	// ---- helpers -------------------------------------------------------------

	private boolean isMcVersionApplicable(Map<String, Object> typeInfo) {
		Object versionsObj = typeInfo.get("versions");
		if (versionsObj instanceof Map<?, ?> versionsInfo) {
			Object minecraftCount = versionsInfo.get("minecraft");
			return minecraftCount instanceof Number n && n.longValue() > 0;
		}
		return true;
	}

	/** Version/release keys, newest first, best-effort using each entry's "created" timestamp. */
	private List<String> sortedByRecency(Map<String, Object> versions) {
		List<String> keys = new ArrayList<>(versions.keySet());
		keys.sort(Comparator.comparing((String key) -> createdOf(versions, key)).reversed());
		return keys;
	}

	@SuppressWarnings("unchecked")
	private String createdOf(Map<String, Object> versions, String key) {
		Map<String, Object> entry = (Map<String, Object>) versions.get(key);
		Object created = entry.get("created");
		if (created == null) {
			Object latest = entry.get("latest");
			if (latest instanceof Map<?, ?> latestMap) {
				created = latestMap.get("created");
			}
		}
		return created == null ? "" : String.valueOf(created);
	}

	private String displayName(Map<String, Object> info) {
		return String.valueOf(info.get("name"));
	}

	private String prompt(String label) throws IOException {
		System.out.print("  " + BOLD_GREEN + "? " + RESET + label + BOLD + " > " + RESET);
		System.out.flush();
		String line = in.readLine();
		return line == null ? "" : line.trim();
	}

	private void error(String message) {
		System.out.println("  " + RED + "✗ " + message + RESET);
	}

	// ---- banner / box drawing -------------------------------------------------

	private void printBanner() {
		List<String> lines = new ArrayList<>();
		lines.add(BOLD_YELLOW + "◆ FIRST-TIME SERVER SETUP ◆" + RESET);
		lines.add(GRAY + "Pick what to run once; this won't ask again." + RESET);
		printBox(lines);
		System.out.println();
	}

	private void printSummary(Map<String, Object> typeInfo, String mcVersion, String buildName) {
		System.out.println();
		List<String> lines = new ArrayList<>();
		lines.add(BOLD_GREEN + "◆ SETUP COMPLETE ◆" + RESET);
		lines.add(CYAN + "Software: " + RESET + BOLD + displayName(typeInfo) + RESET);
		if (mcVersion != null) {
			lines.add(CYAN + "Minecraft version: " + RESET + BOLD + mcVersion + RESET);
		}
		lines.add(CYAN + "Build: " + RESET + BOLD + buildName + RESET);
		lines.add(GRAY + "Saved to " + LaunchConfig.FILE + " - delete it to be asked again." + RESET);
		printBox(lines);
		System.out.println();
	}

	/** Draws a rounded ANSI box around the given (already-colored) lines, auto-sized. */
	private void printBox(List<String> lines) {
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, visibleLength(line));
		}
		width += 4;

		StringBuilder top = new StringBuilder(CYAN + "╭");
		StringBuilder bottom = new StringBuilder(CYAN + "╰");
		for (int i = 0; i < width; i++) {
			top.append('─');
			bottom.append('─');
		}
		top.append('╮').append(RESET);
		bottom.append('╯').append(RESET);

		System.out.println(top);
		for (String line : lines) {
			int pad = width - visibleLength(line);
			int left = pad / 2;
			int right = pad - left;
			System.out.println(CYAN + "│" + RESET + " ".repeat(left + 1) + line + " ".repeat(right + 1) + CYAN + "│" + RESET);
		}
		System.out.println(bottom);
	}

	private int visibleLength(String text) {
		return ANSI.matcher(text).replaceAll("").length();
	}
}
