package com.dbstudio.desktop.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ResolvedResultMetadata {
    private final List<ResultColumn> columns;
    private final ResultMutationTarget mutationTarget;

    public ResolvedResultMetadata(List<ResultColumn> columns, ResultMutationTarget mutationTarget) {
        this.columns = columns == null ? Collections.<ResultColumn>emptyList()
                : Collections.unmodifiableList(new ArrayList<ResultColumn>(columns));
        this.mutationTarget = mutationTarget;
    }

    public List<ResultColumn> columns() { return columns; }
    public ResultMutationTarget mutationTarget() { return mutationTarget; }
}
