package com.dbstudio.server;

import com.dbstudio.desktop.persistence.SettingsRepository;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 使用本机网卡摘要创建稳定标识，不保存也不记录原始 MAC 地址。 */
@Component
public final class MachineIdentity {
    private static final String INSTALLATION_ID = "installation.id";
    private final String fingerprint;

    public MachineIdentity(SettingsRepository settings) {
        this.fingerprint = computeFingerprint(settings);
    }

    public String fingerprint() { return fingerprint; }

    public String workspaceId(String localUuid) {
        byte[] bytes = digest((fingerprint + ":" + localUuid).getBytes(StandardCharsets.UTF_8));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0L, least = 0L;
        for (int i = 0; i < 8; i++) most = (most << 8) | (bytes[i] & 0xffL);
        for (int i = 8; i < 16; i++) least = (least << 8) | (bytes[i] & 0xffL);
        return new UUID(most, least).toString();
    }

    private static String computeFingerprint(SettingsRepository settings) {
        List<String> addresses = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface current = interfaces.nextElement();
                if (current.isLoopback() || current.isVirtual() || !current.isUp()) continue;
                byte[] hardware = current.getHardwareAddress();
                if (hardware == null || hardware.length == 0) continue;
                addresses.add(hex(hardware));
            }
        } catch (Exception ignored) { }
        Collections.sort(addresses);
        String installationId = installationId(settings);
        String material = addresses.isEmpty() ? "installation:" + installationId
                : "mac:" + join(addresses) + ":installation:" + installationId;
        return hex(digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static String installationId(SettingsRepository settings) {
        try {
            java.util.Optional<String> existing = settings.get(INSTALLATION_ID);
            if (existing.isPresent() && !existing.get().trim().isEmpty()) return existing.get();
            String created = UUID.randomUUID().toString();
            settings.put(INSTALLATION_ID, created);
            return created;
        } catch (SQLException exception) {
            return UUID.randomUUID().toString();
        }
    }

    private static byte[] digest(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            output[index * 2] = digits[current >>> 4];
            output[index * 2 + 1] = digits[current & 0x0f];
        }
        return new String(output);
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) { if (joined.length() > 0) joined.append(','); joined.append(value); }
        return joined.toString();
    }
}
