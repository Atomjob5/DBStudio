package com.dbstudio.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class FatJarVerifier {
    private static final String RESOURCE_ROOT = "META-INF/resources/";
    private static final Pattern ASSET = Pattern.compile("(?:src|href)=\"\\./(assets/[^\"]+)\"");

    private FatJarVerifier() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected fat JAR and dist index paths");
        File jarFile = new File(arguments[0]);
        File distIndex = new File(arguments[1]);
        if (!jarFile.isFile() || !distIndex.isFile()) throw new IllegalStateException("Build outputs are missing");
        try (ZipFile jar = new ZipFile(jarFile)) {
            assertNoNestedEditorWeb(jar);
            requireLibrary(jar, "database-mysql-");
            requireLibrary(jar, "database-oracle-");
            requireLibrary(jar, "database-oceanbase-oracle-");
            requireLibrary(jar, "ojdbc8-19.31.0.0");
            requireLibrary(jar, "oceanbase-client-2.4.17");
            ZipEntry indexEntry = require(jar, RESOURCE_ROOT + "index.html");
            byte[] embeddedIndex = read(jar.getInputStream(indexEntry));
            byte[] currentIndex = read(new FileInputStream(distIndex));
            if (!java.util.Arrays.equals(embeddedIndex, currentIndex)) {
                throw new IllegalStateException("Fat JAR index.html does not match the current editor-web build");
            }
            Matcher matcher = ASSET.matcher(new String(embeddedIndex, StandardCharsets.UTF_8));
            int assets = 0;
            while (matcher.find()) { require(jar, RESOURCE_ROOT + matcher.group(1)); assets++; }
            if (assets < 2) throw new IllegalStateException("Built index.html does not reference JavaScript and CSS assets");
        }
    }

    private static void assertNoNestedEditorWeb(ZipFile jar) {
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("BOOT-INF/lib/editor-web-") && name.endsWith(".jar")) {
                throw new IllegalStateException("Fat JAR contains stale nested frontend artifact: " + name);
            }
        }
    }

    private static ZipEntry require(ZipFile jar, String name) {
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) throw new IllegalStateException("Fat JAR entry is missing: " + name);
        return entry;
    }

    private static void requireLibrary(ZipFile jar, String prefix) {
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("BOOT-INF/lib/" + prefix) && name.endsWith(".jar")) return;
        }
        throw new IllegalStateException("Fat JAR library is missing: " + prefix);
    }

    private static byte[] read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }
}
