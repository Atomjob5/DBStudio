package com.dbstudio.spi;

import java.sql.SQLException;
import java.util.List;

/**
 * Receives bounded batches while a provider builds a completion cache.
 *
 * <p>Object metadata and column comments are intentionally separated so Oracle-compatible
 * providers never need to join or scan their expensive column-structure dictionary during
 * the initial load.</p>
 */
public interface CompletionMetadataListener {
    void objects(List<DatabaseObject> objects) throws SQLException;

    void supplementalTables(List<DatabaseObject> objects) throws SQLException;

    void columns(List<CompletionColumnComments> groups) throws SQLException;

    /** Receives visible private/public synonyms when the provider can enumerate them. */
    default void synonyms(List<CompletionSynonymInfo> values) throws SQLException { }

    void warning(String phase, String message) throws SQLException;
}
