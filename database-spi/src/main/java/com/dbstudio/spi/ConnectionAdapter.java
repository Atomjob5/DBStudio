package com.dbstudio.spi;

import java.sql.SQLException;

public interface ConnectionAdapter {
    ConnectionTestResult test(ConnectionProfile profile, char[] password);

    DatabaseSession connect(ConnectionProfile profile, char[] password) throws SQLException;
}
