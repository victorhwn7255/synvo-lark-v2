package synvo.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LarkUserConnectionRepository {

	private static final String OPEN_ID_PARAMETER = "openId";

	private final JdbcClient jdbcClient;

	public LarkUserConnectionRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public void save(LarkUserConnection connection) {
		jdbcClient.sql("""
				INSERT INTO lark_user_connection (
				    open_id, tenant_key, display_name, access_token_ciphertext,
				    refresh_token_ciphertext, access_expires_at, refresh_expires_at,
				    connection_status, updated_at
				)
				VALUES (
				    :openId, :tenantKey, :displayName, :accessTokenCiphertext,
				    :refreshTokenCiphertext, :accessExpiresAt, :refreshExpiresAt,
				    :connectionStatus, :updatedAt
				)
				ON CONFLICT (open_id) DO UPDATE SET
				    tenant_key = EXCLUDED.tenant_key,
				    display_name = EXCLUDED.display_name,
				    access_token_ciphertext = EXCLUDED.access_token_ciphertext,
				    refresh_token_ciphertext = EXCLUDED.refresh_token_ciphertext,
				    access_expires_at = EXCLUDED.access_expires_at,
				    refresh_expires_at = EXCLUDED.refresh_expires_at,
				    connection_status = EXCLUDED.connection_status,
				    updated_at = EXCLUDED.updated_at
				""")
				.param(OPEN_ID_PARAMETER, connection.openId())
				.param("tenantKey", connection.tenantKey())
				.param("displayName", connection.displayName())
				.param("accessTokenCiphertext", connection.accessTokenCiphertext())
				.param("refreshTokenCiphertext", connection.refreshTokenCiphertext())
				.param("accessExpiresAt", atUtc(connection.accessExpiresAt()))
				.param("refreshExpiresAt", atUtc(connection.refreshExpiresAt()))
				.param("connectionStatus", connection.connectionStatus().name())
				.param("updatedAt", atUtc(connection.updatedAt()))
				.update();
	}

	public Optional<LarkUserConnection> findByOpenId(String openId) {
		return jdbcClient.sql("""
				SELECT open_id, tenant_key, display_name, access_token_ciphertext,
				       refresh_token_ciphertext, access_expires_at, refresh_expires_at,
				       connection_status, updated_at
				FROM lark_user_connection
				WHERE open_id = :openId
				""")
				.param(OPEN_ID_PARAMETER, openId)
				.query(LarkUserConnectionRepository::mapConnection)
				.optional();
	}

	public void markReauthorizationRequired(String openId) {
		jdbcClient.sql("""
				UPDATE lark_user_connection
				SET connection_status = 'REAUTHORIZATION_REQUIRED', updated_at = :now
				WHERE open_id = :openId
				""")
				.param(OPEN_ID_PARAMETER, openId)
				.param("now", atUtc(Instant.now()))
				.update();
	}

	private static LarkUserConnection mapConnection(ResultSet resultSet, int rowNumber)
			throws SQLException {
		return new LarkUserConnection(
				resultSet.getString("open_id"),
				resultSet.getString("tenant_key"),
				resultSet.getString("display_name"),
				resultSet.getString("access_token_ciphertext"),
				resultSet.getString("refresh_token_ciphertext"),
				resultSet.getObject("access_expires_at", OffsetDateTime.class).toInstant(),
				resultSet.getObject("refresh_expires_at", OffsetDateTime.class).toInstant(),
				LarkUserConnection.ConnectionStatus.valueOf(resultSet.getString("connection_status")),
				resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
