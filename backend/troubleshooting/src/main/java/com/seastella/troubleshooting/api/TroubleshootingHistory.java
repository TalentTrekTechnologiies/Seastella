package com.seastella.troubleshooting.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Finished guided checks, for the troubleshooting report (RPT-03).
 *
 * <p>The record of what was asked and answered stays in the troubleshooting
 * module; this is the summary line per session that a report needs, scoped by
 * vessel like everything else.
 */
public interface TroubleshootingHistory {

    List<SessionSummary> sessions(Set<Long> vesselIds, int limit);

    record SessionSummary(Long serviceRequestId, String requestNumber, Long vesselId, String vesselName,
                          String spareName, String problemType, String flowName, int flowVersion,
                          boolean sampleContent, String status, TroubleshootingOutcome outcome,
                          int answerCount, String rootCauseNote, String temporaryFixNote,
                          String runBy, Instant startedAt, Instant completedAt) {}
}
