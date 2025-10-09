package link.infra.packwiz.installer.bootstrap;

import org.apache.commons.cli.*;

import java.util.ArrayList;
import java.util.List;

public class ArgParser {
    public static final Options options = new Options();

    static {
        options.addOption(null, "bootstrap-update-url", true, "Github API URL for checking for updates");
        options.addOption(null, "bootstrap-update-token", true, "Github API Access Token, for private repositories");
        options.addOption(null, "bootstrap-no-update", false, "Don't update packwiz-installer");
        options.addOption(null, "bootstrap-main-jar", true, "Location of the packwiz-installer JAR file");
        options.addOption("g", "no-gui", false, "Don't display a GUI to show update progress");
        options.addOption("h", "help", false, "Display this message");
    }

    public static String[] filterArgs(String[] args) {
        List<String> argsList = new ArrayList<>(args.length);
        boolean gotURL = false;
        boolean expectValue = false;
        for (String arg : args) {
            if (expectValue) {
                argsList.add(arg);
                expectValue = false;
                continue;
            }
            if (arg.startsWith("-")) {
                // This is an option - only include if recognized
                if (options.hasOption(arg.replaceFirst("^--?", ""))) {
                    argsList.add(arg);
                    if (options.getOption(arg.replaceFirst("^--?", "")).hasArg()) {
                        expectValue = true;
                    }
                }
                // else: skip unrecognized options (like JVM options)
            } else {
                if (arg.startsWith("http") && !gotURL) {
                    argsList.add(arg);
                    gotURL = true;
                }
                // else: skip non-URL positional arguments
            }
        }
        return argsList.toArray(new String[0]);
    }
}
