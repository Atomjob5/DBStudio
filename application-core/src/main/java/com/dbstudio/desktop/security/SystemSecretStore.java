package com.dbstudio.desktop.security;

import java.util.Locale;
import java.util.Optional;

public final class SystemSecretStore implements SecretStore {
    private final SecretStore delegate;

    public SystemSecretStore() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        delegate = os.contains("mac")
                ? new MacKeychainSecretStore()
                : os.contains("win")
                        ? new WindowsCredentialSecretStore()
                        : new MemorySecretStore();
    }

    @Override
    public void save(String reference, char[] secret) throws SecretStoreException {
        delegate.save(reference, secret);
    }

    @Override
    public Optional<char[]> load(String reference) throws SecretStoreException {
        return delegate.load(reference);
    }

    @Override
    public void delete(String reference) throws SecretStoreException {
        delegate.delete(reference);
    }
}
