package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A question as it was asked, and the answer given (SoW s6.1: "Every question,
 * check and message ... is logged against the request"). The prompt is copied,
 * so later edits to the content never change the record. Append-only.
 */
@Entity
@Table(name = "troubleshooting_response")
public class TroubleshootingResponse extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "prompt", nullable = false, length = 500)
    private String prompt;

    @Column(name = "response_value", nullable = false, length = 16)
    private String responseValue;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "answered_by_user_id", nullable = false)
    private Long answeredByUserId;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    protected TroubleshootingResponse() {
    }

    TroubleshootingResponse(Long sessionId, TroubleshootingStep step, boolean yes, String note,
                            Long answeredByUserId, Instant answeredAt) {
        this.sessionId = sessionId;
        this.stepId = step.getId();
        this.prompt = step.getPrompt();
        this.responseValue = yes ? "YES" : "NO";
        this.note = note;
        this.answeredByUserId = answeredByUserId;
        this.answeredAt = answeredAt;
    }

    Long getSessionId() { return sessionId; }
    Long getStepId() { return stepId; }
    String getPrompt() { return prompt; }
    String getResponseValue() { return responseValue; }
    String getNote() { return note; }
    Long getAnsweredByUserId() { return answeredByUserId; }
    Instant getAnsweredAt() { return answeredAt; }
}
