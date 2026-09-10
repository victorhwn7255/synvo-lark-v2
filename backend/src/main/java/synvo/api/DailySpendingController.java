package synvo.api;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import synvo.billing.BillingException;
import synvo.billingworkflow.DailySpendingView;

@RestController @RequestMapping("/api/billing-insights/daily")
class DailySpendingController {
    private final DailySpendingView daily;
    private final LarkSessionAccess sessions;
    DailySpendingController(DailySpendingView daily, LarkSessionAccess sessions) { this.daily = daily; this.sessions = sessions; }
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping DailySpendingView.View read(@RequestParam(required = false) Integer year, HttpSession session) {
        return daily.read(sessions.require(session).openId(), year);
    }
    @PostMapping("/refresh") DailySpendingView.Refresh refresh(@RequestBody Request body, HttpSession session) {
        return daily.refresh(sessions.require(session).openId(), body.key());
    }
    @ExceptionHandler(LarkSessionAccess.UnauthorizedSessionException.class) ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).header("Cache-Control", "no-store").body(Map.of("error", "UNAUTHORIZED"));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> malformed() { return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("error", "INVALID_REQUEST")); }
    @ExceptionHandler(BillingException.class) ResponseEntity<Map<String, String>> failure(BillingException error) {
        int status = switch (error.reason()) {
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case INVALID_REQUEST -> 400;
            case BUSY, NOT_READY -> 409;
            default -> 503;
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("error", error.reason().name()));
    }
    record Request(String key) { }
}
