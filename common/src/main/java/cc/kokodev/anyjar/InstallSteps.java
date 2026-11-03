package cc.kokodev.anyjar;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Executes mcjars.app's generic install-step format: a list of step groups, each a list of
 * {@code download}/{@code unzip}/{@code remove} steps run in order. This is the same format
 * mcjars uses for every piece of software it serves (a single "download as server.jar" step
 * for things like Paper/Vanilla/Velocity, or download+unzip+cleanup for installer-based
 * software like Forge/NeoForge) so one executor covers all of them.
 */
final class InstallSteps {
	private InstallSteps() {
	}

	@SuppressWarnings("unchecked")
	static void run(Logger logger, List<Object> stepGroups) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.build();

		for (Object groupObj : stepGroups) {
			for (Object stepObj : (List<Object>) groupObj) {
				Map<String, Object> step = (Map<String, Object>) stepObj;
				String type = String.valueOf(step.get("type"));
				switch (type) {
					case "download" -> download(client, logger, step);
					case "unzip" -> unzip(logger, step);
					case "remove" -> remove(logger, step);
					default -> logger.warn("Unknown install step type '{}', skipping.", type);
				}
			}
		}
	}

	private static void download(HttpClient client, Logger logger, Map<String, Object> step) throws IOException, InterruptedException {
		String url = String.valueOf(step.get("url"));
		Path file = Path.of(String.valueOf(step.get("file")));
		logger.info("Downloading {}", file);

		if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(10))
				.GET()
				.build();
		HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(file,
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE));
		if (response.statusCode() / 100 != 2) {
			Files.deleteIfExists(file);
			throw new IOException("Failed to download " + url + ", HTTP " + response.statusCode());
		}
	}

	private static void unzip(Logger logger, Map<String, Object> step) throws IOException {
		Path archive = Path.of(String.valueOf(step.get("file")));
		Path destination = Path.of(String.valueOf(step.get("location")));
		logger.info("Extracting {} into {}", archive, destination);

		Files.createDirectories(destination);
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				Path target = destination.resolve(entry.getName()).normalize();
				if (!target.startsWith(destination.normalize())) {
					throw new IOException("Zip entry escapes destination: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					if (target.getParent() != null) {
						Files.createDirectories(target.getParent());
					}
					Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
				}
				zip.closeEntry();
			}
		}
	}

	private static void remove(Logger logger, Map<String, Object> step) throws IOException {
		Path target = Path.of(String.valueOf(step.get("location")));
		if (!Files.exists(target)) {
			return;
		}
		logger.info("Removing {}", target);
		if (Files.isDirectory(target)) {
			try (var walk = Files.walk(target)) {
				walk.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.delete(path);
					} catch (IOException ignored) {
						// best effort cleanup
					}
				});
			}
		} else {
			Files.delete(target);
		}
	}
}
