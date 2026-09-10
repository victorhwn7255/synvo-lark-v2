package synvo.api;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billingworkflow.BillingWorkflowFacade;
import synvo.billingworkflow.BillingWorkflowStore;
import synvo.workspaceagent.WorkspaceAgentException;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;

@RestController @RequestMapping("/api/billing-insights")
class BillingWorkflowController {
    private final BillingWorkflowFacade facade;
    private final LarkSessionAccess sessions;
    BillingWorkflowController(BillingWorkflowFacade facade, LarkSessionAccess sessions) { this.facade = facade; this.sessions = sessions; }
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping StateView state(HttpSession session) {
        String owner = owner(session);
        var works = facade.recent(owner);
        UUID latest = works.stream().filter(work -> work.kind() == BillingWorkflowStore.Kind.GENERATION
                && (work.state() == BillingWorkflowStore.State.COMPLETE || work.state() == BillingWorkflowStore.State.FACTUAL))
                .map(BillingWorkflowStore.Work::id).findFirst().orElse(null);
        return new StateView(facade.available(owner), works.stream().map(WorkView::of).toList(), latest);
    }
    @PostMapping("/generations") WorkView generate(@RequestBody Generate body, HttpSession session) {
        try { return WorkView.of(facade.generate(owner(session), new BillingData.Range(YearMonth.parse(body.first()), YearMonth.parse(body.last()), true), body.key(), body.workspaceWrite())); }
        catch (java.time.DateTimeException | NullPointerException invalid) { throw new BillingException(BillingException.Reason.INVALID_REQUEST); }
    }
    @GetMapping("/work/{id}") WorkView work(@PathVariable UUID id, HttpSession session) { return WorkView.of(facade.work(owner(session), id)); }
    @PostMapping("/work/{id}/stop") WorkView stop(@PathVariable UUID id, HttpSession session) { return WorkView.of(facade.stop(owner(session), id)); }
    @GetMapping("/reports/{id}") BillingWorkflowFacade.ReportView report(@PathVariable UUID id, HttpSession session) { return facade.reportView(owner(session), id); }
    @GetMapping("/reports/{id}/daily-costs") synvo.billingworkflow.BillingDailyCosts dailyCosts(@PathVariable UUID id,
            @RequestParam(required = false) Integer year, HttpSession session) { return facade.dailyCosts(owner(session), id, year); }
    @GetMapping("/reports") BillingWorkflowFacade.Page<BillingWorkflowFacade.SavedAnalysis> history(@RequestParam(required = false) UUID before, HttpSession session) {
        return facade.savedAnalyses(owner(session), before);
    }
    @GetMapping("/reports/{id}/questions") BillingWorkflowFacade.Page<WorkView> questions(@PathVariable UUID id, @RequestParam(required = false) UUID before, HttpSession session) {
        var page = facade.questions(owner(session), id, before);
        return new BillingWorkflowFacade.Page<>(page.items().stream().map(WorkView::of).toList(), page.nextCursor());
    }
    @GetMapping("/reports/{id}/pdf") ResponseEntity<byte[]> pdf(@PathVariable UUID id, HttpSession session) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"billing-insights-" + id + ".pdf\"").body(facade.pdf(owner(session), id));
    }
    @GetMapping("/reports/{id}/evidence") List<BillingWorkflowFacade.EvidenceView> evidence(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit, HttpSession session) {
        return facade.evidenceView(owner(session), id, offset, limit);
    }
    @PostMapping("/reports/{id}/retry") WorkView retry(@PathVariable UUID id, @RequestBody Retry body, HttpSession session) {
        return WorkView.of(facade.retry(owner(session), id, body.key(), body.workspaceWrite()));
    }
    @PostMapping("/reports/{id}/questions") WorkView ask(@PathVariable UUID id, @RequestBody Question body, HttpSession session) {
        return WorkView.of(facade.ask(owner(session), id, body.question(), body.key()));
    }
    @GetMapping("/work/{id}/answer") Object answer(@PathVariable UUID id, HttpSession session) { return facade.answerView(owner(session), id); }
    @GetMapping("/work/{id}/activity") Object activity(@PathVariable UUID id, @RequestParam(defaultValue = "-1") long after, HttpSession session) { return facade.activity(owner(session), id, after); }
    @GetMapping("/work/{id}/interactions") Object interactions(@PathVariable UUID id, HttpSession session) { return facade.interactions(owner(session), id); }
    @PostMapping("/work/{id}/interactions/{interaction}") void decide(@PathVariable UUID id, @PathVariable UUID interaction, @RequestBody Decision body, HttpSession session) {
        facade.decide(owner(session), id, interaction, body.decision(), body.values());
    }
    private String owner(HttpSession session) { return sessions.require(session).openId(); }
    @ExceptionHandler(LarkSessionAccess.UnauthorizedSessionException.class) ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Cache-Control", "no-store").body(Map.of("error", "UNAUTHORIZED"));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> malformed() {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("error", "INVALID_REQUEST"));
    }
    @ExceptionHandler(BillingException.class) ResponseEntity<Map<String, String>> failure(BillingException exception) {
        HttpStatus status = switch (exception.reason()) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case BUSY, NOT_READY -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", exception.reason().name()));
    }
    @ExceptionHandler(WorkspaceAgentException.class) ResponseEntity<Map<String, String>> agentFailure(WorkspaceAgentException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header("Cache-Control", "no-store").body(Map.of("error", exception.code().name()));
    }
    record StateView(boolean enabled, List<WorkView> works, UUID reportId) { }
    record WorkView(UUID id, String kind, String state, String first, String last, UUID parentReportId, String question, String failure) {
        static WorkView of(BillingWorkflowStore.Work work) { return new WorkView(work.id(), work.kind().name(), work.state().name(), work.range().first().toString(), work.range().last().toString(), work.parentReportId(), work.question(), work.failure()); }
    }
    record Generate(String first, String last, String key, boolean workspaceWrite) { }
    record Retry(String key, boolean workspaceWrite) { }
    record Question(String key, String question) { }
    record Decision(InteractionDecision decision, Map<String, String> values) { }
}
