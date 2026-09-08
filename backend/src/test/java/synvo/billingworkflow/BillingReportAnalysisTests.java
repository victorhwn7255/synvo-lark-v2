package synvo.billingworkflow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import synvo.billing.BillingException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BillingReportAnalysisTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BillingReportAnalysis analysis = new BillingReportAnalysis(mapper);
    private final UUID reportId = UUID.randomUUID();
    private final synvo.billing.BillingData.Snapshot snapshot = new BillingAnalysisPackageTests().snapshot();
    private final BillingReportFacts facts = new BillingReportFacts(new BigDecimal("0.30"), null, null, null,
            Map.of("Service/v2", new BigDecimal("0.30")), Map.of(), Map.of(), Map.of(), Map.of());

    @Test void acceptsSourceBoundExactClaimAndDatesAndServiceDigits() {
        var statement = new BillingReportAnalysis.Statement("In 2026-07 Service v2 cost [[fact:/selectedTotal]].",
                List.of("fact:/selectedTotal", "row:month:2026-07/0/1"),
                List.of(new BillingReportAnalysis.Claim("/selectedTotal", "0.300")));
        var draft = draft(statement);
        assertEquals(draft, analysis.validate(mapper.writeValueAsString(draft), reportId, snapshot, facts));
        assertEquals("In 2026-07 Service v2 cost USD 0.30.", BillingReportAnalysis.render(statement.text(), facts));
    }

    @Test void rejectsForeignBindingReferenceAndUnsupportedAmountsWithoutBanningAllDigits() {
        var valid = new BillingReportAnalysis.Statement("Review Service v2.", List.of("fact:/selectedTotal"), List.of());
        assertThrows(BillingException.class, () -> analysis.validate(mapper.writeValueAsString(draft(valid)), UUID.randomUUID(), snapshot, facts));
        for (var statement : List.of(
                new BillingReportAnalysis.Statement("Costs doubled.", List.of("fact:/missing"), List.of()),
                new BillingReportAnalysis.Statement("Review.", List.of("row:month:2026-07/0/3"), List.of()),
                new BillingReportAnalysis.Statement("Review.", List.of("fact:/selectedTotal"), List.of(new BillingReportAnalysis.Claim("/selectedTotal", "100"))),
                new BillingReportAnalysis.Statement("Save 50% by deleting it.", List.of(), List.of()),
                new BillingReportAnalysis.Statement("Cost is USD 99.", List.of(), List.of()),
                new BillingReportAnalysis.Statement("Unknown [[fact:/baselineTotal]].", List.of("fact:/baselineTotal"), List.of()),
                new BillingReportAnalysis.Statement("<script>alert(1)</script>", List.of(), List.of()))) {
            assertThrows(BillingException.class, () -> analysis.validate(mapper.writeValueAsString(draft(statement)), reportId, snapshot, facts));
        }
    }

    @Test void questionsUseSameFactValidationAndAllowInsufficientEvidence() {
        var answer = new BillingReportAnalysis.Statement("The saved data has no utilization measurements; savings cannot be established.", List.of(), List.of());
        assertEquals(answer, analysis.validateAnswer(mapper.writeValueAsString(answer), snapshot, facts));
        assertEquals("ANSWER_TOO_LONG", assertThrows(BillingReportAnalysis.InvalidAnswer.class, () -> analysis.validateAnswer("x".repeat(12_001), snapshot, facts)).getMessage());
        assertEquals(new BigDecimal("0.30"), BillingReportAnalysis.catalog(facts).get("/services/Service~1v2"));
    }

    @Test void answerReferenceAndClaimLimitsMatchPromptAndRejectOneOverWithoutWeakeningGrounding() {
        var services = new java.util.LinkedHashMap<String, BigDecimal>();
        for (int i = 0; i < 13; i++) services.put("service-" + i, BigDecimal.ONE);
        var rich = new BillingReportFacts(BigDecimal.ONE, null, null, null, services, Map.of(), Map.of(), Map.of(), Map.of());
        var refs = services.keySet().stream().map(key -> "fact:/services/" + key).toList();
        var claims = services.keySet().stream().map(key -> new BillingReportAnalysis.Claim("/services/" + key, "1")).toList();
        var valid = new BillingReportAnalysis.Statement("Saved evidence supports this summary.", refs.subList(0, 12), claims.subList(0, 12));
        assertEquals(valid, analysis.validateAnswer(mapper.writeValueAsString(valid), snapshot, rich));
        var tooManyRefs = new BillingReportAnalysis.Statement(valid.text(), refs, valid.claims());
        assertEquals("ANSWER_TOO_MANY_REFERENCES", rejected(tooManyRefs, rich));
        var tooManyClaims = new BillingReportAnalysis.Statement(valid.text(), valid.references(), claims);
        assertEquals("ANSWER_TOO_MANY_CLAIMS", rejected(tooManyClaims, rich));
        assertEquals("ANSWER_INVALID", rejected(new BillingReportAnalysis.Statement("Unsupported [[fact:/missing]].", List.of("fact:/missing"), List.of()), rich));
        assertEquals("ANSWER_INVALID", rejected(new BillingReportAnalysis.Statement("Mismatch.", List.of("fact:/selectedTotal"), List.of(new BillingReportAnalysis.Claim("/selectedTotal", "99"))), rich));
        for (boolean question : List.of(true, false)) {
            String instruction = BillingReportAnalysis.outputInstructions(question);
            assertTrue(instruction.contains("at most " + (question ? 12000 : 32000) + " characters total"));
            assertTrue(instruction.contains("3000 text characters, 12 references total (fact and row references combined), 12 claims, 600 characters per reference and 80 per claim value"));
        }
    }

    @Test void answerTextAndPayloadAcceptExactBudgetsAndRejectOneOverWithSafeErrors() {
        var valid = new BillingReportAnalysis.Statement("a".repeat(3000), List.of(), List.of());
        String json = mapper.writeValueAsString(valid);
        assertEquals(valid, analysis.validateAnswer(json + " ".repeat(12000 - json.length()), snapshot, facts));
        assertEquals("ANSWER_TOO_LONG", rejected(new BillingReportAnalysis.Statement("a".repeat(3001), List.of(), List.of()), facts));
        assertEquals("ANSWER_TOO_LONG", assertThrows(BillingReportAnalysis.InvalidAnswer.class,
                () -> analysis.validateAnswer(json + " ".repeat(12001 - json.length()), snapshot, facts)).getMessage());
        var failure = assertThrows(BillingReportAnalysis.InvalidAnswer.class, () -> analysis.validateAnswer("private-invalid-answer", snapshot, facts));
        assertEquals("ANSWER_INVALID", failure.getMessage()); assertNull(failure.getCause());
        assertEquals(BillingException.Reason.SOURCE_INVALID, assertThrows(BillingException.class,
                () -> analysis.validate("private-invalid-draft", reportId, snapshot, facts)).reason());
    }

    private String rejected(BillingReportAnalysis.Statement answer, BillingReportFacts values) {
        return assertThrows(BillingReportAnalysis.InvalidAnswer.class,
                () -> analysis.validateAnswer(mapper.writeValueAsString(answer), snapshot, values)).getMessage();
    }

    private BillingReportAnalysis.Draft draft(BillingReportAnalysis.Statement statement) {
        return new BillingReportAnalysis.Draft(reportId, snapshot.id(), statement, List.of(statement), List.of(), List.of());
    }
}
