package com.dbstudio.spi;

import java.util.Optional;

/** Stable, dialect-owned assessment of whether a result query can enter edit mode. */
public final class ResultEditPlan {
    private final ResultMutationSource source;
    private final String reasonCode;
    private final String reason;

    private ResultEditPlan(ResultMutationSource source, String reasonCode, String reason) {
        this.source = source;
        this.reasonCode = reasonCode == null ? "" : reasonCode;
        this.reason = reason == null ? "" : reason;
    }

    public static ResultEditPlan editable(ResultMutationSource source) {
        return new ResultEditPlan(source, "", "");
    }

    public static ResultEditPlan readOnly(String reasonCode, String reason) {
        return new ResultEditPlan(null, reasonCode, reason);
    }

    public static ResultEditPlan readOnly(ResultMutationSource source, String reasonCode, String reason) {
        return new ResultEditPlan(source, reasonCode, reason);
    }

    public Optional<ResultMutationSource> source() { return Optional.ofNullable(source); }
    public boolean editableCandidate() { return source != null && source.editableForUpdate(); }
    public String reasonCode() { return reasonCode; }
    public String reason() { return reason; }
}
