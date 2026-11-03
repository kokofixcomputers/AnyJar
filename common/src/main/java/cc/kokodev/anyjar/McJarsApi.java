package cc.kokodev.anyjar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the public mcjars.app API (versions.mcjars.app), used to list and
 * download any server software it knows about (vanilla, paper, purpur, fabric, forge,
 * neoforge, velocity, ...), not just Velocity.
 */
final class McJarsApi {
	private static final String BASE = "https://versions.mcjars.app/api/v1";

	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();

	/** modid (e.g. "PAPER") -> info (name, description, versions.minecraft/project counts, ...) */
	@SuppressWarnings("unchecked")
	Map<String, Object> types() throws IOException, InterruptedException {
		Map<String, Object> response = (Map<String, Object>) Json.parse(get(BASE + "/types"));
		return (Map<String, Object>) response.get("types");
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
