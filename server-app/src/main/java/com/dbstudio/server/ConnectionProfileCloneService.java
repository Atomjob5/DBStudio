package com.dbstudio.server;

import com.dbstudio.desktop.persistence.AppDatabase;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.spi.ConnectionProfile;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在同一环境中克隆数据库链接，并安全复制可用的密钥库密码。 */
public final class ConnectionProfileCloneService {
    static final String PASSWORD_COPIED = "copied";
    static final String PASSWORD_NOT_REMEMBERED = "not-remembered";
    static final String PASSWORD_UNAVAILABLE = "unavailable";
    private static final Pattern COPY_SUFFIX =
            Pattern.compile("^(.*) - 副本(?: ([0-9]+))?$");

    private final AppDatabase database;
    private final ConnectionProfileRepository profiles;
    private final SecretStore secrets;

    public ConnectionProfileCloneService(AppDatabase database,
                                         ConnectionProfileRepository profiles,
                                         SecretStore secrets) {
        this.database = database;
        this.profiles = profiles;
        this.secrets = secrets;
    }

    public CloneResult cloneProfile(UUID sourceId) throws Exception {
        synchronized (database) {
            SavedProfile source = profiles.find(sourceId).orElseThrow(
                    () -> new ApiException("PROFILE_NOT_FOUND", "数据库链接不存在或已删除"));
            List<SavedProfile> available = profiles.findAll();
            String name = cloneName(source, available);
            UUID cloneId = UUID.randomUUID();
            String secretRef = "dbstudio/" + cloneId;
            char[] password = null;
            boolean secretCopied = false;
            String passwordStatus = source.rememberPassword()
                    ? PASSWORD_UNAVAILABLE : PASSWORD_NOT_REMEMBERED;

            if (source.rememberPassword()) {
                try {
                    Optional<char[]> remembered = secrets.load(source.profile().secretRef());
                    if (remembered.isPresent()) {
                        password = remembered.get();
                        try {
                            secrets.save(secretRef, password);
                            secretCopied = true;
                            passwordStatus = PASSWORD_COPIED;
                        } catch (Exception copyFailure) {
                            cleanupUnavailableSecret(secretRef);
                        }
                    }
                } catch (ApiException exception) {
                    clear(password);
                    throw exception;
                } catch (Exception ignored) {
                    passwordStatus = PASSWORD_UNAVAILABLE;
                }
            }

            Connection connection = database.connection();
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                ConnectionProfile cloned = new ConnectionProfile(
                        cloneId, source.profile().providerId(), name,
                        source.profile().settings(), secretRef);
                profiles.save(cloned, secretCopied, source.environmentId());
                connection.commit();
            } catch (Exception exception) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                Exception cleanupFailure = secretCopied ? deleteSecret(secretRef) : null;
                if (cleanupFailure != null) {
                    throw new ApiException("CONNECTION_CLONE_SECRET_RECOVERY_FAILED",
                            "链接克隆已回滚，但副本密码清理失败：" + cleanupFailure.getMessage(),
                            cleanupFailure);
                }
                if (exception instanceof ApiException) throw (ApiException) exception;
                throw new ApiException("CONNECTION_CLONE_FAILED",
                        "数据库链接克隆失败，所有修改均已回滚", exception);
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); }
                finally { clear(password); }
            }

            SavedProfile cloned = profiles.find(cloneId).orElseThrow(
                    () -> new ApiException("PROFILE_NOT_FOUND", "克隆后的数据库链接不存在"));
            return new CloneResult(cloned, passwordStatus);
        }
    }

    private void cleanupUnavailableSecret(String secretRef) {
        Exception cleanupFailure = deleteSecret(secretRef);
        if (cleanupFailure != null) {
            throw new ApiException("CONNECTION_CLONE_SECRET_RECOVERY_FAILED",
                    "副本密码写入失败，且系统密钥库清理失败：" + cleanupFailure.getMessage(),
                    cleanupFailure);
        }
    }

    private Exception deleteSecret(String secretRef) {
        try {
            secrets.delete(secretRef);
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    static String cloneName(SavedProfile source, List<SavedProfile> available) {
        String sourceName = source.profile().name().trim();
        Matcher suffix = COPY_SUFFIX.matcher(sourceName);
        String root = suffix.matches() && !suffix.group(1).trim().isEmpty()
                ? suffix.group(1).trim() : sourceName;
        Set<String> used = new LinkedHashSet<String>();
        for (SavedProfile profile : available) {
            if (source.environmentId().equals(profile.environmentId())) {
                used.add(normalize(profile.profile().name()));
            }
        }
        String candidate = root + " - 副本";
        if (!used.contains(normalize(candidate))) return candidate;
        for (int sequence = 2; sequence < Integer.MAX_VALUE; sequence++) {
            candidate = root + " - 副本 " + sequence;
            if (!used.contains(normalize(candidate))) return candidate;
        }
        throw new ApiException("CONNECTION_CLONE_NAME_EXHAUSTED", "无法为克隆链接生成可用名称");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    public static final class CloneResult {
        private final SavedProfile profile;
        private final String passwordStatus;

        private CloneResult(SavedProfile profile, String passwordStatus) {
            this.profile = profile;
            this.passwordStatus = passwordStatus;
        }

        public SavedProfile profile() { return profile; }
        public String passwordStatus() { return passwordStatus; }
    }
}
