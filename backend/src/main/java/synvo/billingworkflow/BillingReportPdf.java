package synvo.billingworkflow;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import synvo.billing.BillingData;
import synvo.billing.BillingException;

/** Deterministic private renderer: no model code, HTML, remote assets or temporary company files. */
public final class BillingReportPdf {
    public byte[] render(UUID reportId, BillingData.Snapshot snapshot, BillingReportFacts facts, BillingReportAnalysis.Draft analysis) {
        return render(new BillingWorkflowStore.Report(reportId, snapshot.id(), snapshot.range(), snapshot.createdAt(), snapshot.expiresAt(), facts, snapshot.summary(), analysis, null, null, null));
    }

    public byte[] render(BillingWorkflowStore.Report report) {
        var view = BillingReportPresentation.of(report);
        var facts = report.facts(); var source = report.source(); var analysis = report.analysis();
        try (var document = new PDDocument(); var layout = new Layout(document)) {
            document.getDocumentInformation().setTitle("Synvo Billing Insights");
            document.getDocumentInformation().setAuthor("Synvo");
            layout.heading("Billing Insights", 26);
            layout.paragraph(view.period() + " | Synergetic Evolution | Azure subscriptions");
            layout.paragraph("USD actual cost, before tax | Compared with " + view.comparisonPeriod());
            layout.heading("At a glance", 17);
            layout.paragraph("Period cost: " + view.total() + "     Comparison: " + view.baseline());
            layout.paragraph("Change: " + view.change() + " (" + view.percent() + ")");
            layout.heading("What your team should know", 16);
            for (String highlight : view.highlights()) layout.paragraph(highlight);
            layout.heading("Coverage and confidence", 16);
            for (String coverage : view.coverage()) layout.paragraph(coverage);
            layout.paragraph("Provisional: Azure may revise these charges. Cost data alone does not prove waste or achievable savings.");
            if (analysis == null) layout.paragraph("Analysis unavailable. This is a factual report only; no AI recommendations were published.");
            layout.paragraph("Figures are rounded for readability; source evidence and calculations retain full precision. Nonzero sub-cent values are marked explicitly.");

            layout.section("Cost breakdown");
            layout.heading("Services", 16); layout.rows(view.services());
            layout.heading("Selected months", 16); layout.rows(view.months());
            layout.heading("Comparison months", 16); layout.rows(view.baselineMonths());
            layout.heading("Largest service changes", 16); layout.rows(view.serviceChanges());
            layout.paragraph("Service names are grouped case-insensitively for presentation. Underlying evidence and totals are unchanged.");

            if (analysis != null) {
                layout.section("Analysis and next steps");
                layout.heading("Executive interpretation", 16); layout.statement(analysis.executiveSummary(), facts);
                layout.heading("Cost drivers and changes", 16);
                for (var driver : analysis.costDrivers()) layout.statement(driver, facts);
                for (var change : analysis.periodChanges()) layout.statement(change, facts);
                layout.heading("Cost optimization priorities", 16);
                layout.paragraph("Investigation candidates, not verified savings. No Azure resources have been changed.");
                if (analysis.optimizationPriorities().isEmpty()) layout.paragraph("No supported optimization priority was established.");
                int index = 0;
                for (var priority : analysis.optimizationPriorities()) {
                    var paragraphs = java.util.List.of(BillingReportAnalysis.render(priority.evidence().text(), facts),
                            "What to investigate: " + BillingReportPresentation.narrative(priority.hypothesis()),
                            "Evidence needed: " + priority.missingInputs(), "Risk to manage: " + priority.risk(),
                            "Next action: " + priority.nextStep());
                    layout.keep(paragraphs, 55);
                    layout.heading("Priority " + (++index), 14);
                    for (String paragraph : paragraphs) layout.paragraph(paragraph);
                    layout.references(priority.evidence().references());
                }
            }
            layout.section("Appendix | Sources and reconciliation");
            layout.paragraph("Report: " + report.id()); layout.paragraph("Snapshot: " + report.snapshotId());
            var date = java.time.format.DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm 'UTC'", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC);
            layout.paragraph("Report record: " + date.format(report.createdAt()) + " | Available until: " + date.format(report.expiresAt()));
            layout.paragraph("Basis: actual cost assigned to the billing period in which charges were received. Mapping: " + source.mappingVersion() + "; calculation: " + source.calculationVersion() + "; presentation: readable-v2.");
            layout.heading("Subscriptions", 16); layout.rows(view.subscriptions());
            layout.heading("Attribution", 16); layout.rows(view.buckets());
            layout.paragraph("Profile adjustments, excluded charges and unresolved attribution are separate from Azure subscription cost. Monthly cost and invoice datasets are not added together.");
            layout.heading("Invoice reconciliation", 16);
            if (view.reconciliation().isEmpty()) layout.paragraph("Invoice reconciliation unavailable.");
            for (String value : view.reconciliation()) layout.paragraph(value);
            for (var invoice : source.invoices()) {
                var rows = invoice.amounts().entrySet().stream()
                        .filter(entry -> java.util.Set.of("billedAmount", "taxAmount", "totalAmount", "amountDue").contains(entry.getKey()) || entry.getValue().signum() != 0)
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new BillingWorkflowFacade.DisplayRow(invoiceLabel(entry.getKey()), money(entry.getValue()))).toList();
                layout.keep(rows.stream().map(row -> row.label() + " " + row.amount()).toList(), 60);
                layout.heading("Invoice " + invoice.id(), 12);
                layout.paragraph(invoice.first() + " to " + invoice.last()); layout.rows(rows);
            }
            layout.heading("Limitations", 16);
            for (String limitation : source.limitations().stream().sorted().toList()) layout.paragraph(limitation.replace('_', ' '));
            layout.section("Appendix | Evidence references");
            layout.paragraph("These references identify the saved facts and source partitions behind the analysis. Full-precision values remain in the authorized evidence view.");
            layout.paragraph("Fact and row references: " + String.join("; ", layout.evidenceReferences));
            for (var partition : source.partitions()) {
                layout.keep(java.util.List.of(partition.dataset()), 65 + partition.parts().size() * 50);
                layout.heading(partition.dataset().startsWith("month:") ? BillingReportPresentation.month(partition.dataset()) : partition.dataset(), 12);
                layout.paragraph("Retrieved: " + date.format(partition.retrievedAt()) + " | Attribution " + (partition.attributionComplete() ? "complete" : "incomplete"));
                for (var part : partition.parts()) layout.paragraph("Part " + part.ordinal() + " | " + part.rows() + " rows | SHA-256 " + part.sha256());
            }
            layout.finish();
            var output = new ByteArrayOutputStream(); document.save(output);
            if (output.size() > 10 * 1024 * 1024) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException exception) { throw new BillingException(BillingException.Reason.SOURCE_INVALID); }
    }

    private static String money(BigDecimal amount) { return BillingReportPresentation.money(amount); }
    private static String invoiceLabel(String value) {
        String words = value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ENGLISH);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static final class Layout implements AutoCloseable {
        private static final float MARGIN = 48, WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
        private final PDDocument document;
        private final PDType0Font font;
        private final java.util.Set<String> evidenceReferences = new java.util.LinkedHashSet<>();
        private PDPageContentStream stream;
        private float y;
        Layout(PDDocument document) throws IOException {
            this.document = document;
            try (var resource = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                if (resource == null) throw new IOException("Bundled report font unavailable");
                font = PDType0Font.load(document, resource, true);
            }
            page();
        }
        void page() throws IOException {
            if (stream != null) stream.close();
            if (document.getNumberOfPages() >= 50) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page); y = PDRectangle.A4.getHeight() - 55;
        }
        void heading(String text, float size) throws IOException {
            if (y < 130) page();
            y -= 12; line(text, size, new Color(42, 56, 114)); y -= 6;
        }
        void section(String title) throws IOException { page(); heading(title, 20); }
        void keep(java.util.List<String> paragraphs, float extra) throws IOException {
            float height = extra;
            for (String paragraph : paragraphs) height += (Math.ceil(font.getStringWidth(paragraph.replaceAll("\\s+", " ")) / 1000 * 10 / WIDTH) + 1) * 14.5f + 6;
            if (height < 680 && y - height < 65) page();
        }
        void rows(java.util.List<BillingWorkflowFacade.DisplayRow> rows) throws IOException {
            if (rows.isEmpty()) { paragraph("No supported values available."); return; }
            for (var row : rows) {
                float amountWidth = font.getStringWidth(row.amount()) / 1000 * 10;
                float labelWidth = font.getStringWidth(row.label()) / 1000 * 10;
                if (amountWidth + labelWidth + 25 > WIDTH) { keep(java.util.List.of(row.label(), row.amount()), 5); paragraph(row.label() + " | " + row.amount()); continue; }
                if (y < 80) page();
                stream.setNonStrokingColor(Color.DARK_GRAY); stream.beginText(); stream.setFont(font, 10);
                stream.newLineAtOffset(MARGIN + WIDTH - amountWidth, y); stream.showText(row.amount()); stream.endText();
                line(row.label(), 10, Color.DARK_GRAY); y -= 5;
            }
        }
        void paragraph(String text) throws IOException {
            String normalized = text.replaceAll("\\s+", " ").strip();
            StringBuilder line = new StringBuilder();
            for (int codePoint : normalized.codePoints().toArray()) {
                String next = new String(Character.toChars(codePoint));
                if (font.getStringWidth(line + next) / 1000 * 10 > WIDTH) {
                    int breakAt = line.lastIndexOf(" ");
                    if (breakAt > line.length() / 2) {
                        line(line.substring(0, breakAt), 10, Color.DARK_GRAY);
                        line.delete(0, breakAt + 1);
                    } else { line(line.toString(), 10, Color.DARK_GRAY); line.setLength(0); }
                }
                line.append(next);
            }
            if (!line.isEmpty()) line(line.toString(), 10, Color.DARK_GRAY);
            y -= 6;
        }
        void line(String value, float size, Color color) throws IOException {
            if (y < 65) page();
            stream.setNonStrokingColor(color); stream.beginText(); stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y); stream.showText(value); stream.endText(); y -= size * 1.45f;
        }
        void statement(BillingReportAnalysis.Statement statement, BillingReportFacts facts) throws IOException {
            paragraph(BillingReportAnalysis.render(statement.text(), facts));
            references(statement.references());
        }
        void references(java.util.List<String> references) { evidenceReferences.addAll(references); }
        void finish() throws IOException {
            close(); int pageNumber = 0;
            for (var page : document.getPages()) {
                try (var footer = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    footer.setNonStrokingColor(Color.GRAY); footer.beginText(); footer.setFont(font, 8);
                    footer.newLineAtOffset(MARGIN, 32);
                    footer.showText("SYNVO | Confidential billing report                         " + (++pageNumber) + " / " + document.getNumberOfPages());
                    footer.endText();
                }
            }
        }
        @Override public void close() throws IOException { if (stream != null) { stream.close(); stream = null; } }
    }
}
