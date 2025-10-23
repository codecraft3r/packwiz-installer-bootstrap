package link.infra.packwiz.installer.bootstrap;

import link.infra.packwiz.installer.bootstrap.update.UpdateManager;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.ArrayList;

/**
 * Main class+method for the bootstrapper.
 * Has two modes:
 * 1. Detects packwiz.chainload.class property in packwiz-installer-bootstrap.properties or Java System Properties
 * 	 - Then starts itself as a subprocess (required to not initialise AWT in the current process)
 * 	 - Then invokes the main method of the class specified in packwiz.chainload.class
 * 2. Parses command line arguments, and starts an AWT window (if GUI is enabled)
 * TODO: need to relocate minimal-json and commons-cli
 */
public class Main {
	public static void main(String[] args) {
        UpdateManager.INSTANCE.checkForUpdates(false, List.of(args));
        // Check for chainload in Java System Properties
		if (attemptChainload(System.getProperties(), args)) { return; }

		// Try looking in packwiz-installer-bootstrap.properties
		try (FileReader reader = new FileReader("packwiz-installer-bootstrap.properties")) {
			Properties props = new Properties();
			props.load(reader);
			if (attemptChainload(props, args)) {
				return;
			} else {
				throw new RuntimeException("packwiz-installer-bootstrap.properties is invalid");
			}
		} catch (FileNotFoundException ignored) {
			// Ignored - continue trying to start without chainloading
		} catch (IOException e) {
			throw new RuntimeException("Failed to read packwiz-installer-bootstrap.properties", e);
		}

		// Custom CLI arg handling for pack.toml URL construction and passthrough options
		String[] filteredArgs;
		String ghUser = null, ghRepo = null, ghTag = null, urlArg = null;
		String sideName = null;
		boolean noGui = false;

		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("http")) {
				urlArg = args[i];
				// Don't break - continue parsing for -s and -g flags
			} else if ("--user".equals(args[i]) && i + 1 < args.length) {
				ghUser = args[++i];
			} else if ("--repo".equals(args[i]) && i + 1 < args.length) {
				ghRepo = args[++i];
			} else if ("--tag".equals(args[i]) && i + 1 < args.length) {
				ghTag = args[++i];
			} else if ("-s".equals(args[i]) && i + 1 < args.length) {
				sideName = args[++i];
			} else if ("-g".equals(args[i])) {
				noGui = true;
			} // Ignore all other arguments
		}

		// Validate mutually exclusive options
		if (urlArg != null && (ghUser != null || ghRepo != null || ghTag != null)) {
			System.err.println("Error: Cannot specify both a URL and GitHub parameters (--user/--repo/--tag)");
			System.exit(1);
			return;
		}

		List<String> outArgs = new ArrayList<>();
		if (urlArg != null) {
			outArgs.add(urlArg);
		} else if (ghUser != null && ghRepo != null) {
			if (ghTag == null) {
				// Fetch latest release tag from GitHub
				try {
					String apiUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", ghUser, ghRepo);
					HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
					conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
					conn.setRequestProperty("User-Agent", "packwiz-installer-bootstrap");
					if (conn.getResponseCode() != 200) {
						System.err.println("Error: Failed to fetch latest release from GitHub (HTTP " + conn.getResponseCode() + ")");
						System.exit(1);
						return;
					}
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
						JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
						ghTag = json.get("tag_name").getAsString();
					}
				} catch (Exception e) {
					System.err.println("Error: Could not fetch latest release tag from GitHub: " + e.getMessage());
					System.exit(1);
					return;
				}
			}
			String packUrl = String.format(
				"https://raw.githubusercontent.com/%s/%s/refs/tags/%s/pack.toml",
				ghUser, ghRepo, ghTag
			);
			outArgs.add(packUrl);
		} else {
			System.err.println("Error: Provide either one URL argument or --user <GH_USER> --repo <GH_REPO> [--tag <GH_TAG>]");
			System.exit(1);
			return;
		}

		// Append optional passthrough flags
		if (sideName != null) {
			outArgs.add("-s");
			outArgs.add(sideName);
		}
		if (noGui) {
			outArgs.add("-g");
		}

		filteredArgs = outArgs.toArray(new String[0]);
		Bootstrap.init(filteredArgs);
        System.exit(0);
	}

	private static boolean attemptChainload(Properties props, String[] args) {
		// Check for chainload class
		String chainloadClass = props.getProperty("packwiz.chainload.class");

		// Check for chainload jar
		String chainloadJar = props.getProperty("packwiz.chainload.jar");

		if (chainloadClass != null || chainloadJar != null) {
			// TODO: parse args (packwiz.chainload.args.0?)
			List<String> bootstrapArgs = Collections.emptyList();

			if (chainloadClass != null) {
				ChainloadHandler.startChainloadClass(chainloadClass, bootstrapArgs, ArgParser.filterArgs(args));
                System.exit(0);
			} else {
				ChainloadHandler.startChainloadJar(chainloadJar, bootstrapArgs, ArgParser.filterArgs(args));
                System.exit(0);
			}
			return true;
		}
		return false;
	}
}
