package com.dbstudio.spi;

import java.util.List;

public interface DatabaseProvider {
    String id();

    String displayName();

    List<ConnectionField> connectionFields();

    DatabaseCapabilities capabilities();

    ConnectionAdapter connections();

    MetadataAdapter metadata();

    SqlDialect dialect();
}
