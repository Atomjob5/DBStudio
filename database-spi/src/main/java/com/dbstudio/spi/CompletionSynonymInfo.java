package com.dbstudio.spi;

import java.util.Objects;

/** A visible SQL synonym and the object name it points at. */
public final class CompletionSynonymInfo {
    private final String owner;
    private final String name;
    private final String targetOwner;
    private final String targetName;
    private final String databaseLink;
    private final boolean publicSynonym;

    public CompletionSynonymInfo(String owner, String name, String targetOwner, String targetName,
                                 String databaseLink, boolean publicSynonym) {
        this.owner = value(owner);
        this.name = Objects.requireNonNull(value(name), "name");
        this.targetOwner = value(targetOwner);
        this.targetName = Objects.requireNonNull(value(targetName), "targetName");
        this.databaseLink = value(databaseLink);
        this.publicSynonym = publicSynonym;
    }

    public String owner() { return owner; }
    public String name() { return name; }
    public String targetOwner() { return targetOwner; }
    public String targetName() { return targetName; }
    public String databaseLink() { return databaseLink; }
    public boolean publicSynonym() { return publicSynonym; }

    private static String value(String value) { return value == null ? "" : value; }
}
