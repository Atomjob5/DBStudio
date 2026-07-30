package com.dbstudio.server;

import com.dbstudio.desktop.ProviderRegistry;
import com.dbstudio.desktop.persistence.AppDatabase;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository.EnvironmentEntry;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository.SystemEntry;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.desktop.security.SecretStoreException;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.ConnectionFieldOption;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.FieldType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

/** 生成、解析和持久化数据库链接 Excel；任何工作簿都不包含数据库密码。 */
public final class ConnectionWorkbookService {
    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DATA_SHEET = "数据库链接";
    private static final String HELP_SHEET = "填写说明";
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 5000;
    private static final Pattern FIELD_HEADER = Pattern.compile(".*\\(([A-Za-z][A-Za-z0-9]*)\\)\\s*$");
    private static final List<String> FIXED_HEADERS =
            Arrays.asList("系统", "环境", "链接名称", "数据库类型");

    private final AppDatabase database;
    private final ProviderRegistry providers;
    private final ConnectionProfileRepository profiles;
    private final ConnectionCatalogRepository catalog;
    private final SecretStore secrets;

    public ConnectionWorkbookService(AppDatabase database, ProviderRegistry providers,
                                     ConnectionProfileRepository profiles,
                                     ConnectionCatalogRepository catalog, SecretStore secrets) {
        this.database = database;
        this.providers = providers;
        this.profiles = profiles;
        this.catalog = catalog;
        this.secrets = secrets;
    }

    public byte[] template() {
        return workbookBytes(Collections.<ExportRow>emptyList());
    }

    public Map<String, Object> preview(MultipartFile file) throws Exception {
        validateUpload(file);
        List<Draft> drafts;
        try (InputStream input = file.getInputStream()) {
            drafts = readWorkbook(input);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException("INVALID_CONNECTION_WORKBOOK",
                    "无法读取 Excel，请确认文件未损坏且来自 DBStudio 模板", exception);
        }
        classify(drafts, catalogIndex());
        markFileDuplicates(drafts);
        return previewMap(file.getOriginalFilename(), drafts);
    }

    public byte[] exportWorkbook(String scope, List<String> rawProfileIds) throws SQLException {
        List<SavedProfile> available = profiles.findAll();
        Set<String> selected = new LinkedHashSet<String>();
        if ("selected".equals(scope)) {
            for (String id : rawProfileIds) {
                try { selected.add(UUID.fromString(id).toString()); }
                catch (Exception exception) {
                    throw new ApiException("INVALID_PROFILE_ID", "导出列表包含无效的链接 ID");
                }
            }
            if (selected.isEmpty()) throw new ApiException("EMPTY_CONNECTION_EXPORT", "请至少选择一个数据库链接");
        } else if (!"all".equals(scope)) {
            throw new ApiException("INVALID_CONNECTION_EXPORT_SCOPE", "数据库链接导出范围无效");
        }

        CatalogIndex index = catalogIndex();
        List<ExportRow> rows = new ArrayList<ExportRow>();
        Set<String> found = new LinkedHashSet<String>();
        for (SavedProfile saved : available) {
            String id = saved.profile().id().toString();
            if ("selected".equals(scope) && !selected.contains(id)) continue;
            EnvironmentEntry environment = index.environmentById.get(saved.environmentId());
            SystemEntry system = environment == null ? null : index.systemById.get(environment.systemId());
            if (environment == null || system == null) continue;
            rows.add(new ExportRow(system.name(), environment.name(), saved));
            found.add(id);
        }
        if ("selected".equals(scope) && !found.equals(selected)) {
            throw new ApiException("PROFILE_NOT_FOUND", "部分待导出的数据库链接已被删除");
        }
        Collections.sort(rows, Comparator.comparing((ExportRow row) -> normalize(row.systemName))
                .thenComparing(row -> normalize(row.environmentName))
                .thenComparing(row -> normalize(row.saved.profile().name())));
        return workbookBytes(rows);
    }

