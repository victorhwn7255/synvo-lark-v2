package synvo.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import synvo.lark.auth.AesGcmTokenCipher;
import synvo.lark.auth.TokenCipher;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
class LarkSecurityConfiguration {

	@Bean
	TokenCipher tokenCipher(LarkProperties properties) {
		return new AesGcmTokenCipher(properties.tokenEncryptionKey());
	}
}
