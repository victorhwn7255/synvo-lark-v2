package synvo.billingworkflow;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Validates one source-bound draft; numeric authority remains in the supplied Java facts. */
public final class BillingReportAnalysis {
    private static final int DRAFT_CHARS = 32_000, ANSWER_CHARS = 12_000, TEXT_CHARS = 3_000;
    private static final int REFERENCES = 12, CLAIMS = 12, REFERENCE_CHARS = 600, CLAIM_VALUE_CHARS = 80;
    private static final Pattern SLOT = Pattern.compile("\\[\\[fact:([^]\\r\\n]{1,512})]]");
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:[$€£]\\s*-?\\d|\\b(?:USD|EUR|GBP)\\s*-?\\d|\\d[\\d,.]*\\s*(?:%|percent\\b|USD\\b|dollars?\\b))");
    private final ObjectMapper mapper;

    public BillingReportAnalysis(ObjectMapper mapper) { this.mapper = mapper; }

    public Draft validate(String raw, UUID reportId, BillingData.Snapshot snapshot, BillingReportFacts facts) {
        try {
            if (raw == null || raw.length() > DRAFT_CHARS) throw invalid();
            Draft draft = mapper.readValue(raw.strip(), Draft.class);
            if (!reportId.equals(draft.reportId()) || !snapshot.id().equals(draft.snapshotId())) throw invalid();
            var catalog = catalog(facts);
            var rows = evidenceBounds(snapshot);
            statement(draft.executiveSummary(), catalog, rows);
            statements(draft.costDrivers(), 8, catalog, rows);
            statements(draft.periodChanges(), 8, catalog, rows);
            if (draft.optimizationPriorities() == null || draft.optimizationPriorities().size() > 3) throw invalid();
            for (Priority priority : draft.optimizationPriorities()) {
                if (priority == null) throw invalid();
                statement(priority.evidence(), catalog, rows);
                prose(priority.hypothesis(), catalog, List.of());
                prose(priority.missingInputs(), catalog, List.of());
                prose(priority.risk(), catalog, List.of());
                prose(priority.nextStep(), catalog, List.of());
            }
            return draft;
        } catch (JacksonException | NullPointerException | IllegalArgumentException exception) { throw invalid(); }
    }

    public Statement validateAnswer(String raw, BillingData.Snapshot snapshot, BillingReportFacts facts) {
        try {
            if (raw == null) throw invalid();
            if (raw.length() > ANSWER_CHARS) throw new InvalidAnswer("ANSWER_TOO_LONG");
            Statement answer = mapper.readValue(raw.strip(), Statement.class);
            if (answer == null) throw invalid();
            if (answer.text() != null && answer.text().length() > TEXT_CHARS) throw new InvalidAnswer("ANSWER_TOO_LONG");
            if (answer.references() != null && answer.references().size() > REFERENCES) throw new InvalidAnswer("ANSWER_TOO_MANY_REFERENCES");
            if (answer.claims() != null && answer.claims().size() > CLAIMS) throw new InvalidAnswer("ANSWER_TOO_MANY_CLAIMS");
            statement(answer, catalog(facts), evidenceBounds(snapshot));
            return answer;
        } catch (BillingException | JacksonException | NullPointerException | IllegalArgumentException exception) {
            throw new InvalidAnswer("ANSWER_INVALID");
        }
    }

    static String outputInstructions(boolean question) {
        return "\nOutput budget: at most " + (question ? ANSWER_CHARS : DRAFT_CHARS) + " characters total. "
                + "Each statement: at most " + TEXT_CHARS + " text characters, " + REFERENCES + " references total (fact and row references combined), "
                + CLAIMS + " claims, " + REFERENCE_CHARS + " characters per reference and " + CLAIM_VALUE_CHARS + " per claim value. "
                + "Use only the strongest necessary citations; prefer a few over the maximum. Count references before responding. "
                + "If detail would exceed a limit, shorten the answer and reduce its scope while retaining references for every fact slot and claim.\n";
    }

    /** Fixed safe category only; never retain the rejected payload or parsing cause. */
    static final class InvalidAnswer extends RuntimeException {
        InvalidAnswer(String code) { super(code); }
    }

    private static void statements(List<Statement> statements, int limit, Map<String, BigDecimal> catalog,
            Map<String, Long> rows) {
        if (statements == null || statements.size() > limit) throw invalid();
        statements.forEach(statement -> statement(statement, catalog, rows));
    }

    private static void statement(Statement statement, Map<String, BigDecimal> catalog, Map<String, Long> rows) {
        if (statement == null || statement.references() == null || statement.references().size() > REFERENCES
                || statement.claims() == null || statement.claims().size() > CLAIMS) throw invalid();
        for (String reference : statement.references()) {
            if (reference == null || reference.length() > REFERENCE_CHARS) throw invalid();
            if (reference.startsWith("fact:")) {
                if (!catalog.containsKey(reference.substring(5))) throw invalid();
            } else if (reference.startsWith("row:")) {
                int split = reference.lastIndexOf('/');
                if (split < 5) throw invalid();
                String partition = reference.substring(4, split);
                long ordinal;
                try { ordinal = Long.parseLong(reference.substring(split + 1)); }
                catch (NumberFormatException exception) { throw invalid(); }
                if (ordinal < 1 || ordinal > rows.getOrDefault(partition, 0L)) throw invalid();
            } else throw invalid();
        }
        for (Claim claim : statement.claims()) {
            if (claim == null || claim.fact() == null || claim.value() == null || claim.value().length() > CLAIM_VALUE_CHARS
                    || !statement.references().contains("fact:" + claim.fact())) throw invalid();
            BigDecimal authoritative = catalog.get(claim.fact());
            if (authoritative == null || authoritative.compareTo(new BigDecimal(claim.value())) != 0) throw invalid();
        }
        prose(statement.text(), catalog, statement.references());
    }

    private static void prose(String text, Map<String, BigDecimal> catalog, List<String> references) {
        if (text == null || text.isBlank() || text.length() > TEXT_CHARS || text.indexOf('\0') >= 0
                || text.contains("<") || text.contains(">")) throw invalid();
        var matcher = SLOT.matcher(text);
        StringBuilder plain = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!catalog.containsKey(key) || !references.contains("fact:" + key)) throw invalid();
            matcher.appendReplacement(plain, " verified value ");
        }
        matcher.appendTail(plain);
        if (plain.indexOf("[[fact:") >= 0 || MONEY.matcher(plain).find()) throw invalid();
    }

    public static String render(String text, BillingReportFacts facts) {
        var catalog = catalog(facts);
        var matcher = SLOT.matcher(text);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            BigDecimal amount = catalog.get(matcher.group(1));
            if (amount == null) throw invalid();
            String value = matcher.group(1).equals("/percent") ? BillingReportPresentation.percent(amount)
                    : BillingReportPresentation.money(amount);
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return BillingReportPresentation.narrative(output.toString());
    }

    static Map<String, BigDecimal> catalog(BillingReportFacts facts) {
        var result = new HashMap<String, BigDecimal>();
        put(result, "/selectedTotal", facts.selectedTotal()); put(result, "/baselineTotal", facts.baselineTotal());
        put(result, "/delta", facts.delta()); put(result, "/percent", facts.percent());
        Map.of("services", facts.services(), "months", facts.months(), "subscriptions", facts.subscriptions(),
                "serviceChanges", facts.serviceChanges()).forEach((group, values) ->
                values.forEach((key, value) -> put(result, "/" + group + "/" + pointer(key), value)));
        facts.buckets().forEach((key, value) -> put(result, "/buckets/" + key.name() + "/cost", value.cost()));
        return Map.copyOf(result);
    }

    private static void put(Map<String, BigDecimal> facts, String key, BigDecimal amount) {
        if (amount != null) facts.put(key, amount);
    }
    private static String pointer(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    private static Map<String, Long> evidenceBounds(BillingData.Snapshot snapshot) {
        var result = new HashMap<String, Long>();
        snapshot.summary().partitions().forEach(partition -> partition.parts().forEach(part ->
                result.put(partition.dataset() + "/" + part.ordinal(), part.rows())));
        return result;
    }
    private static BillingException invalid() { return new BillingException(BillingException.Reason.SOURCE_INVALID); }

    public record Claim(String fact, String value) { }
    public record Statement(String text, List<String> references, List<Claim> claims) {
        public Statement { references = references == null ? null : List.copyOf(references); claims = claims == null ? null : List.copyOf(claims); }
        @Override public String toString() { return "BillingStatement[protected]"; }
    }
    public record Priority(Statement evidence, String hypothesis, String missingInputs, String risk, String nextStep) {
        @Override public String toString() { return "BillingPriority[protected]"; }
    }
    public record Draft(UUID reportId, UUID snapshotId, Statement executiveSummary,
            List<Statement> costDrivers, List<Statement> periodChanges, List<Priority> optimizationPriorities) {
        public Draft {
            costDrivers = costDrivers == null ? null : List.copyOf(costDrivers);
            periodChanges = periodChanges == null ? null : List.copyOf(periodChanges);
            optimizationPriorities = optimizationPriorities == null ? null : List.copyOf(optimizationPriorities);
        }
        @Override public String toString() { return "BillingDraft[protected]"; }
    }
}
