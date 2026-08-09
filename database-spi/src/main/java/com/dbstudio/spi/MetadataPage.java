package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MetadataPage<T> {
    private final List<T> items;
    private final String nextPageToken;
    private final boolean supported;
    private final String warning;

    public MetadataPage(List<T> items, String nextPageToken, boolean supported, String warning) {
        this.items = Collections.unmodifiableList(new ArrayList<T>(items));
        this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
        this.supported = supported;
        this.warning = warning == null ? "" : warning;
    }
    public static <T> MetadataPage<T> empty() {
        return new MetadataPage<T>(Collections.<T>emptyList(), "", true, "");
    }
    public List<T> items() { return items; }
    public String nextPageToken() { return nextPageToken; }
    public boolean supported() { return supported; }
    public String warning() { return warning; }
}
