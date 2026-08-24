package synvo.configuration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@Import(WebSecurityConfiguration.class)
public class WebSecurityTestConfiguration {
}
