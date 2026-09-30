package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The last software gap announced for one unit, so a unit that is behind is
 * reported when it <em>falls</em> behind and not every night afterwards.
 *
 * <p>Both versions are remembered, not just the fact that something was said.
 * A unit reported as 5.4 against 5.6 is news again when the sheet moves to 5.7,
 * and again when the vessel flashes it to 5.5 and is still behind - each is a
 * different gap, and each is something the office would want to know. Storing
 * only "already told them" would silence all of it.
 */
@Entity
@Table(name = "software_alert_state")
class SoftwareAlertState extends BaseEntity {

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    /** The version on the unit when this was last announced. */
    @Column(name = "installed_version", nullable = false, length = 64)
    private String installedVersion;

    /** The baseline it was measured against at the time. */
    @Column(name = "latest_version", nullable = false, length = 64)
    private String latestVersion;

    @Column(name = "alerted_at", nullable = false)
    private Instant alertedAt;

    protected SoftwareAlertState() {
    }

    SoftwareAlertState(Long spareId, String installedVersion, String latestVersion, Instant alertedAt) {
        this.spareId = spareId;
        this.installedVersion = installedVersion;
        this.latestVersion = latestVersion;
        this.alertedAt = alertedAt;
    }

    Long getSpareId() { return spareId; }

    /** Whether this exact gap is the one already announced. */
    boolean alreadyToldAbout(String installed, String latest) {
        return installedVersion.equals(installed) && latestVersion.equals(latest);
    }

    void announced(String installed, String latest, Instant at) {
        this.installedVersion = installed;
        this.latestVersion = latest;
        this.alertedAt = at;
    }
}
