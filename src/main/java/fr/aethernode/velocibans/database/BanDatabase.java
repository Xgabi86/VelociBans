package fr.aethernode.velocibans.database;

import org.h2.jdbcx.JdbcConnectionPool;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;

/**
 * H2 embedded database — single {@code bans.mv.db} file next to {@code config.yml}.
 *
 * <p>Schema:
 * <pre>
 * bans (
 *   id          BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   player_name VARCHAR(36) NOT NULL,   -- lowercase username
 *   ip          VARCHAR(45),             -- null for name-bans
 *   reason      TEXT        NOT NULL,
 *   staff_name  VARCHAR(36) NOT NULL,
 *   banned_at   BIGINT      NOT NULL,   -- epoch seconds
 *   expires_at  BIGINT                  -- null = permanent
 * )
 *
 * ip_cache (
 *   player_name VARCHAR(36) PRIMARY KEY,
 *   ip          VARCHAR(45) NOT NULL,
 *   seen_at     BIGINT      NOT NULL    -- epoch seconds, updated on each login
 * )
 * </pre>
 *
 * <p>Only <em>active</em> bans are stored: expired rows are purged lazily on lookup.
 * The ip_cache table allows /banip on offline players by remembering their last IP.
 */
public final class BanDatabase implements AutoCloseable {

    private final JdbcConnectionPool pool;

    public BanDatabase(Path dataDirectory) throws SQLException {
        String url = "jdbc:h2:" + dataDirectory.resolve("bans").toAbsolutePath()
                + ";AUTO_SERVER=FALSE;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
        pool = JdbcConnectionPool.create(url, "sa", "");
        pool.setMaxConnections(4);
        initSchema();
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void initSchema() throws SQLException {
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS bans (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_name VARCHAR(36) NOT NULL,
                        ip          VARCHAR(45),
                        reason      TEXT        NOT NULL,
                        staff_name  VARCHAR(36) NOT NULL,
                        banned_at   BIGINT      NOT NULL,
                        expires_at  BIGINT
                    )
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_bans_player ON bans(player_name)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_bans_ip     ON bans(ip)");

            // Cache IP → joueur pour permettre /banip sur joueurs hors-ligne
            s.execute("""
                    CREATE TABLE IF NOT EXISTS ip_cache (
                        player_name VARCHAR(36) PRIMARY KEY,
                        ip          VARCHAR(45) NOT NULL,
                        seen_at     BIGINT      NOT NULL
                    )
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_ip_cache_ip ON ip_cache(ip)");
        }
    }

    // ── IP cache ──────────────────────────────────────────────────────────────

    /**
     * Persists (or updates) the last known IP for a player.
     * Called by the login listener on every successful connection.
     */
    public void upsertIpCache(String playerName, String ip) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     MERGE INTO ip_cache (player_name, ip, seen_at) KEY(player_name)
                     VALUES (?, ?, ?)
                     """)) {
            ps.setString(1, playerName.toLowerCase());
            ps.setString(2, ip);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
    }

    /**
     * Returns the last known IP for an offline player, or {@code null} if unknown.
     */
    public String getCachedIp(String playerName) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ip FROM ip_cache WHERE player_name = ?")) {
            ps.setString(1, playerName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("ip") : null;
            }
        }
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /** Inserts a name-ban, replacing any existing active ban for that player. */
    public void addBan(String playerName, String reason, String staffName, Instant expiresAt)
            throws SQLException {
        removeBan(playerName);
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bans (player_name, ip, reason, staff_name, banned_at, expires_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, playerName.toLowerCase());
            ps.setNull(2, Types.VARCHAR);
            ps.setString(3, reason);
            ps.setString(4, staffName);
            ps.setLong(5, Instant.now().getEpochSecond());
            if (expiresAt != null) ps.setLong(6, expiresAt.getEpochSecond());
            else ps.setNull(6, Types.BIGINT);
            ps.executeUpdate();
        }
    }

    /** Inserts an IP-ban, replacing any existing active IP-ban for that address. */
    public void addIpBan(String playerName, String ip, String reason, String staffName, Instant expiresAt)
            throws SQLException {
        removeIpBan(ip);
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bans (player_name, ip, reason, staff_name, banned_at, expires_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, playerName.toLowerCase());
            ps.setString(2, ip);
            ps.setString(3, reason);
            ps.setString(4, staffName);
            ps.setLong(5, Instant.now().getEpochSecond());
            if (expiresAt != null) ps.setLong(6, expiresAt.getEpochSecond());
            else ps.setNull(6, Types.BIGINT);
            ps.executeUpdate();
        }
    }

    public void removeBan(String playerName) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM bans WHERE player_name = ? AND ip IS NULL")) {
            ps.setString(1, playerName.toLowerCase());
            ps.executeUpdate();
        }
    }

    public void removeIpBan(String ip) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM bans WHERE ip = ?")) {
            ps.setString(1, ip);
            ps.executeUpdate();
        }
    }

    // ── Read operations ───────────────────────────────────────────────────────

    /** Returns the active name-ban for {@code playerName}, or {@code null} if none. */
    public BanEntry getActiveBan(String playerName) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM bans WHERE player_name = ? AND ip IS NULL")) {
            ps.setString(1, playerName.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                BanEntry entry = map(rs);
                if (entry.isExpired()) { removeBan(playerName); return null; }
                return entry;
            }
        }
    }

    /** Returns the active IP-ban for {@code ip}, or {@code null} if none. */
    public BanEntry getActiveIpBan(String ip) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM bans WHERE ip = ?")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                BanEntry entry = map(rs);
                if (entry.isExpired()) { removeIpBan(ip); return null; }
                return entry;
            }
        }
    }

    public boolean isBanned(String playerName) throws SQLException {
        return getActiveBan(playerName) != null;
    }

    public boolean isIpBanned(String ip) throws SQLException {
        return getActiveIpBan(ip) != null;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        pool.dispose();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private static BanEntry map(ResultSet rs) throws SQLException {
        long expiresRaw = rs.getLong("expires_at");
        Instant expiresAt = rs.wasNull() ? null : Instant.ofEpochSecond(expiresRaw);
        return new BanEntry(
                rs.getLong("id"),
                rs.getString("player_name"),
                rs.getString("ip"),
                rs.getString("reason"),
                rs.getString("staff_name"),
                Instant.ofEpochSecond(rs.getLong("banned_at")),
                expiresAt
        );
    }
}
