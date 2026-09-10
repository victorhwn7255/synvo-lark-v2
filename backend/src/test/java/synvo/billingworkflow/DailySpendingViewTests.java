package synvo.billingworkflow;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import synvo.billing.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DailySpendingViewTests {
    @Test void gapsEmptyCoverageSourceDatesAndBandsAreIndependentOfReports() {
        var daily = mock(DailySpendingFacade.class);
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        UUID version = UUID.randomUUID();
        when(daily.read(eq("owner"), any())).thenAnswer(call -> {
            Consumer<BillingData.CostRow> sink = call.getArgument(1);
            sink.accept(row("2026-09", "2026-08-30", "0.10", 1));
            sink.accept(row("2026-09", "2026-08-30", "0.10", 2));
            sink.accept(row("2026-09", "2026-09-02", "0.20", 3));
            sink.accept(row("2026-09", "2026-09-10", "-0.10", 4));
            return new DailySpendingStore.Feed(List.of(new DailySpendingStore.Partition(version,
                    new DailySpendingStore.Period(LocalDate.of(2026,9,1), LocalDate.of(2026,9,8)), now, 4)), null, null);
        });
        var view = new DailySpendingView(daily, YearMonth.of(2025,9), Clock.fixed(now, ZoneOffset.UTC));
        var result = view.read("owner", null);
        assertEquals(12, result.missingMonths().size());
        assertNull(result.coveredThrough()); // A later successful month cannot hide twelve earlier holes.
        assertEquals("2026-09-02", result.observedThrough());
        assertTrue(result.provisional());
        assertEquals("0.30", result.calendar().selectedTotal().exact());
        assertEquals(2, result.calendar().spilloverRows());
        assertEquals("FUTURE_RECORDED", result.calendar().days().get(252).state());
        var prior = view.read("owner", 2025);
        assertEquals(result.revision(), prior.revision());
        assertEquals(result.calendar().bands(), prior.calendar().bands());
        assertEquals("0", prior.calendar().visibleTotal().exact());
        verify(daily, never()).refresh(any(), any());
    }
    private BillingData.CostRow row(String month, String date, String cost, long ordinal) {
        return new BillingData.CostRow("month:" + month, 0, ordinal, LocalDate.parse(date), "USD", new BigDecimal(cost), BillingData.Bucket.AZURE,
                "11111111-1111-1111-1111-111111111111", "Storage", "Usage", "", Map.of());
    }
}
