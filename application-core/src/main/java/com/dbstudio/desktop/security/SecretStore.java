package com.dbstudio.desktop.security;

import java.util.Optional;

public interface SecretStore {
    void save(String reference, char[] secret) throws SecretStoreException;

    Optional<char[]> load(String reference) throws SecretStoreException;

    void delete(String reference) throws SecretStoreException;
}
