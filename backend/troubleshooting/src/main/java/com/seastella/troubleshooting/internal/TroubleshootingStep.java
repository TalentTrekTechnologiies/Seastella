package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One yes/no check. Each answer leads to another step or ends in an outcome. */
@Entity
@Table(name = "troubleshooting_step")
public class TroubleshootingStep extends BaseEntity {

    @Column(name = "flow_id", nullable = false)
    private Long flowId;

    @Column(name = "step_key", nullable = false, length = 40)
    private String stepKey;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "prompt", nullable = false, length = 500)
    private String prompt;

    @Column(name = "help_text", length = 1000)
    private String helpText;

    @Column(name = "input_kind", nullable = false, length = 16)
    private String inputKind = "YES_NO";

    @Column(name = "yes_next_key", length = 40)
    private String yesNextKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "yes_outcome", length = 16)
    private TroubleshootingOutcome yesOutcome;

    @Column(name = "no_next_key", length = 40)
    private String noNextKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "no_outcome", length = 16)
    private TroubleshootingOutcome noOutcome;

    protected TroubleshootingStep() {
    }

    TroubleshootingStep(Long flowId, String stepKey, int displayOrder, String prompt, String helpText,
                        String yesNextKey, TroubleshootingOutcome yesOutcome,
                        String noNextKey, TroubleshootingOutcome noOutcome) {
        this.flowId = flowId;
        this.stepKey = stepKey;
        this.displayOrder = displayOrder;
        this.prompt = prompt;
        this.helpText = helpText;
        this.yesNextKey = yesNextKey;
        this.yesOutcome = yesOutcome;
        this.noNextKey = noNextKey;
        this.noOutcome = noOutcome;
    }

    Long getFlowId() { return flowId; }
    String getStepKey() { return stepKey; }
    int getDisplayOrder() { return displayOrder; }
    String getPrompt() { return prompt; }
    String getHelpText() { return helpText; }

    String getYesNextKey() { return yesNextKey; }
    TroubleshootingOutcome getYesOutcome() { return yesOutcome; }
    String getNoNextKey() { return noNextKey; }
    TroubleshootingOutcome getNoOutcome() { return noOutcome; }

    /** The step key to go to next, or null when this answer ends the checks. */
    String nextKey(boolean yes) { return yes ? yesNextKey : noNextKey; }

    /** The outcome this answer ends in, or null when the checks continue. */
    TroubleshootingOutcome outcome(boolean yes) { return yes ? yesOutcome : noOutcome; }
}
