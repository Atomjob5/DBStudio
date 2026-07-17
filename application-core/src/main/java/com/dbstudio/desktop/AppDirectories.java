package com.dbstudio.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public final class AppDirectories {
    private AppDirectories() {
    }

    public static Path dataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "DBStudio");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.trim().isEmpty()) {
                return Paths.get(appData, "DBStudio");
            }
        }
        return Paths.get(System.getProperty("user.home"), ".dbstudio");
    }
}
