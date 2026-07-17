package com.dbstudio.desktop.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class MacKeychainSecretStore implements SecretStore {
    private static final String SERVICE_PREFIX = "DBStudio/";

    @Override
    public void save(String reference, char[] secret) throws SecretStoreException {
        run(true, "add-generic-password", "-U", "-a", "dbstudio", "-s", service(reference),
                "-w", new String(secret));
    }

    @Override
    public Optional<char[]> load(String reference) throws SecretStoreException {
        Result result = run(false, "find-generic-password", "-a", "dbstudio", "-s", service(reference), "-w");
        if (result.exitCode() == 44) return Optional.empty();
        if (result.exitCode() != 0) {
            throw new SecretStoreException("无法读取 macOS Keychain：" + result.output().trim());
        }
        return Optional.of(trimLineEnding(result.output()).toCharArray());
    }

    @Override
    public void delete(String reference) throws SecretStoreException {
        Result result = run(false, "delete-generic-password", "-a", "dbstudio", "-s", service(reference));
        if (result.exitCode() != 0 && result.exitCode() != 44) {
            throw new SecretStoreException("无法删除 macOS Keychain 密码：" + result.output().trim());
        }
    }

    private Result run(boolean requireSuccess, String... arguments) throws SecretStoreException {
        String[] command = new String[arguments.length + 1];
        command[0] = "/usr/bin/security";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readUtf8(process.getInputStream());
            int exitCode = process.waitFor();
            if (requireSuccess && exitCode != 0) {
                throw new SecretStoreException("无法写入 macOS Keychain：" + output.trim());
            }
            return new Result(exitCode, output);
        } catch (IOException exception) {
            throw new SecretStoreException("无法启动 macOS Keychain 工具", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SecretStoreException("Keychain 操作已中断", exception);
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trimLineEnding(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
        return value.substring(0, end);
    }

    private String service(String reference) { return SERVICE_PREFIX + reference; }

    private static final class Result {
        private final int exitCode;
        private final String output;
        private Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
        private int exitCode() { return exitCode; }
        private String output() { return output; }
    }
}
