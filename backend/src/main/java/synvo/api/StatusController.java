package synvo.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class StatusController {

	@GetMapping("/status")
	ServiceStatus status() {
		return new ServiceStatus("synvo-backend", "ready");
	}

	record ServiceStatus(String service, String status) {
	}
}
