package com.seastella.notification.internal;

import com.seastella.fleet.api.FleetDirectory;
import com.seastella.fleet.api.FleetDirectory.EquipmentExpiry;
import com.seastella.identity.api.Role;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The client asked for <em>one</em> notice at 60 days and <em>one</em> at 15.
 *
 * <p>"One" is the whole requirement here, and it is the part that breaks
 * silently: a sweep that runs nightly and alerts on every night a unit is
 * inside 60 days sends ninety emails instead of one, and the fix arrives after
 * the recipients have already learned to filter the sender. So these cases are
 * mostly about what the monitor must <b>not</b> send.
 */
class EquipmentExpiryMonitorTest {

    private static final LocalDate TODAY = com.seastella.core.api.time.BusinessTime.today();

    private FleetDirectory fleet;
    private EquipmentExpiryAlertStateRepository states;
    private NotificationFanout fanout;
    private MaintenanceStatusEngine engine;

    @BeforeEach
    void setUp() {
        fleet = mock(FleetDirectory.class);
        states = mock(EquipmentExpiryAlertStateRepository.class);
        fanout = mock(NotificationFanout.class);
        engine = mock(MaintenanceStatusEngine.class);
        when(engine.classify(anyInt(), any())).thenReturn(DueStatus.APPROACHING);
        when(states.findBySpareIdIn(anyCollection())).thenReturn(List.of());
    }

    private EquipmentExpiryMonitor monitor() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new EquipmentExpiryMonitor(fleet, states, engine, fanout, transactions,
                List.of(60, 15, 0));
    }

    /** A unit expiring in {@code days}, as the fleet would hand it over. */
    private static EquipmentExpiry unit(long spareId, int days) {
        return new EquipmentExpiry(spareId, "Liferaft bottle", "13.1.4", TODAY.plusDays(days),
                7L, "MV Kestrel", 3L);
    }

    private static EquipmentExpiryAlertState announcedAt(long spareId, int threshold, LocalDate expiry) {
        return new EquipmentExpiryAlertState(spareId, threshold, expiry, java.time.Instant.now());
    }

    @Nested
    @DisplayName("one notice per threshold")
    class OncePerThreshold {

        @Test
        void announcesAUnitThatHasJustReachedSixtyDays() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, 60)));

            monitor().scan();

            verify(fanout).equipmentExpiryAlert(any(), any(), any(), any());
            verify(states).save(any(EquipmentExpiryAlertState.class));
        }

        @Test
        void saysNothingMoreWhileTheUnitSitsInsideAThresholdAlreadyAnnounced() {
            EquipmentExpiry unit = unit(1L, 41);
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit));
            when(states.findBySpareIdIn(anyCollection()))
                    .thenReturn(List.of(announcedAt(1L, 60, unit.expiryDate())));

            monitor().scan();

            verify(fanout, never()).equipmentExpiryAlert(any(), any(), any(), any());
        }

        @Test
        void announcesAgainWhenTheTighterThresholdIsReached() {
            EquipmentExpiry unit = unit(1L, 15);
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit));
            when(states.findBySpareIdIn(anyCollection()))
                    .thenReturn(List.of(announcedAt(1L, 60, unit.expiryDate())));

            monitor().scan();

            verify(fanout).equipmentExpiryAlert(any(), any(), any(), any());
        }

        /**
         * A unit added to the platform already inside both thresholds - or a
         * date typed in late - has "crossed" 60 and 15 at once. That is one
         * notice at the tighter of them, not one for each.
         */
        @Test
        void crossingTwoThresholdsAtOnceIsStillOneNotice() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, 3)));

            monitor().scan();

            verify(fanout, times(1)).equipmentExpiryAlert(any(), any(), any(), any());
        }

        /**
         * The sweep is bounded by the widest threshold, but a unit outside it
         * must be ignored rather than announced, whatever the query returns.
         */
        @Test
        void saysNothingAboutAUnitBeyondTheWidestThreshold() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, 75)));

            monitor().scan();

            verify(fanout, never()).equipmentExpiryAlert(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("a corrected date is a new deadline")
    class CorrectedDate {

        /**
         * Re-certifying a unit moves its expiry out. The bookmark then
         * describes a deadline that no longer exists, and the unit must be
         * free to announce against the new one when it comes round again.
         */
        @Test
        void announcesAgainWhenTheExpiryDateItselfChanges() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, 20)));
            // Told at 60 days - but against a date that has since been corrected.
            when(states.findBySpareIdIn(anyCollection()))
                    .thenReturn(List.of(announcedAt(1L, 60, TODAY.plusDays(9))));

            monitor().scan();

            verify(fanout).equipmentExpiryAlert(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("what the notice says")
    class Wording {

        @Test
        void anExpiringUnitIsWordedAsAWarningAndCarriesItsBand() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, 15)));
            when(engine.classify(anyInt(), any())).thenReturn(DueStatus.URGENT);

            monitor().scan();

            ArgumentCaptor<AlertMessages.Message> message = ArgumentCaptor.forClass(AlertMessages.Message.class);
            ArgumentCaptor<String> band = ArgumentCaptor.forClass(String.class);
            verify(fanout).equipmentExpiryAlert(any(), message.capture(), any(), band.capture());

            assertThat(message.getValue().title()).startsWith("Equipment expiring");
            assertThat(message.getValue().body()).contains("Liferaft bottle", "MV Kestrel", "15 days");
            // The band the equipment list is drawing for the same date.
            assertThat(band.getValue()).isEqualTo("URGENT");
        }

        @Test
        void aUnitAlreadyPastItsDateIsWordedAsExpiredNotExpiring() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, -4)));

            monitor().scan();

            ArgumentCaptor<AlertMessages.Message> message = ArgumentCaptor.forClass(AlertMessages.Message.class);
            verify(fanout).equipmentExpiryAlert(any(), message.capture(), any(), any());

            assertThat(message.getValue().title()).startsWith("Equipment expired");
            assertThat(message.getValue().body()).contains("should not stay in service");
        }
    }

    @Nested
    @DisplayName("who hears about it")
    class Recipients {

        /** The same three roles an expiring certificate reaches. */
        @Test
        void reachesTheVesselsPeopleAndTheOrganizationsTechnicalHead() {
            when(fleet.equipmentExpiringBy(any(LocalDate.class))).thenReturn(List.of(unit(1L, 15)));

            monitor().scan();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Role>> roles = ArgumentCaptor.forClass(List.class);
            verify(fanout).equipmentExpiryAlert(any(), any(), roles.capture(), any());

            assertThat(roles.getValue())
                    .containsExactlyInAnyOrder(Role.CAPTAIN, Role.SHIP_MANAGER, Role.TECHNICAL_HEAD);
        }
    }
}
