package com.rankauth.database;

import com.rankauth.config.ConfigManager;
import com.rankauth.model.OpIpRecord;
import com.rankauth.model.PlayerAccount;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles all persistence. Schema is deliberately MySQL/MariaDB-portable
 * (plain types, no SQLite-only syntax) so a future connector only needs a new
 * HikariConfig branch, not a schema rewrite.
 */
public final class DatabaseManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private HikariDataSource dataSource;
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            r -> new Thread(r, "RankAuth-DB"));

    public DatabaseManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() throws SQLException {
        HikariConfig hikariConfig = new HikariConfig();

        if ("mysql".equalsIgnoreCase(config.databaseType()) || "mariadb".equalsIgnoreCase(config.databaseType())) {
            String jdbcUrl = "jdbc:mysql://" + config.mysqlHost() + ":" + config.mysqlPort() + "/"
                    + config.mysqlDatabase() + "?useSSL=" + config.mysqlUseSsl() + "&autoReconnect=true";
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(config.mysqlUsername());
            hikariConfig.setPassword(config.mysqlPassword());
            hikariConfig.setMaximumPoolSize(6);
        } else {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File dbFile = new File(plugin.getDataFolder(), "rankauth.db");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            hikariConfig.setMaximumPoolSize(1); // SQLite is single-writer; avoid lock contention.
        }

        hikariConfig.setPoolName("RankAuth-Hikari");
        this.dataSource = new HikariDataSource(hikariConfig);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS players (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "username VARCHAR(16) NOT NULL," +
                    "password_hash VARCHAR(100) NOT NULL," +
                    "email VARCHAR(255)," +
                    "email_verified INTEGER NOT NULL DEFAULT 0," +
                    "registered_at BIGINT NOT NULL," +
                    "last_login BIGINT NOT NULL," +
                    "last_ip VARCHAR(64)," +
                    "session_expires_at BIGINT NOT NULL DEFAULT 0" +
                    ")");
            migrateColumnIfMissing(st, "players", "last_ip", "VARCHAR(64)");
            migrateColumnIfMissing(st, "players", "session_expires_at", "BIGINT NOT NULL DEFAULT 0");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS verification_codes (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "code_hash VARCHAR(64) NOT NULL," +
                    "pending_email VARCHAR(255)," +
                    "expires_at BIGINT NOT NULL" +
                    ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS op_ip_security (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "username VARCHAR(16) NOT NULL," +
                    "trusted_ip VARCHAR(64) NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
        }

        plugin.getLogger().info("Database initialized (" + config.databaseType() + ").");
    }

    /** Best-effort ALTER TABLE for upgrades from older RankAuth schemas — ignores "column exists" errors. */
    private void migrateColumnIfMissing(Statement st, String table, String column, String definition) {
        try {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // Column already present — expected on any non-first startup.
        }
    }

    public void shutdown() {
        executor.shutdown();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                future.complete(supplier.get(conn));
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get(Connection conn) throws SQLException;
    }

    // ---------------- players ----------------

    public CompletableFuture<Optional<PlayerAccount>> getAccount(UUID uuid) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(mapAccount(rs));
                }
            }
        });
    }

    private PlayerAccount mapAccount(ResultSet rs) throws SQLException {
        return new PlayerAccount(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("email"),
                rs.getInt("email_verified") == 1,
                rs.getLong("registered_at"),
                rs.getLong("last_login"),
                rs.getString("last_ip"),
                rs.getLong("session_expires_at")
        );
    }

    public CompletableFuture<Void> createAccount(PlayerAccount account) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (uuid, username, password_hash, email, email_verified, registered_at, last_login, last_ip, session_expires_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, account.uuid.toString());
                ps.setString(2, account.username);
                ps.setString(3, account.passwordHash);
                ps.setString(4, account.email);
                ps.setInt(5, account.emailVerified ? 1 : 0);
                ps.setLong(6, account.registeredAt);
                ps.setLong(7, account.lastLogin);
                ps.setString(8, account.lastIp);
                ps.setLong(9, account.sessionExpiresAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> updateLastLogin(UUID uuid, long timestamp) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET last_login = ? WHERE uuid = ?")) {
                ps.setLong(1, timestamp);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> updatePassword(UUID uuid, String newHash) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET password_hash = ? WHERE uuid = ?")) {
                ps.setString(1, newHash);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Refreshes the short-lived trusted-session fields after a successful password login. */
    public CompletableFuture<Void> updateSession(UUID uuid, String ip, long sessionExpiresAt) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET last_ip = ?, session_expires_at = ? WHERE uuid = ?")) {
                ps.setString(1, ip);
                ps.setLong(2, sessionExpiresAt);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------- verification codes ----------------

    public CompletableFuture<Void> storeVerificationCode(UUID uuid, String codeHash, String pendingEmail, long expiresAt) {
        return supplyAsync(conn -> {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM verification_codes WHERE uuid = ?")) {
                del.setString(1, uuid.toString());
                del.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO verification_codes (uuid, code_hash, pending_email, expires_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, codeHash);
                ps.setString(3, pendingEmail);
                ps.setLong(4, expiresAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static final class VerificationEntry {
        public final String codeHash;
        public final String pendingEmail;
        public final long expiresAt;
        public VerificationEntry(String codeHash, String pendingEmail, long expiresAt) {
            this.codeHash = codeHash;
            this.pendingEmail = pendingEmail;
            this.expiresAt = expiresAt;
        }
    }

    public CompletableFuture<Optional<VerificationEntry>> getVerificationEntry(UUID uuid) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM verification_codes WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new VerificationEntry(
                            rs.getString("code_hash"),
                            rs.getString("pending_email"),
                            rs.getLong("expires_at")));
                }
            }
        });
    }

    public CompletableFuture<Void> deleteVerificationEntry(UUID uuid) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_codes WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> markEmailVerified(UUID uuid, String email) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET email = ?, email_verified = 1 WHERE uuid = ?")) {
                ps.setString(1, email);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------- OP IP security ----------------

    public CompletableFuture<Optional<OpIpRecord>> getOpIpRecord(UUID uuid) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM op_ip_security WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new OpIpRecord(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("username"),
                            rs.getString("trusted_ip"),
                            rs.getLong("created_at"),
                            rs.getLong("updated_at")));
                }
            }
        });
    }

    public CompletableFuture<Void> upsertOpIpRecord(UUID uuid, String username, String ip) {
        return supplyAsync(conn -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid FROM op_ip_security WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        try (PreparedStatement up = conn.prepareStatement(
                                "UPDATE op_ip_security SET trusted_ip = ?, updated_at = ? WHERE uuid = ?")) {
                            up.setString(1, ip);
                            up.setLong(2, now);
                            up.setString(3, uuid.toString());
                            up.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement in = conn.prepareStatement(
                                "INSERT INTO op_ip_security (uuid, username, trusted_ip, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                            in.setString(1, uuid.toString());
                            in.setString(2, username);
                            in.setString(3, ip);
                            in.setLong(4, now);
                            in.setLong(5, now);
                            in.executeUpdate();
                        }
                    }
                }
            }
            return null;
        });
    }

    public CompletableFuture<Void> removeOpIpRecord(UUID uuid) {
        return supplyAsync(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM op_ip_security WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Runs an async task on the DB executor and hands the result back on the main thread. */
    public <T> void runAsyncThenSync(JavaPlugin plugin, CompletableFuture<T> future, java.util.function.Consumer<T> onMain,
                                      java.util.function.Consumer<Throwable> onError) {
        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> onError.accept(throwable));
            } else {
                plugin.getServer().getScheduler().runTask(plugin, () -> onMain.accept(result));
            }
        });
    }
}
