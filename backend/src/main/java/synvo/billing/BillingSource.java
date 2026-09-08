package synvo.billing;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** One bounded retrieval session owns provider credentials, scope validation and cumulative limits. */
public interface BillingSource {
    Session open();

    interface Session extends AutoCloseable {
        BillingData.Partition month(YearMonth month, Consumer<BillingData.CostRow> sink);
        /** Daily feed only: one received month, optionally ending at captured UTC today. */
        default BillingData.Partition period(LocalDate first, LocalDate through, Consumer<BillingData.CostRow> sink) {
            throw new BillingException(BillingException.Reason.SOURCE_UNAVAILABLE);
        }
        List<BillingData.Invoice> invoices(Set<String> references, LocalDate first, LocalDate last);
        BillingData.Partition invoice(String reference, Consumer<BillingData.CostRow> sink);
        @Override default void close() { }
    }
}
