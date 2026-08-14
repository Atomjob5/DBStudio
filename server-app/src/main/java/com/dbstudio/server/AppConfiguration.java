package com.dbstudio.server;

import com.dbstudio.desktop.AppDirectories;
import com.dbstudio.desktop.ProviderRegistry;
import com.dbstudio.desktop.csv.CsvService;
import com.dbstudio.desktop.persistence.AppDatabase;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository;
import com.dbstudio.desktop.persistence.QueryHistoryRepository;
import com.dbstudio.desktop.persistence.SettingsRepository;
import com.dbstudio.desktop.persistence.WorkspaceRepository;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.desktop.security.SystemSecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.SQLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfiguration {
    @Bean public LocalAccessToken localAccessToken(
            @Value("${dbstudio.local-access-token.enabled:false}") boolean enabled) {
        return new LocalAccessToken(enabled);
    }
    @Bean public ProviderRegistry providerRegistry() { return new ProviderRegistry(); }
    @Bean(destroyMethod = "close") public AppDatabase appDatabase(
            @Value("${dbstudio.data-directory:}") String configuredDirectory) throws SQLException, IOException {
        Path directory = configuredDirectory == null || configuredDirectory.trim().isEmpty()
                ? AppDirectories.dataDirectory() : Paths.get(configuredDirectory);
        return new AppDatabase(directory);
    }
    @Bean public ConnectionProfileRepository connectionProfiles(AppDatabase database, ObjectMapper mapper) {
        return new ConnectionProfileRepository(database, mapper);
    }
    @Bean public ConnectionCatalogRepository connectionCatalog(AppDatabase database) {
        return new ConnectionCatalogRepository(database);
    }
    @Bean public ConnectionWorkbookService connectionWorkbookService(
            AppDatabase database, ProviderRegistry providers,
            ConnectionProfileRepository profiles, ConnectionCatalogRepository catalog,
            SecretStore secrets) {
        return new ConnectionWorkbookService(database, providers, profiles, catalog, secrets);
    }
    @Bean public ConnectionProfileCloneService connectionProfileCloneService(
            AppDatabase database, ConnectionProfileRepository profiles, SecretStore secrets) {
        return new ConnectionProfileCloneService(database, profiles, secrets);
    }
    @Bean public QueryHistoryRepository queryHistory(AppDatabase database) {
        return new QueryHistoryRepository(database);
    }
    @Bean public SettingsRepository settings(AppDatabase database) { return new SettingsRepository(database); }
    @Bean public WorkspaceRepository workspaces(AppDatabase database) { return new WorkspaceRepository(database); }
    @Bean public SecretStore secretStore() { return new SystemSecretStore(); }
    @Bean public CsvService csvService() { return new CsvService(); }
    @Bean public ResultExportService resultExportService(CsvService csv) {
        return new ResultExportService(csv);
    }
}
