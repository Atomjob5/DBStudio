package com.dbstudio.desktop.security;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public final class WindowsCredentialSecretStore implements SecretStore {
    private static final String PREFIX = "DBStudio/";
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final CredentialApi API = Native.load("Advapi32", CredentialApi.class);

    @Override
    public void save(String reference, char[] secret) throws SecretStoreException {
        byte[] bytes = new String(secret).getBytes(StandardCharsets.UTF_16LE);
        Memory blob = new Memory(Math.max(1, bytes.length));
        try {
            if (bytes.length > 0) {
                blob.write(0, bytes, 0, bytes.length);
            }
            Credential credential = new Credential();
            credential.type = CRED_TYPE_GENERIC;
            credential.targetName = new WString(PREFIX + reference);
            credential.credentialBlobSize = bytes.length;
            credential.credentialBlob = blob;
            credential.persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.userName = new WString("dbstudio");
            credential.write();
            if (!API.CredWriteW(credential, 0)) {
                throw failure("无法写入 Windows Credential Manager");
            }
        } finally {
            blob.clear();
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public Optional<char[]> load(String reference) throws SecretStoreException {
        PointerByReference pointer = new PointerByReference();
        if (!API.CredReadW(new WString(PREFIX + reference), CRED_TYPE_GENERIC, 0, pointer)) {
            if (Native.getLastError() == ERROR_NOT_FOUND) {
                return Optional.empty();
            }
            throw failure("无法读取 Windows Credential Manager");
        }
        Pointer raw = pointer.getValue();
        try {
            Credential credential = new Credential(raw);
            byte[] bytes = credential.credentialBlobSize == 0
                    ? new byte[0]
                    : credential.credentialBlob.getByteArray(0, credential.credentialBlobSize);
            try {
                return Optional.of(new String(bytes, StandardCharsets.UTF_16LE).toCharArray());
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            API.CredFree(raw);
        }
    }

    @Override
    public void delete(String reference) throws SecretStoreException {
        if (!API.CredDeleteW(new WString(PREFIX + reference), CRED_TYPE_GENERIC, 0)
                && Native.getLastError() != ERROR_NOT_FOUND) {
            throw failure("无法删除 Windows Credential Manager 密码");
        }
    }

    private SecretStoreException failure(String action) {
        return new SecretStoreException(action + "（Windows 错误 " + Native.getLastError() + "）");
    }

    private interface CredentialApi extends Library {
        boolean CredWriteW(Credential credential, int flags);
        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);
        boolean CredDeleteW(WString targetName, int type, int flags);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({
            "flags", "type", "targetName", "comment", "lastWritten", "credentialBlobSize",
            "credentialBlob", "persist", "attributeCount", "attributes", "targetAlias", "userName"
    })
    public static final class Credential extends Structure {
        public int flags;
        public int type;
        public WString targetName;
        public WString comment;
        public WinBase.FILETIME lastWritten;
        public int credentialBlobSize;
        public Pointer credentialBlob;
        public int persist;
        public int attributeCount;
        public Pointer attributes;
        public WString targetAlias;
        public WString userName;

        public Credential() {
        }

        public Credential(Pointer pointer) {
            super(pointer);
            read();
        }
    }
}
