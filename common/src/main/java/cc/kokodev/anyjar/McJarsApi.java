package cc.kokodev.anyjar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the public mcjars.app API (versions.mcjars.app), used to list and
 * download any server software it knows about (vanilla, paper, purpur, fabric, forge,
 * neoforge, velocity, ...), not just Velocity.
 */
final class McJarsApi {
	private static final String BASE = "https://versions.mcjars.app/api/v1";

	/**
	 * Limbo (anti-bot / placeholder) server software mcjars.app's build endpoints serve
	 * (e.g. GET /builds/NANOLIMBO works fine) but its /types listing doesn't mention, so they
	 * have to be added by hand. "minecraft"/"project" version counts are just placeholders
	 * (>0 vs. 0) so {@code isMcVersionApplicable} picks the right prompt: LoohpLimbo's builds
	 * are keyed by Minecraft version, Nanolimbo's by its own release version (like Velocity).
	 */
	private static final Map<String, Object> EXTRA_TYPES = extraTypes();

	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();

	/** modid (e.g. "PAPER") -> info (name, description, versions.minecraft/project counts, ...) */
	@SuppressWarnings("unchecked")
	Map<String, Object> types() throws IOException, InterruptedException {
		Map<String, Object> response = (Map<String, Object>) Json.parse(get(BASE + "/types"));
		Map<String, Object> types = new LinkedHashMap<>((Map<String, Object>) response.get("types"));
		types.putAll(EXTRA_TYPES);
		return types;
	}

	private static Map<String, Object> extraTypes() {
		Map<String, Object> types = new LinkedHashMap<>();
		types.put("LOOHPLIMBO", type("LoohpLimbo",
				"A lightweight limbo server (holds players in a fake world, e.g. behind a proxy while the real backend is down).",
				1, 0));
		types.put("NANOLIMBO", type("NanoLimbo",
				"A high-performance, low-footprint limbo server for holding large numbers of connections.",
				0, 1));
		return types;
	}

	private static Map<String, Object> type(String name, String description, long mcVersionCount, long projectVersionCount) {
		Map<String, Object> versions = new LinkedHashMap<>();
		versions.put("minecraft", mcVersionCount);
		versions.put("project", projectVersionCount);

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", name);
		info.put("description", description);
		info.put("versions", versions);
		return info;
	}

	/**
	 * version string -> info, where "version string" is a Minecraft version for most
	 * software, or the software's own release line for proxies like Velocity that have no
	 * Minecraft version concept. Each entry's "latest" field is the newest build for it.
	 */
	@SuppressWarnings("unchecked")
	Map<String, Object> versions(String type) throws IOException, InterruptedException {
		Map<String, Object> response = (Map<String, Object>) Json.parse(get(BASE + "/builds/" + type));
		return (Map<String, Object>) response.get("versions");
	}

	/** All known builds for a given type + Minecraft version, newest first. */
	@SuppressWarnings("unchecked")
	List<Object> builds(String type, String mcVersion) throws IOException, InterruptedException {
		Map<String, Object> response = (Map<String, Object>) Json.parse(get(BASE + "/builds/" + type + "/" + mcVersion));
		return (List<Object>) response.get("builds");
	}

	private String get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
		}
		return response.body();
	}
}
