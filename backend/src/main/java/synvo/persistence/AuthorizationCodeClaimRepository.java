package synvo.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthorizationCodeClaimRepository {

	private final JdbcClient jdbcClient;

	public AuthorizationCodeClaimRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public boolean tryClaim(String codeHash) {
		return jdbcClient.sql("""
				INSERT INTO lark_authorization_code_claim (code_hash, claimed_at)
				VALUES (:codeHash, :claimedAt)
				ON CONFLICT (code_hash) DO NOTHING
				""")
				.param("codeHash", codeHash)
				.param("claimedAt", Instant.now().atOffset(ZoneOffset.UTC))
				.update() == 1;
	}

	public int deleteClaimedBefore(Instant cutoff) {
		return jdbcClient.sql("DELETE FROM lark_authorization_code_claim WHERE claimed_at < :cutoff")
				.param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
				.update();
	}
}