    public Map<String, Object> commit(Object rawRows) throws Exception {
        if (!(rawRows instanceof List)) {
            throw new ApiException("INVALID_CONNECTION_IMPORT", "rows 必须是数组");
        }
        final List<Draft> drafts = new ArrayList<Draft>();
        int position = 0;
        for (Object raw : (List<?>) rawRows) {
            position++;
            if (!(raw instanceof Map)) {
                throw new ApiException("INVALID_CONNECTION_IMPORT", "第 " + position + " 条链接格式无效");
            }
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) raw;
            drafts.add(draftFromPayload(value, position));
        }
        if (drafts.isEmpty()) throw new ApiException("EMPTY_CONNECTION_IMPORT", "没有可导入的数据库链接");
        if (drafts.size() > MAX_ROWS) {
            throw new ApiException("CONNECTION_IMPORT_TOO_LARGE", "单次最多导入 " + MAX_ROWS + " 条数据库链接");
        }

        final List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        final int[] counts = new int[4]; // systems, environments, created, updated
        synchronized (database) {
            CatalogIndex before = catalogIndex();
            classify(drafts, before);
            markFileDuplicates(drafts);
            validateCredentials(drafts, before);
            rejectInvalidOrStale(drafts);
            Map<String, SecretSnapshot> secretSnapshots;
            try {
                secretSnapshots = snapshotSecrets(drafts, before);
            } catch (Exception exception) {
                throw new ApiException("CONNECTION_IMPORT_SECRET_FAILED",
                        "无法读取系统密钥库，数据库链接尚未导入：" + exception.getMessage(), exception);
            }

            Connection connection = database.connection();
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                Map<String, SystemEntry> systems = new LinkedHashMap<String, SystemEntry>(before.systemByName);
                Map<String, EnvironmentEntry> environments =
                        new LinkedHashMap<String, EnvironmentEntry>(before.environmentByPath);
                for (Draft draft : drafts) {
                    String systemKey = normalize(draft.systemName);
                    SystemEntry system = systems.get(systemKey);
                    if (system == null) {
                        system = catalog.createSystem(draft.systemName);
                        systems.put(systemKey, system);
                        counts[0]++;
                    }
                    String environmentKey = pathKey(draft.systemName, draft.environmentName);
                    EnvironmentEntry environment = environments.get(environmentKey);
                    if (environment == null) {
                        environment = catalog.createEnvironment(system.id(), draft.environmentName);
                        environments.put(environmentKey, environment);
                        counts[1]++;
                    }

                    UUID profileId;
                    String secretRef;
                    if ("update".equals(draft.operation)) {
                        SavedProfile existing = before.profileById.get(draft.matchedProfileId);
                        if (existing == null) throw stale();
                        profileId = existing.profile().id();
                        secretRef = existing.profile().secretRef();
                        counts[3]++;
                    } else {
                        profileId = parseProfileId(draft.profileId);
                        if (before.profileById.containsKey(profileId.toString())) throw stale();
                        secretRef = "dbstudio/" + profileId;
                        counts[2]++;
                    }
                    ConnectionProfile profile = new ConnectionProfile(profileId, draft.providerId,
                            draft.name, draft.settings, secretRef);
                    profiles.save(profile, draft.rememberPassword, environment.id());
                    results.add(ApiPayloads.map("rowId", draft.rowId, "sourceRow", draft.sourceRow,
                            "profileId", profileId.toString(), "operation", draft.operation,
                            "name", draft.name));
                }
                applySecretChanges(drafts, before);
                connection.commit();
            } catch (Exception exception) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                Exception recoveryFailure = restoreSecrets(secretSnapshots);
                if (recoveryFailure != null) {
                    throw new ApiException("CONNECTION_IMPORT_SECRET_RECOVERY_FAILED",
                            "数据库链接导入已回滚，但系统密钥库恢复失败：" + recoveryFailure.getMessage(),
                            recoveryFailure);
                }
                if (exception instanceof ApiException) throw (ApiException) exception;
                if (exception instanceof SecretStoreException) {
                    throw new ApiException("CONNECTION_IMPORT_SECRET_FAILED",
                            "密码写入系统密钥库失败，所有修改均已回滚：" + exception.getMessage(), exception);
                }
                throw new ApiException("CONNECTION_IMPORT_FAILED",
                        "数据库链接导入失败，所有修改均已回滚", exception);
            } finally {
                try { connection.setAutoCommit(previousAutoCommit); }
                finally { clearSecretSnapshots(secretSnapshots); }
            }
        }
        return ApiPayloads.map("createdSystems", counts[0], "createdEnvironments", counts[1],
                "createdProfiles", counts[2], "updatedProfiles", counts[3], "rows", results);
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("EMPTY_CONNECTION_WORKBOOK", "请选择非空的 Excel 文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ApiException("INVALID_CONNECTION_WORKBOOK", "仅支持 .xlsx 格式的 Excel 文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException("CONNECTION_WORKBOOK_TOO_LARGE", "Excel 文件不能超过 10 MB");
        }
    }

    private List<Draft> readWorkbook(InputStream input) throws Exception {
        byte[] bytes = readLimited(input, MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new ApiException("CONNECTION_WORKBOOK_TOO_LARGE", "Excel 文件不能超过 10 MB");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet(DATA_SHEET);
            if (sheet == null) {
                throw new ApiException("INVALID_CONNECTION_WORKBOOK", "Excel 缺少“数据库链接”工作表");
            }
            Row header = sheet.getRow(0);
            if (header == null) throw new ApiException("INVALID_CONNECTION_WORKBOOK", "Excel 缺少字段标题");
            Map<String, Integer> columns = parseHeaders(header);
            for (String required : FIXED_HEADERS) {
                if (!columns.containsKey(required)) {
                    throw new ApiException("INVALID_CONNECTION_WORKBOOK", "Excel 缺少“" + required + "”列");
                }
            }
            int physicalRows = sheet.getLastRowNum();
            if (physicalRows > MAX_ROWS) {
                throw new ApiException("CONNECTION_IMPORT_TOO_LARGE", "单次最多导入 " + MAX_ROWS + " 条数据库链接");
            }
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            List<Draft> result = new ArrayList<Draft>();
            for (int rowIndex = 1; rowIndex <= physicalRows; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || blank(row, formatter)) continue;
                Draft draft = new Draft();
                draft.rowId = UUID.randomUUID().toString();
                draft.sourceRow = rowIndex + 1;
                draft.profileId = UUID.randomUUID().toString();
                rejectFormulaCells(row, draft);
                draft.systemName = cell(row, columns.get("系统"), formatter).trim();
                draft.environmentName = cell(row, columns.get("环境"), formatter).trim();
                draft.name = cell(row, columns.get("链接名称"), formatter).trim();
                String providerText = cell(row, columns.get("数据库类型"), formatter).trim();
                DatabaseProvider provider = resolveProvider(providerText);
                if (provider == null) {
                    draft.providerId = providerText;
                    draft.errors.add("数据库类型不存在：" + providerText);
                } else {
                    draft.providerId = provider.id();
                    draft.settings = settingsFromRow(row, columns, formatter, provider, draft.errors);
                }
                validateNamesAndProvider(draft);
                result.add(draft);
            }
            if (result.isEmpty()) throw new ApiException("EMPTY_CONNECTION_IMPORT", "Excel 中没有可导入的数据");
            return result;
        }
    }

    private Map<String, Integer> parseHeaders(Row header) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim();
            String key = value;
            Matcher matcher = FIELD_HEADER.matcher(value);
            if (matcher.matches()) key = matcher.group(1);
            if (value.isEmpty()) continue;
            if (result.put(key, cell.getColumnIndex()) != null) {
                throw new ApiException("INVALID_CONNECTION_WORKBOOK", "Excel 包含重复字段：" + value);
            }
        }
        return result;
    }

    private Map<String, String> settingsFromRow(Row row, Map<String, Integer> columns,
                                                DataFormatter formatter, DatabaseProvider provider,
                                                List<String> errors) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (ConnectionField field : provider.connectionFields()) {
            if (field.type() == FieldType.PASSWORD) continue;
            Integer column = columns.get(field.key());
            String value = column == null ? "" : cell(row, column, formatter).trim();
            value = normalizeFieldValue(field, value, errors);
            result.put(field.key(), value);
        }
        return result;
    }

    private Draft draftFromPayload(Map<String, Object> value, int position) {
        Draft draft = new Draft();
        draft.rowId = text(value.get("rowId"));
        if (draft.rowId.isEmpty()) draft.rowId = UUID.randomUUID().toString();
        draft.sourceRow = integer(value.get("sourceRow"), position + 1);
        draft.profileId = text(value.get("profileId"));
        draft.systemName = text(value.get("systemName")).trim();
        draft.environmentName = text(value.get("environmentName")).trim();
        draft.name = text(value.get("name")).trim();
        draft.providerId = text(value.get("providerId")).trim();
        draft.submittedOperation = text(value.get("operation"));
        draft.submittedMatchedProfileId = text(value.get("matchedProfileId"));
        draft.submittedMatchedRevision = text(value.get("matchedRevision"));
        draft.password = text(value.get("password"));
        draft.rememberPassword = bool(value.get("rememberPassword"), false);
        draft.credentialsSubmitted = true;
        DatabaseProvider provider = providerById(draft.providerId);
        Map<String, Object> rawSettings = object(value.get("settings"));
        if (provider == null) {
            draft.errors.add("数据库类型不存在：" + draft.providerId);
            draft.settings = Collections.emptyMap();
        } else {
            Map<String, String> settings = new LinkedHashMap<String, String>();
            for (ConnectionField field : provider.connectionFields()) {
                if (field.type() == FieldType.PASSWORD) continue;
                settings.put(field.key(), normalizeFieldValue(field,
                        text(rawSettings.get(field.key())).trim(), draft.errors));
            }
            draft.settings = settings;
        }
        validateNamesAndProvider(draft);
        return draft;
    }

    private void validateNamesAndProvider(Draft draft) {
        if (draft.systemName.isEmpty()) draft.errors.add("系统不能为空");
        else if (draft.systemName.length() > 64) draft.errors.add("系统名称不能超过64个字符");
        if (draft.environmentName.isEmpty()) draft.errors.add("环境不能为空");
        else if (draft.environmentName.length() > 64) draft.errors.add("环境名称不能超过64个字符");
        if (draft.name.isEmpty()) draft.errors.add("链接名称不能为空");
        else if (draft.name.length() > 128) draft.errors.add("链接名称不能超过128个字符");
        if (draft.providerId.isEmpty()) draft.errors.add("数据库类型不能为空");
    }

    private String normalizeFieldValue(ConnectionField field, String raw, List<String> errors) {
        String value = raw;
        if (value.isEmpty() && field.required()) value = field.defaultValue();
        if (field.required() && value.isEmpty()) errors.add(field.label() + "不能为空");
        if (value.isEmpty()) return "";
        if (field.type() == FieldType.INTEGER) {
            try {
                int number = Integer.parseInt(value);
                if (number <= 0) throw new NumberFormatException();
                return String.valueOf(number);
            } catch (NumberFormatException exception) {
                errors.add(field.label() + "必须是大于0的整数");
            }
        } else if (field.type() == FieldType.BOOLEAN) {
            if (Arrays.asList("true", "1", "是").contains(value.toLowerCase(Locale.ROOT))) return "true";
            if (Arrays.asList("false", "0", "否").contains(value.toLowerCase(Locale.ROOT))) return "false";
            errors.add(field.label() + "必须填写 true/false、是/否或1/0");
        } else if (field.type() == FieldType.SELECT) {
            for (ConnectionFieldOption option : field.options()) {
                if (option.value().equalsIgnoreCase(value) || option.label().equalsIgnoreCase(value)) {
                    return option.value();
                }
            }
            errors.add(field.label() + "的可选值为：" + optionLabels(field.options()));
        }
        return value;
    }

    private static String optionLabels(List<ConnectionFieldOption> options) {
        List<String> values = new ArrayList<String>();
        for (ConnectionFieldOption option : options) values.add(option.label());
        return String.join("、", values);
    }

    private void classify(List<Draft> drafts, CatalogIndex index) {
        for (Draft draft : drafts) {
            boolean existingSystem = index.systemByName.containsKey(normalize(draft.systemName));
            boolean existingEnvironment = index.environmentByPath.containsKey(
                    pathKey(draft.systemName, draft.environmentName));
            draft.createsSystem = !existingSystem;
            draft.createsEnvironment = !existingEnvironment;
            List<SavedProfile> matches = index.profilesByPath.get(
                    profileKey(draft.systemName, draft.environmentName, draft.name));
            if (matches == null || matches.isEmpty()) {
                draft.operation = "create";
                draft.matchedProfileId = "";
                draft.matchedRevision = "";
            } else if (matches.size() == 1) {
                SavedProfile matched = matches.get(0);
                draft.operation = "update";
                draft.profileId = matched.profile().id().toString();
                draft.matchedProfileId = draft.profileId;
                draft.matchedRevision = matched.revision();
                if (!draft.credentialsSubmitted) draft.rememberPassword = matched.rememberPassword();
            } else {
                draft.operation = "update";
                draft.errors.add("现有目录中存在多个同名链接，无法判定需要修改的记录");
            }
        }
    }

    private static void markFileDuplicates(List<Draft> drafts) {
        Map<String, List<Draft>> grouped = new LinkedHashMap<String, List<Draft>>();
        for (Draft draft : drafts) {
            String key = profileKey(draft.systemName, draft.environmentName, draft.name);
            if (key.replace("\u0000", "").isEmpty()) continue;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<Draft>()).add(draft);
        }
        for (List<Draft> values : grouped.values()) {
            if (values.size() < 2) continue;
            for (Draft draft : values) {
                if (!draft.errors.contains("Excel 中存在重复的系统、环境和链接名称")) {
                    draft.errors.add("Excel 中存在重复的系统、环境和链接名称");
                }
            }
        }
    }

    private static void validateCredentials(List<Draft> drafts, CatalogIndex index) {
        for (Draft draft : drafts) {
            if (!draft.rememberPassword || !draft.password.isEmpty()) continue;
            SavedProfile existing = index.profileById.get(draft.matchedProfileId);
            if (!"update".equals(draft.operation) || existing == null || !existing.rememberPassword()) {
                draft.errors.add("勾选记住密码时必须填写密码");
            }
        }
    }

    private Map<String, SecretSnapshot> snapshotSecrets(List<Draft> drafts,
                                                        CatalogIndex index) throws Exception {
        Map<String, SecretSnapshot> snapshots = new LinkedHashMap<String, SecretSnapshot>();
        try {
            for (Draft draft : drafts) {
                SavedProfile existing = index.profileById.get(draft.matchedProfileId);
                boolean previouslyRemembered = existing != null && existing.rememberPassword();
                boolean changesSecret = (draft.rememberPassword && !draft.password.isEmpty())
                        || (!draft.rememberPassword && previouslyRemembered);
                if (!changesSecret) continue;
                String reference = existing == null
                        ? "dbstudio/" + parseProfileId(draft.profileId)
                        : existing.profile().secretRef();
                if (snapshots.containsKey(reference)) continue;
                Optional<char[]> previous = secrets.load(reference);
                snapshots.put(reference, new SecretSnapshot(reference,
                        previous.orElse(null), previous.isPresent()));
            }
            return snapshots;
        } catch (Exception exception) {
            clearSecretSnapshots(snapshots);
            throw exception;
        }
    }

    private void applySecretChanges(List<Draft> drafts, CatalogIndex index) throws Exception {
        for (Draft draft : drafts) {
            SavedProfile existing = index.profileById.get(draft.matchedProfileId);
            boolean previouslyRemembered = existing != null && existing.rememberPassword();
            String reference = existing == null
                    ? "dbstudio/" + parseProfileId(draft.profileId)
                    : existing.profile().secretRef();
            if (draft.rememberPassword && !draft.password.isEmpty()) {
                char[] password = draft.password.toCharArray();
                try { secrets.save(reference, password); }
                finally { Arrays.fill(password, '\0'); }
            } else if (!draft.rememberPassword && previouslyRemembered) {
                secrets.delete(reference);
            }
        }
    }

    private Exception restoreSecrets(Map<String, SecretSnapshot> snapshots) {
        Exception firstFailure = null;
        for (SecretSnapshot snapshot : snapshots.values()) {
            try {
                if (snapshot.present) secrets.save(snapshot.reference, snapshot.value);
                else secrets.delete(snapshot.reference);
            } catch (Exception exception) {
                if (firstFailure == null) firstFailure = exception;
            }
        }
        return firstFailure;
    }

    private static void clearSecretSnapshots(Map<String, SecretSnapshot> snapshots) {
        for (SecretSnapshot snapshot : snapshots.values()) {
            if (snapshot.value != null) Arrays.fill(snapshot.value, '\0');
        }
        snapshots.clear();
    }

    private void rejectInvalidOrStale(List<Draft> drafts) {
        List<Object> invalid = new ArrayList<Object>();
        Set<String> newProfileIds = new LinkedHashSet<String>();
        for (Draft draft : drafts) {
            if (!draft.errors.isEmpty()) {
                invalid.add(ApiPayloads.map("rowId", draft.rowId, "sourceRow", draft.sourceRow,
                        "errors", draft.errors));
                continue;
            }
            if (!draft.operation.equals(draft.submittedOperation)
                    || !draft.matchedProfileId.equals(draft.submittedMatchedProfileId)
                    || !draft.matchedRevision.equals(draft.submittedMatchedRevision)) {
                throw stale();
            }
            if ("create".equals(draft.operation)) {
                parseProfileId(draft.profileId);
                if (!newProfileIds.add(draft.profileId)) {
                    throw new ApiException("INVALID_CONNECTION_IMPORT", "待导入链接包含重复 ID");
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new ApiException("INVALID_CONNECTION_IMPORT",
                    "存在未修正的导入记录", invalid, null);
        }
    }

    private static ApiException stale() {
        return new ApiException("CONNECTION_IMPORT_STALE",
                "连接目录在确认期间已发生变化，请重新选择 Excel 并预览");
    }

    private CatalogIndex catalogIndex() throws SQLException {
        CatalogIndex index = new CatalogIndex();
        for (SystemEntry system : catalog.systems()) {
            index.systemById.put(system.id(), system);
            index.systemByName.put(normalize(system.name()), system);
        }
        for (EnvironmentEntry environment : catalog.environments()) {
            index.environmentById.put(environment.id(), environment);
            SystemEntry system = index.systemById.get(environment.systemId());
            if (system != null) index.environmentByPath.put(
                    pathKey(system.name(), environment.name()), environment);
        }
        for (SavedProfile saved : profiles.findAll()) {
            index.profileById.put(saved.profile().id().toString(), saved);
            EnvironmentEntry environment = index.environmentById.get(saved.environmentId());
            SystemEntry system = environment == null ? null : index.systemById.get(environment.systemId());
            if (environment == null || system == null) continue;
            index.profilesByPath.computeIfAbsent(profileKey(system.name(), environment.name(),
                    saved.profile().name()), ignored -> new ArrayList<SavedProfile>()).add(saved);
        }
        return index;
    }

    private Map<String, Object> previewMap(String filename, List<Draft> drafts) {
        List<Object> rows = new ArrayList<Object>();
        int created = 0;
        int updated = 0;
        int invalid = 0;
        Set<String> newSystems = new LinkedHashSet<String>();
        Set<String> newEnvironments = new LinkedHashSet<String>();
        for (Draft draft : drafts) {
            rows.add(draftMap(draft));
            if ("create".equals(draft.operation)) created++; else updated++;
            if (!draft.errors.isEmpty()) invalid++;
            if (draft.createsSystem) newSystems.add(normalize(draft.systemName));
            if (draft.createsEnvironment) {
                newEnvironments.add(pathKey(draft.systemName, draft.environmentName));
            }
        }
        return ApiPayloads.map("filename", filename == null ? "" : filename, "rows", rows,
                "summary", ApiPayloads.map("total", drafts.size(), "created", created, "updated", updated,
                        "invalid", invalid, "newSystems", newSystems.size(),
                        "newEnvironments", newEnvironments.size()));
    }

    private static Map<String, Object> draftMap(Draft draft) {
        return ApiPayloads.map("rowId", draft.rowId, "sourceRow", draft.sourceRow,
                "profileId", draft.profileId, "systemName", draft.systemName,
                "environmentName", draft.environmentName, "name", draft.name,
                "providerId", draft.providerId, "settings", draft.settings,
                "createsSystem", draft.createsSystem, "createsEnvironment", draft.createsEnvironment,
                "operation", draft.operation, "matchedProfileId", draft.matchedProfileId,
                "matchedRevision", draft.matchedRevision, "errors", draft.errors,
                "warnings", draft.warnings, "rememberPassword", draft.rememberPassword);
    }

    private byte[] workbookBytes(List<ExportRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            List<ConnectionField> fields = exportFields();
            Sheet sheet = workbook.createSheet(DATA_SHEET);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle bodyStyle = bodyStyle(workbook);
            Row header = sheet.createRow(0);
            List<String> headers = new ArrayList<String>(FIXED_HEADERS);
            for (ConnectionField field : fields) {
                headers.add(field.label() + " (" + field.key() + ")");
            }
            for (int index = 0; index < headers.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(headers.get(index));
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (ExportRow export : rows) {
                Row row = sheet.createRow(rowIndex++);
                ConnectionProfile profile = export.saved.profile();
                DatabaseProvider provider = providerById(profile.providerId());
                set(row, 0, export.systemName, bodyStyle);
                set(row, 1, export.environmentName, bodyStyle);
                set(row, 2, profile.name(), bodyStyle);
                set(row, 3, provider == null ? profile.providerId() : provider.displayName(), bodyStyle);
                for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                    set(row, fieldIndex + FIXED_HEADERS.size(),
                            profile.settings().get(fields.get(fieldIndex).key()), bodyStyle);
                }
            }
            sheet.createFreezePane(4, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(1, rowIndex - 1), 0, headers.size() - 1));
            for (int index = 0; index < headers.size(); index++) {
                int width = index < 4 ? new int[] { 18, 14, 22, 20 }[index] : 20;
                sheet.setColumnWidth(index, width * 256);
            }
            addProviderValidation(sheet);
            addHelpSheet(workbook, fields);
            workbook.setActiveSheet(0);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ApiException("CONNECTION_WORKBOOK_FAILED", "生成数据库链接 Excel 失败", exception);
        }
    }

    private List<ConnectionField> exportFields() {
        Map<String, ConnectionField> fields = new LinkedHashMap<String, ConnectionField>();
        for (DatabaseProvider provider : providers.all()) {
            for (ConnectionField field : provider.connectionFields()) {
                if (field.type() != FieldType.PASSWORD) fields.putIfAbsent(field.key(), field);
            }
        }
        return new ArrayList<ConnectionField>(fields.values());
    }

    private void addHelpSheet(XSSFWorkbook workbook, List<ConnectionField> fields) {
        Sheet help = workbook.createSheet(HELP_SHEET);
        CellStyle title = headerStyle(workbook);
        CellStyle body = bodyStyle(workbook);
        String[] notices = {
                "DBStudio 数据库链接导入说明",
                "1. 仅支持 .xlsx 文件，请勿修改“数据库链接”工作表名称或标题中的技术字段名。",
                "2. 系统、环境、链接名称、数据库类型为必填字段。",
                "3. 导入按“系统 + 环境 + 链接名称”识别新增或修改，匹配时忽略大小写和首尾空格。",
                "4. 文件不包含密码。密码只能在导入确认页逐条填写，不会写入 Excel、配置数据库或接口响应。",
                "5. 勾选“记住密码”时密码保存到系统密钥库；未勾选时仅在本次应用运行期间可用。"
        };
        for (int index = 0; index < notices.length; index++) {
            Row row = help.createRow(index);
            Cell cell = row.createCell(0);
            cell.setCellValue(notices[index]);
            cell.setCellStyle(index == 0 ? title : body);
        }
        int rowIndex = notices.length + 1;
        Row providerHeader = help.createRow(rowIndex++);
        set(providerHeader, 0, "数据库类型 ID", title);
        set(providerHeader, 1, "显示名称", title);
        set(providerHeader, 2, "必填连接字段", title);
        for (DatabaseProvider provider : providers.all()) {
            Row row = help.createRow(rowIndex++);
            set(row, 0, provider.id(), body);
            set(row, 1, provider.displayName(), body);
            List<String> required = new ArrayList<String>();
            for (ConnectionField field : provider.connectionFields()) {
                if (field.type() != FieldType.PASSWORD && field.required()) required.add(field.label());
            }
            set(row, 2, String.join("、", required), body);
        }
        rowIndex++;
        Row fieldHeader = help.createRow(rowIndex++);
        set(fieldHeader, 0, "技术字段", title);
        set(fieldHeader, 1, "名称", title);
        set(fieldHeader, 2, "适用数据库", title);
        set(fieldHeader, 3, "必填", title);
        set(fieldHeader, 4, "说明 / 可选值", title);
        for (ConnectionField field : fields) {
            Row row = help.createRow(rowIndex++);
            set(row, 0, field.key(), body);
            set(row, 1, field.label(), body);
            List<String> applicable = new ArrayList<String>();
            List<String> required = new ArrayList<String>();
            for (DatabaseProvider provider : providers.all()) {
                for (ConnectionField providerField : provider.connectionFields()) {
                    if (!providerField.key().equals(field.key())
                            || providerField.type() == FieldType.PASSWORD) continue;
                    applicable.add(provider.displayName());
                    if (providerField.required()) required.add(provider.displayName());
                    break;
                }
            }
            set(row, 2, String.join("、", applicable), body);
            set(row, 3, required.isEmpty() ? "否" : String.join("、", required), body);
            String options = field.options().isEmpty() ? "" : "可选值：" + optionLabels(field.options());
            set(row, 4, options.isEmpty() ? field.description() : options, body);
        }
        help.setColumnWidth(0, 24 * 256);
        help.setColumnWidth(1, 25 * 256);
        help.setColumnWidth(2, 32 * 256);
        help.setColumnWidth(3, 32 * 256);
        help.setColumnWidth(4, 60 * 256);
    }

    private void addProviderValidation(Sheet sheet) {
        List<String> names = new ArrayList<String>();
        for (DatabaseProvider provider : providers.all()) names.add(provider.displayName());
        if (names.isEmpty()) return;
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint =
                helper.createExplicitListConstraint(names.toArray(new String[names.size()]));
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(1, MAX_ROWS, 3, 3));
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("数据库类型无效", "请从下拉列表中选择数据库类型");
        sheet.addValidationData(validation);
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle bodyStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.HAIR);
        return style;
    }

    private static void set(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column, CellType.STRING);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private DatabaseProvider resolveProvider(String value) {
        for (DatabaseProvider provider : providers.all()) {
            if (provider.id().equalsIgnoreCase(value)
                    || provider.displayName().equalsIgnoreCase(value)) return provider;
        }
        return null;
    }

    private DatabaseProvider providerById(String id) {
        for (DatabaseProvider provider : providers.all()) {
            if (provider.id().equals(id)) return provider;
        }
        return null;
    }

    private static String cell(Row row, Integer index, DataFormatter formatter) {
        if (index == null) return "";
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private static boolean blank(Row row, DataFormatter formatter) {
        for (Cell cell : row) if (!formatter.formatCellValue(cell).trim().isEmpty()) return false;
        return true;
    }

    private static void rejectFormulaCells(Row row, Draft draft) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA) {
                draft.errors.add("不支持公式单元格：" + cell.getAddress().formatAsString());
            }
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) break;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static UUID parseProfileId(String raw) {
        try { return UUID.fromString(raw); }
        catch (Exception exception) {
            throw new ApiException("INVALID_PROFILE_ID", "待导入链接 ID 无效");
        }
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) return Collections.emptyMap();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(text(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        String text = text(value).trim();
        return text.isEmpty() ? fallback : Boolean.parseBoolean(text);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String pathKey(String system, String environment) {
        return normalize(system) + '\u0000' + normalize(environment);
    }

    private static String profileKey(String system, String environment, String profile) {
        return pathKey(system, environment) + '\u0000' + normalize(profile);
    }

    private static final class CatalogIndex {
        private final Map<String, SystemEntry> systemById = new LinkedHashMap<String, SystemEntry>();
        private final Map<String, SystemEntry> systemByName = new LinkedHashMap<String, SystemEntry>();
        private final Map<String, EnvironmentEntry> environmentById =
                new LinkedHashMap<String, EnvironmentEntry>();
        private final Map<String, EnvironmentEntry> environmentByPath =
                new LinkedHashMap<String, EnvironmentEntry>();
        private final Map<String, SavedProfile> profileById = new LinkedHashMap<String, SavedProfile>();
        private final Map<String, List<SavedProfile>> profilesByPath =
                new LinkedHashMap<String, List<SavedProfile>>();
    }

    private static final class SecretSnapshot {
        private final String reference;
        private final char[] value;
        private final boolean present;
        private SecretSnapshot(String reference, char[] value, boolean present) {
            this.reference = reference;
            this.value = value;
            this.present = present;
        }
    }

    private static final class Draft {
        private String rowId = "";
        private int sourceRow;
        private String profileId = "";
        private String systemName = "";
        private String environmentName = "";
        private String name = "";
        private String providerId = "";
        private Map<String, String> settings = Collections.emptyMap();
        private boolean createsSystem;
        private boolean createsEnvironment;
        private String operation = "create";
        private String matchedProfileId = "";
        private String matchedRevision = "";
        private String submittedOperation = "";
        private String submittedMatchedProfileId = "";
        private String submittedMatchedRevision = "";
        private String password = "";
        private boolean rememberPassword;
        private boolean credentialsSubmitted;
        private final List<String> errors = new ArrayList<String>();
        private final List<String> warnings = new ArrayList<String>();
    }

    private static final class ExportRow {
        private final String systemName;
        private final String environmentName;
        private final SavedProfile saved;
        private ExportRow(String systemName, String environmentName, SavedProfile saved) {
            this.systemName = systemName;
            this.environmentName = environmentName;
            this.saved = saved;
        }
    }
}
