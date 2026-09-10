package synvo.billing;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailySpendingFacadeTests {
    @Test void unsupportedOldPrecisionLeavesGapButPublishesNewerMonthInSameSession() throws Exception {
        exercise(BillingException.Reason.UNSUPPORTED_PRECISION, true);
    }

    @Test void campaignLimitStopsWithoutAttemptingAnotherMonth() throws Exception {
        exercise(BillingException.Reason.LIMIT_EXCEEDED, false);
    }

    private void exercise(BillingException.Reason reason, boolean continueAfterFailure) throws Exception {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        var old = new DailySpendingStore.Period(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30));
        var recent = new DailySpendingStore.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8));
        UUID id = UUID.randomUUID(), version = UUID.randomUUID();
        var run = new DailySpendingStore.Run(id, "RUNNING", now, null, List.of(old, recent), 0, 0, null);
        var store = mock(DailySpendingStore.class);
        var source = mock(BillingSource.class);
        var session = mock(BillingSource.Session.class);
        var finished = new CountDownLatch(1);
        when(store.claim(anyString(), anyString(), anyString(), any(), any())).thenReturn(new DailySpendingStore.Claim(run, true));
        when(store.stagePartition(anyString(), anyString(), eq(id), any(), any())).thenReturn(version);
        when(source.open()).thenReturn(session);
        when(session.period(eq(old.first()), eq(old.through()), any())).thenThrow(new BillingException(reason));
        var evidence = new BillingData.Partition("month:2026-09", now, "synthetic", Map.of(), List.of(), Set.of(), Set.of(), true, true);
        when(session.period(eq(recent.first()), eq(recent.through()), any())).thenReturn(evidence);
        doAnswer(call -> { finished.countDown(); return null; }).when(store).finish(anyString(), anyString(), eq(id), nullable(BillingException.Reason.class), any());
        try (var facade = new DailySpendingFacade(store, source, "owner", "revision", true, YearMonth.of(2025, 9), Clock.fixed(now, ZoneOffset.UTC))) {
            assertEquals(id, facade.refresh("owner", "request-1").id());
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            verify(source).open();
            verify(session).close();
            if (continueAfterFailure) {
                verify(store).skip(eq("owner"), anyString(), eq(id), eq(version), eq(reason), eq(now));
                verify(store).publish(eq("owner"), anyString(), eq(id), eq(version), eq(evidence), eq(now));
                verify(store).finish(eq("owner"), anyString(), eq(id), isNull(), eq(now));
            } else {
                verify(session, never()).period(eq(recent.first()), eq(recent.through()), any());
                verify(store, never()).publish(anyString(), anyString(), any(), any(), any(), any());
                verify(store).finish(eq("owner"), anyString(), eq(id), eq(reason), eq(now));
            }
            verify(session, never()).month(any(), any());
            verify(session, never()).invoices(any(), any(), any());
        }
    }
}
