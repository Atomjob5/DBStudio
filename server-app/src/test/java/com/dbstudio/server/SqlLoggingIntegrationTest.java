package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dbstudio.spi.SqlLogCategory;
import com.dbstudio.spi.SqlLogging;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SqlLoggingIntegrationTest {
    @Test
    void recordsSqlInputsAndConsumedRowCountWithoutResultValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.dbstudio.sql.business");
        ListAppender<ILoggingEvent> events = appender(logger);
        try (Connection connection = SqlLogging.wrap(
                DriverManager.getConnection("jdbc:sqlite::memory:"), SqlLogCategory.BUSINESS)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sample(id INTEGER, secret TEXT)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sample(id, secret) VALUES (?, ?)")) {
                statement.setInt(1, 7);
                statement.setString(2, "input-secret");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sample(id, secret) VALUES (?, ?)")) {
                statement.setInt(1, 8);
                statement.setString(2, "alpha");
                statement.addBatch();
                statement.setInt(1, 9);
                statement.setString(2, "beta");
                statement.addBatch();
                statement.executeBatch();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT upper(secret) FROM sample")) {
                while (rows.next()) rows.getString(1);
            }
        } finally {
            logger.detachAppender(events);
        }

        String messages = messages(events);
        assertTrue(messages.contains("INSERT INTO sample(id, secret) VALUES (?, ?)"));
        assertTrue(messages.contains("1=Integer(\"7\")"));
        assertTrue(messages.contains("2=String(\"input-secret\")"));
        assertTrue(messages.contains("batchCount=2"));
        assertTrue(messages.contains("returnedRows=3"));
        assertFalse(messages.contains("INPUT-SECRET"), "ResultSet values must never be formatted into SQL logs");
        assertFalse(messages.contains("ALPHA"), "Derived ResultSet values must never be logged");
    }

    @Test
    void categoryScopeIsNestedAndRestoresTheConnectionDefault() throws Exception {
        Logger business = (Logger) LoggerFactory.getLogger("com.dbstudio.sql.business");
        Logger metadata = (Logger) LoggerFactory.getLogger("com.dbstudio.sql.metadata");
        Logger transfer = (Logger) LoggerFactory.getLogger("com.dbstudio.sql.transfer");
        ListAppender<ILoggingEvent> businessEvents = appender(business);
        ListAppender<ILoggingEvent> metadataEvents = appender(metadata);
        ListAppender<ILoggingEvent> transferEvents = appender(transfer);
        try (Connection connection = SqlLogging.wrap(
                DriverManager.getConnection("jdbc:sqlite::memory:"), SqlLogCategory.BUSINESS)) {
            execute(connection, "SELECT 1");
            try (SqlLogging.Scope metadataScope = SqlLogging.scope(SqlLogCategory.METADATA)) {
                execute(connection, "SELECT 2");
                try (SqlLogging.Scope transferScope = SqlLogging.scope(SqlLogCategory.TRANSFER)) {
                    execute(connection, "SELECT 3");
                }
                execute(connection, "SELECT 4");
            }
            execute(connection, "SELECT 5");
        } finally {
            business.detachAppender(businessEvents);
            metadata.detachAppender(metadataEvents);
            transfer.detachAppender(transferEvents);
        }
        assertTrue(messages(businessEvents).contains("SELECT 1"));
        assertTrue(messages(businessEvents).contains("SELECT 5"));
        assertTrue(messages(metadataEvents).contains("SELECT 2"));
        assertTrue(messages(metadataEvents).contains("SELECT 4"));
        assertTrue(messages(transferEvents).contains("SELECT 3"));
    }

    @Test
    void binaryParametersAreHashedAndNeverLoggedAsContent() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.dbstudio.sql.persistence");
        ListAppender<ILoggingEvent> events = appender(logger);
        try (Connection connection = SqlLogging.wrap(
                DriverManager.getConnection("jdbc:sqlite::memory:"), SqlLogCategory.PERSISTENCE);
             PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
            statement.setBytes(1, new byte[]{0x01, 0x02, 0x03});
            try (ResultSet ignored = statement.executeQuery()) { }
        } finally {
            logger.detachAppender(events);
        }
        String messages = messages(events);
        assertTrue(messages.contains("BINARY(length=3,sha256="));
        assertFalse(messages.contains("[1, 2, 3]"));
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) { /* count only; never read result values */ }
        }
    }

    private static ListAppender<ILoggingEvent> appender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        return String.join("\n", messages);
    }
}
