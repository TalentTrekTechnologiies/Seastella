package com.seastella.notification.internal;

import com.seastella.fleet.api.SoftwareBaselineGateway;
import com.seastella.fleet.api.SoftwareBaselineGateway.OutdatedUnit;
import com.seastella.identity.api.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The client asked for an alert per unit. The whole difficulty is <em>when</em>.
 *
 * <p>A sweep that alerted on everything it found would mail the same forty
 * units every night for as long as they stayed behind; one that alerted only
 * once per unit would go quiet the day the sheet moved on, which is exactly
 * when the office wants telling again. These cases pin both edges.
 */
class SoftwareCurrencyMonitorTest {

    private SoftwareBaselineGateway baselines;
    private SoftwareAlertStateRepository states;
    private NotificationFanout fanout;

    @BeforeEach
    void setUp() {
        baselines = mock(SoftwareBaselineGateway.class);
        states = mock(SoftwareAlertStateRepository.class);
        fanout = mock(NotificationFanout.class);
        when(states.findBySpareIdIn(anyCollection())).thenReturn(List.of());
        // Something has been recorded before, so a sweep is ordinary news
        // rather than the first-ever backlog.
        when(states.count()).thenReturn(1L);
    }

    private SoftwareCurrencyMonitor monitor() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new SoftwareCurrencyMonitor(baselines, states, fanout, transactions);
    }

    private static OutdatedUnit unit(long spareId, String installed, String latest) {
        return new OutdatedUnit(spareId, "AIS Transponder", "1.1", 7L, "MV Kestrel", 3L, installed, latest);
    }

    private static SoftwareAlertState told(long spareId, String installed, String latest) {
        return new SoftwareAlertState(spareId, installed, latest, Instant.now());
    }

    @Nested
    @DisplayName("one alert per gap")
    class OncePerGap {

        @Test
        void announcesAUnitThatHasJustFallenBehind() {
            when(baselines.outdated()).thenReturn(List.of(unit(1L, "5.4", "5.6")));

            monitor().scan();

            verify(fanout).softwareAlert(any(), any(), any());
            verify(states).save(any(SoftwareAlertState.class));
        }

        @Test
        void saysNothingAboutAGapItHasAlreadyReported() {
            when(baselines.outdated()).thenReturn(List.of(unit(1L, "5.4", "5.6")));
            when(states.findBySpareIdIn(anyCollection())).thenReturn(List.of(told(1L, "5.4", "5.6")));

            monitor().scan();

            verify(fanout, never()).softwareAlert(any(), any(), any());
        }

        /** The manufacturer shipped a newer release; the gap is wider than it was. */
        @Test
        void announcesAgainWhenTheSheetMovesOn() {
            when(baselines.outdated()).thenReturn(List.of(unit(1L, "5.4", "5.7")));
            when(states.findBySpareIdIn(anyCollection())).thenReturn(List.of(told(1L, "5.4", "5.6")));

            monitor().scan();

            verify(fanout).softwareAlert(any(), any(), any());
        }

        /** The vessel flashed it, and it is still not current. That is progress worth saying. */
        @Test
        void announcesAgainWhenTheVesselMovesAndIsStillBehind() {
            when(baselines.outdated()).thenReturn(List.of(unit(1L, "5.5", "5.6")));
            when(states.findBySpareIdIn(anyCollection())).thenReturn(List.of(told(1L, "5.4", "5.6")));

            monitor().scan();

            verify(fanout).softwareAlert(any(), any(), any());
        }

        @Test
        void saysNothingAtAllWhenTheFleetIsCurrent() {
            when(baselines.outdated()).thenReturn(List.of());

            monitor().scan();

            verify(fanout, never()).softwareAlert(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the first sheet is a backlog, not a night's news")
    class FirstSweep {

        /**
         * Uploading a master sheet for the first time makes every behind unit
         * in the fleet outdated in one second. Mailing all of it would teach
         * the recipients to filter the sender before the feature was a day old.
         */
        @Test
        void recordsWithoutAlertingWhenNothingHasEverBeenRecorded() {
            when(states.count()).thenReturn(0L);
            when(baselines.outdated()).thenReturn(List.of(
                    unit(1L, "5.4", "5.6"), unit(2L, "3.1", "3.2"), unit(3L, "1.0", "1.2")));

            monitor().scan();

            verify(fanout, never()).softwareAlert(any(), any(), any());
            // But it is remembered, so tomorrow's genuine change is not lost.
            verify(states, times(3)).save(any(SoftwareAlertState.class));
        }
    }

    @Nested
    @DisplayName("what the alert says")
    class Wording {

        @Test
        void namesBothVersionsAndWhereTheUnitIs() {
            when(baselines.outdated()).thenReturn(List.of(unit(1L, "5.4", "5.6")));

            monitor().scan();

            ArgumentCaptor<AlertMessages.Message> message = ArgumentCaptor.forClass(AlertMessages.Message.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Role>> roles = ArgumentCaptor.forClass(List.class);
            verify(fanout).softwareAlert(any(), message.capture(), roles.capture());

            assertThat(message.getValue().title()).isEqualTo("Software update due: AIS Transponder");
            assertThat(message.getValue().body()).contains("MV Kestrel", "5.4", "5.6");
            assertThat(roles.getValue())
                    .containsExactlyInAnyOrder(Role.CAPTAIN, Role.SHIP_MANAGER, Role.TECHNICAL_HEAD);
        }
    }
}
