package com.rankauth.auth;

import com.rankauth.config.ConfigManager;
import com.rankauth.database.DatabaseManager;
import com.rankauth.email.EmailService;
import com.rankauth.hub.HubIntegration;
import com.rankauth.model.OpIpRecord;
import com.rankauth.model.PlayerAccount;
import com.rankauth.security.CodeGenerator;
import com.rankauth.security.PasswordUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuthManager {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final DatabaseManager db;
    private final EmailService email;
    private final SessionManager sessions;
    private final HubIntegration hub;

    public AuthManager(JavaPlugin plugin, ConfigManager config, DatabaseManager db,
                        EmailService email, SessionManager sessions, HubIntegration hub) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.email = email;
        this.sessions = sessions;
        this.hub = hub;
    }

    // ------------------------------------------------------------------
    // Join handling
    // ------------------------------------------------------------------

    /** Called on join once the player's account lookup returns. Decides register vs login flow. */
    public void beginAuthFlow(Player player) {
        db.getAccount(player.getUniqueId()).whenComplete((accountOpt, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (err != null) {
                plugin.getLogger().warning("Failed to load account for " + player.getName() + ": " + err.getMessage());
                return;
            }
            Location safeLoc = buildSafeLocation(player);
            player.teleport(safeLoc);
            com.rankauth.util.TabVisibility.hideFromEveryone(plugin, player);

            if (accountOpt.isEmpty()) {
                // Decision order step 2: no account → registration flow.
                PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_PASSWORD);
                session.stage = AuthStage.AWAITING_PASSWORD;
                session.safeLocation = safeLoc;
                startTimeout(player, session, config.registrationTimeSeconds());
                player.sendMessage(ChatColor.YELLOW + "Şifre belirle:");
                return;
            }

            PlayerAccount account = accountOpt.get();
            String currentIp = resolvePlayerIp(player);
            boolean sessionValid = config.sessionEnabled()
                    && account.sessionExpiresAt > System.currentTimeMillis()
                    && account.lastIp != null
                    && account.lastIp.equals(currentIp);

            if (sessionValid) {
                // Decision order steps 3+4: valid trusted session on the same IP.
                PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_LOGIN);
                session.safeLocation = safeLoc;
                if (config.opSecurityEnabled() && player.isOp()) {
                    // Decision order step 5: OP IP lock still applies independently of session trust.
                    enforceOpIpLock(player, session, account);
                } else {
                    finalizeLogin(player, session, account);
                }
                return;
            }

            // No valid session (or different IP, or session expired) → normal /login prompt.
            PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_LOGIN);
            session.stage = AuthStage.AWAITING_LOGIN;
            session.safeLocation = safeLoc;
            startTimeout(player, session, config.loginTimeSeconds());
            player.sendMessage(ChatColor.YELLOW + "Giriş yapmak için: " + ChatColor.WHITE + "/login <şifre>");
        }));
    }

    private Location buildSafeLocation(Player player) {
        World world = player.getWorld();
        // Suspend the player well above any terrain at a fixed platform-independent point
        // so they visually float without ever reaching fall damage, without altering the world.
        return new Location(world, player.getLocation().getX(), 320, player.getLocation().getZ());
    }

    private void startTimeout(Player player, PlayerSession session, int seconds) {
        sessions.cancelTasks(session);
        BossBar bar = com.rankauth.util.AuthTimer.createBar("Kalan süre");
        session.bossBar = bar;
        session.secondsRemaining = seconds;
        session.timeoutTask = com.rankauth.util.AuthTimer.start(plugin, player, bar, seconds,
                remaining -> session.secondsRemaining = remaining,
                () -> {
                    if (player.isOnline()) {
                        player.kick(net.kyori.adventure.text.Component.text(config.message("registration-timeout")));
                    }
                    sessions.remove(player.getUniqueId());
                });
    }

    // ------------------------------------------------------------------
    // Chat-driven registration flow
    // ------------------------------------------------------------------

    /** Returns true if the chat message was consumed by the auth flow (i.e. should not reach normal chat). */
    public boolean handleChatInput(Player player, String message) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage == AuthStage.AUTHENTICATED) {
            return false;
        }

        switch (session.stage) {
            case AWAITING_PASSWORD -> handlePasswordEntry(player, session, message);
            case AWAITING_PASSWORD_CONFIRM -> handlePasswordConfirm(player, session, message);
            case AWAITING_EMAIL -> handleEmailEntry(player, session, message);
            case AWAITING_CODE -> handleCodeEntry(player, session, message);
            case AWAITING_LOGIN -> player.sendMessage(ChatColor.YELLOW + "Giriş yapmak için: /login <şifre>");
            default -> { /* AUTHENTICATED already excluded above */ }
        }
        return true;
    }

    private void handlePasswordEntry(Player player, PlayerSession session, String password) {
        String invalidReason = PasswordUtil.validate(password, config);
        if (invalidReason != null) {
            player.sendMessage(ChatColor.RED + config.message("weak-password"));
            return;
        }
        session.pendingPasswordHash = PasswordUtil.hash(password);
        session.stage = AuthStage.AWAITING_PASSWORD_CONFIRM;
        player.sendMessage(ChatColor.YELLOW + "Şifrenizi tekrar girin:");
    }

    private void handlePasswordConfirm(Player player, PlayerSession session, String password) {
        if (!PasswordUtil.matches(password, session.pendingPasswordHash)) {
            player.sendMessage(ChatColor.RED + config.message("password-mismatch"));
            session.pendingPasswordHash = null;
            session.stage = AuthStage.AWAITING_PASSWORD;
            player.sendMessage(ChatColor.YELLOW + "Şifre belirle:");
            return;
        }
        session.stage = AuthStage.AWAITING_EMAIL;
        player.sendMessage(ChatColor.YELLOW + "Güvenlik için e-posta adresinizi girin:");
    }

    private void handleEmailEntry(Player player, PlayerSession session, String emailAddress) {
        if (!EMAIL_PATTERN.matcher(emailAddress).matches()) {
            player.sendMessage(ChatColor.RED + config.message("invalid-email"));
            return;
        }
        session.pendingEmail = emailAddress;
        String code = CodeGenerator.generateNumericCode(config.verificationCodeLength());
        String codeHash = CodeGenerator.hashCode(code);
        long expiresAt = System.currentTimeMillis() + (config.verificationExpirationSeconds() * 1000L);

        db.storeVerificationCode(player.getUniqueId(), codeHash, emailAddress, expiresAt)
                .thenCompose(v -> email.sendVerificationCode(emailAddress, code))
                .whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (err != null) {
                        player.sendMessage(ChatColor.RED + "Doğrulama e-postası gönderilemedi. Lütfen tekrar deneyin.");
                        session.stage = AuthStage.AWAITING_EMAIL;
                        return;
                    }
                    session.stage = AuthStage.AWAITING_CODE;
                    player.sendMessage(ChatColor.GREEN + config.message("verification-sent"));
                    player.sendMessage(ChatColor.YELLOW + "Kodu chat üzerinden girin:");
                }));
    }

    private void handleCodeEntry(Player player, PlayerSession session, String code) {
        db.getVerificationEntry(player.getUniqueId()).whenComplete((entryOpt, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (err != null || entryOpt.isEmpty()) {
                player.sendMessage(ChatColor.RED + config.message("wrong-code"));
                return;
            }
            DatabaseManager.VerificationEntry entry = entryOpt.get();
            if (System.currentTimeMillis() > entry.expiresAt) {
                player.sendMessage(ChatColor.RED + "Doğrulama kodunun süresi doldu. Lütfen tekrar deneyin.");
                session.stage = AuthStage.AWAITING_EMAIL;
                return;
            }
            if (!CodeGenerator.matches(code, entry.codeHash)) {
                player.sendMessage(ChatColor.RED + config.message("wrong-code"));
                return;
            }
            completeRegistration(player, session);
        }));
    }

    private void completeRegistration(Player player, PlayerSession session) {
        PlayerAccount account = new PlayerAccount(
                player.getUniqueId(), player.getName(), session.pendingPasswordHash,
                session.pendingEmail, true, System.currentTimeMillis(), System.currentTimeMillis(),
                resolvePlayerIp(player), System.currentTimeMillis() + (config.sessionDurationSeconds() * 1000L));

        db.createAccount(account)
                .thenCompose(v -> db.deleteVerificationEntry(player.getUniqueId()))
                .whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (err != null) {
                        player.sendMessage(ChatColor.RED + "Kayıt tamamlanamadı, lütfen tekrar deneyin.");
                        plugin.getLogger().warning("Failed to finalize registration for " + player.getName());
                        return;
                    }
                    finishAuth(player, session, "&aKayıt başarıyla tamamlandı!");
                }));
    }

    // ------------------------------------------------------------------
    // /register command (alternate entry point per spec: /register <pw> <pw>)
    // ------------------------------------------------------------------

    public void handleRegisterCommand(Player player, String password1, String password2) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage == AuthStage.AWAITING_LOGIN || session.stage == AuthStage.AUTHENTICATED) {
            player.sendMessage(ChatColor.RED + config.message("need-register"));
            return;
        }
        if (!password1.equals(password2)) {
            player.sendMessage(ChatColor.RED + config.message("password-mismatch"));
            return;
        }
        String invalidReason = PasswordUtil.validate(password1, config);
        if (invalidReason != null) {
            player.sendMessage(ChatColor.RED + config.message("weak-password"));
            return;
        }
        session.pendingPasswordHash = PasswordUtil.hash(password1);
        session.stage = AuthStage.AWAITING_EMAIL;
        player.sendMessage(ChatColor.YELLOW + "Güvenlik için e-posta adresinizi girin:");
    }

    // ------------------------------------------------------------------
    // /login command
    // ------------------------------------------------------------------

    public void handleLoginCommand(Player player, String password) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage != AuthStage.AWAITING_LOGIN) {
            player.sendMessage(ChatColor.RED + config.message("need-login"));
            return;
        }
        if (session.lockedUntil > System.currentTimeMillis()) {
            player.sendMessage(ChatColor.RED + config.message("rate-limited"));
            return;
        }

        db.getAccount(player.getUniqueId()).whenComplete((accountOpt, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (err != null || accountOpt.isEmpty()) {
                player.sendMessage(ChatColor.RED + config.message("wrong-password"));
                return;
            }
            PlayerAccount account = accountOpt.get();
            if (!PasswordUtil.matches(password, account.passwordHash)) {
                registerFailedAttempt(player, session);
                return;
            }

            // Password correct — now enforce OP IP lock if applicable.
            if (config.opSecurityEnabled() && player.isOp()) {
                enforceOpIpLock(player, session, account);
            } else {
                finalizeLogin(player, session, account);
            }
        }));
    }

    private void registerFailedAttempt(Player player, PlayerSession session) {
        session.failedAttempts++;
        if (session.failedAttempts >= config.maxFailedLoginAttempts()) {
            session.lockedUntil = System.currentTimeMillis() + (config.failedLoginLockoutSeconds() * 1000L);
            session.failedAttempts = 0;
            player.sendMessage(ChatColor.RED + config.message("rate-limited"));
        } else {
            player.sendMessage(ChatColor.RED + config.message("wrong-password"));
        }
    }

    private String resolvePlayerIp(Player player) {
        // If behind a proxy with player-info-forwarding configured, Paper's
        // player.getAddress() already reflects the real client IP — no extra
        // header parsing is done here, since untrusted forwarding headers
        // must never be trusted directly.
        if (player.getAddress() == null) return "unknown";
        return player.getAddress().getAddress().getHostAddress();
    }

    private void enforceOpIpLock(Player player, PlayerSession session, PlayerAccount account) {
        String currentIp = resolvePlayerIp(player);
        db.getOpIpRecord(player.getUniqueId()).whenComplete((recordOpt, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (err != null) {
                plugin.getLogger().warning("OP IP lookup failed for " + player.getName() + ", denying login as a safe default.");
                player.sendMessage(ChatColor.RED + "Güvenlik kontrolü başarısız oldu, tekrar deneyin.");
                return;
            }
            Optional<OpIpRecord> record = recordOpt;
            if (record.isEmpty()) {
                // First successful OP login after install / IP removal: trust this IP going forward.
                db.upsertOpIpRecord(player.getUniqueId(), player.getName(), currentIp);
                finalizeLogin(player, session, account);
                return;
            }
            if (!record.get().trustedIp.equals(currentIp)) {
                player.sendMessage(ChatColor.RED + config.message("op-ip-locked"));
                return;
            }
            finalizeLogin(player, session, account);
        }));
    }

    private void finalizeLogin(Player player, PlayerSession session, PlayerAccount account) {
        long expiresAt = System.currentTimeMillis() + (config.sessionDurationSeconds() * 1000L);
        db.updateLastLogin(player.getUniqueId(), System.currentTimeMillis());
        db.updateSession(player.getUniqueId(), resolvePlayerIp(player), expiresAt);
        finishAuth(player, session, null);
    }

    private void finishAuth(Player player, PlayerSession session, String successMessage) {
        sessions.cancelTasks(session);
        session.stage = AuthStage.AUTHENTICATED;
        session.failedAttempts = 0;
        if (successMessage != null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', successMessage));
        }
        com.rankauth.util.TabVisibility.restoreVisibility(plugin, player);
        hub.sendToHub(player);
    }

    // ------------------------------------------------------------------
    // /opsistemikaldir <player>
    // ------------------------------------------------------------------

    public void removeOpIpLock(org.bukkit.command.CommandSender admin, Player target) {
        db.removeOpIpRecord(target.getUniqueId()).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                admin.sendMessage(ChatColor.RED + "İşlem sırasında bir hata oluştu.");
                return;
            }
            admin.sendMessage(ChatColor.GREEN + target.getName() + " için kayıtlı IP kilidi kaldırıldı.");
        }));
    }

    public SessionManager getSessions() {
        return sessions;
    }
}
