package link.infra.packwiz.installer.bootstrap;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.apache.commons.cli.*;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class Bootstrap {

	private static final String DEFAULT_UPDATE_URL = "https://api.github.com/repos/codecraft3r/packwiz-installer/releases/latest";
	public static final String JAR_NAME = "packwiz-installer.jar";

	private static String updateURL = DEFAULT_UPDATE_URL;
	private static boolean skipUpdate = false;
	private static boolean useGUI = true;
	private static String jarPath = null;
	private static String accessToken = null;

	public static void init(String[] args) {
		try {
			parseOptions(args);
		} catch (ParseException e) {
			showError(e, "There was an error parsing command line arguments:");
			System.exit(1);
		}
		
		// Filter args to remove unrecognized options (like JVM options) before passing to packwiz-installer
		String[] filteredArgs = ArgParser.filterArgs(args);

		if (jarPath == null) {
			jarPath = JAR_NAME;
		}

		if (useGUI) {
			EventQueue.invokeLater(() -> {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (Exception e) {
					// Ignore the exceptions, just continue using the ugly L&F
				}
			});
		}

		if (skipUpdate) {
			try {
				LoadJAR.start(filteredArgs, jarPath);
			} catch (ClassNotFoundException e) {
				showError(e, "packwiz-installer cannot be found, or there was an error loading it:");
				System.exit(1);
			} catch (Exception e) {
				showError(e, "There was an error loading packwiz-installer:");
				System.exit(1);
			}
			return;
		}

		try {
			doUpdate();
		} catch (Exception e) {
			showError(e, "There was an error downloading packwiz-installer:");
		}

		try {
			LoadJAR.start(filteredArgs, jarPath);
		} catch (Exception e) {
			showError(e, "There was an error loading packwiz-installer (did it download properly?):");
			System.exit(1);
		}
	}

	private static void doUpdate() throws IOException, GithubException {
		String currVersion = LoadJAR.getVersion(jarPath);
		Release ghRelease = requestRelease();
		
		if (ghRelease == null) {
			return;
		}

		System.out.println("Current version is: " + currVersion);
		System.out.println("New version is: " + ghRelease.tagName);
		if (!ghRelease.tagName.equals(currVersion)) {
			System.out.println("Attempting to update...");
			RollbackHandler backup = new RollbackHandler(jarPath);

			try {
				downloadUpdate(ghRelease.downloadURL, ghRelease.assetURL, jarPath);
			} catch (InterruptedIOException e) {
				// User did this, don't show the error
				try {
					backup.rollback();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				return;
			} catch (IOException e) {
				showError(e, "Update download failed, attempting to rollback:");
				try {
					backup.rollback();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				return;
			}

			System.out.println("Update successful!");
		} else {
			System.out.println("Already up to date!");
		}
	}

	private static void showError(Exception e, String message) {
		if (useGUI) {
			e.printStackTrace();
			try {
				EventQueue.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
					message + "\n" + e.getClass().getCanonicalName() + ": " + e.getMessage(),
					"packwiz-installer-bootstrap", JOptionPane.ERROR_MESSAGE));
			} catch (InterruptedException | InvocationTargetException ex) {
				System.out.println("Unexpected interruption while showing error message");
				ex.printStackTrace();
			}
		} else {
			System.out.println(message);
			e.printStackTrace();
		}
	}

	private static void parseOptions(String[] args) throws ParseException {
        Options options = ArgParser.options;
		CommandLineParser parser = new DefaultParser();
		// Filter args to only parse bootstrap options, but we need to do this carefully
		// to avoid removing positional arguments that packwiz-installer needs
		CommandLine cmd = parser.parse(options, ArgParser.filterArgs(args));

		if (cmd.hasOption("bootstrap-main-jar")) {
			jarPath = cmd.getOptionValue("bootstrap-main-jar");
		}

		if (cmd.hasOption("help")) {
			HelpFormatter formatter = new HelpFormatter();
			// Add options from packwiz-installer JAR, if it is present
			boolean jarLoaded = LoadJAR.addOptions(options, jarPath);
			formatter.printHelp("java -jar packwiz-installer-bootstrap.jar", options);
			if (!jarLoaded) {
				System.out.println("Options for packwiz-installer will be visible once it has been downloaded.");
			}
			System.exit(0);
		}

		if (cmd.hasOption("bootstrap-update-url")) {
			updateURL = cmd.getOptionValue("bootstrap-update-url");
		}
		
		if (cmd.hasOption("bootstrap-update-token")) {
			accessToken = cmd.getOptionValue("bootstrap-update-token");
		}

		if (cmd.hasOption("bootstrap-no-update")) {
			skipUpdate = true;
		}

		if (cmd.hasOption("no-gui")) {
			useGUI = false;
		}
	}

	private static class Release {
		String tagName = null;
		String downloadURL = null;
		String assetURL = null;
	}

	private static class GithubException extends Exception {
		private static final long serialVersionUID = 3843811090801607241L;

		public GithubException() {
			super("Invalid Github API response");
		}

		public GithubException(String message) {
			super("Invalid Github API response: " + message);
		}
	}

	private static Release requestRelease() throws IOException, GithubException {
		Release rel = new Release();

		URL url = new URL(updateURL);
		URLConnection conn = url.openConnection();

		addAuthorizationHeader(conn);
		// 30 second read timeout
		conn.setReadTimeout(30 * 1000);
		InputStream in;
		if (useGUI) {
			in = new ConnMonitorInputStream(conn, "Checking for packwiz-installer updates...", null);
		} else {
			in = conn.getInputStream();
		}
		Reader streamReader = new InputStreamReader(in);
		JsonObject object;
		try {
			object = Json.parse(streamReader).asObject();
		} catch (InterruptedIOException e) {
			System.out.println("Update check cancelled!");
			return null;
		}
		streamReader.close();

		rel.tagName = getStringProperty("tag_name", object, "Tag name");

		JsonValue assets = object.get("assets");
		if (assets == null || !assets.isArray()) {
			throw new GithubException("Assets array cannot be found");
		}
		for (JsonValue assetValue : assets.asArray()) {
			if (!assetValue.isObject()) {
				throw new GithubException();
			}

			JsonObject asset = assetValue.asObject();
			String name = getStringProperty("name", asset, "Asset name");

			if (!name.equalsIgnoreCase(JAR_NAME)) {
				continue;
			}

			rel.downloadURL = getAssetUrl("browser_download_url", asset);
			rel.assetURL = getAssetUrl("url", asset);
			break;
		}
		if (rel.tagName == null) {
			throw new GithubException("Latest release asset cannot be found");
		}

		return rel;
	}

	private static String getAssetUrl(String property, JsonObject asset) throws GithubException {
		return getStringProperty(property, asset, "Asset Download URL property");
	}
	
	private static String getStringProperty(String property, JsonObject obj, String displayName) throws GithubException {
		JsonValue value = obj.get(property);
		if (value == null || !value.isString()) {
			throw new GithubException(displayName + " (" + property + ") cannot be found");
		}
		return value.asString();
	}

	private static void downloadUpdate(String downloadURL, String assetURL, String path) throws IOException {
		URL url = new URL(downloadURL);
		URLConnection conn = url.openConnection();

		addAuthorizationHeader(conn);
		conn.addRequestProperty("Accept", "application/octet-stream");
		// 30 second read timeout
		conn.setReadTimeout(30 * 1000);
		InputStream in;
		if (useGUI) {
			in = new ConnMonitorInputStream(conn, "Updating packwiz-installer...", null);
		} else {
			in = conn.getInputStream();
		}
		Files.copy(in, Paths.get(path), StandardCopyOption.REPLACE_EXISTING);
		in.close();
	}

	private static void addAuthorizationHeader(URLConnection conn) {
		if (accessToken != null) {
			// Authenticated downloads use the assetURL
			conn.addRequestProperty("Authorization", accessToken);
		}
	}

}
