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
                PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_PASSWORD);
                session.stage = AuthStage.AWAITING_PASSWORD;
                session.safeLocation = safeLoc;
                placeBarrier(session, safeLoc);
                startAmbientMusic(player, session);
                startTimeout(player, session, config.registrationTimeSeconds());
                if (!player.isOp()) {
                    sendWelcomeScreen(player);
                }
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
                PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_LOGIN);
                session.safeLocation = safeLoc;
                if (config.opSecurityEnabled() && player.isOp()) {
                    enforceOpIpLock(player, session, account);
                } else {
                    finalizeLogin(player, session, account);
                }
                return;
            }

            PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_LOGIN);
            session.stage = AuthStage.AWAITING_LOGIN;
            session.safeLocation = safeLoc;
            placeBarrier(session, safeLoc);
            startAmbientMusic(player, session);
            startTimeout(player, session, config.loginTimeSeconds());
            player.sendMessage(ChatColor.YELLOW + "Giriş yapmak için: " + ChatColor.WHITE + "/login <şifre>");
        }));
    }

    private Location buildSafeLocation(Player player) {
        World world = player.getWorld();
        double safeY = (world.getEnvironment() == World.Environment.NETHER) ? 120 : 320;
        safeY = Math.min(safeY, world.getMaxHeight() - 5);
        return new Location(world, player.getLocation().getX(), safeY, player.getLocation().getZ());
    }

    /** Sends the colored first-join / auth instructions screen. */
    private void sendWelcomeScreen(Player player) {
        for (String line : config.welcomeLines()) {
            player.sendMessage(line);
        }
    }

    /**
     * Places an invisible barrier block beneath the player's safe (in-air) auth
     * location so they cannot fall while restricted, and remembers whatever was
     * there before so it can be restored once authentication finishes.
     */
    private void placeBarrier(PlayerSession session, Location safeLoc) {
        org.bukkit.block.Block block = safeLoc.clone().subtract(0, 1, 0).getBlock();
        session.barrierLocation = block.getLocation();
        session.barrierPreviousType = block.getType();
        block.setType(org.bukkit.Material.BARRIER, false);
    }

    /** Restores the block beneath the player once they're authenticated. */
    private void releaseBarrier(PlayerSession session) {
        if (session.barrierLocation != null) {
            org.bukkit.Material previous = session.barrierPreviousType != null
                    ? session.barrierPreviousType : org.bukkit.Material.AIR;
            session.barrierLocation.getBlock().setType(previous, false);
            session.barrierLocation = null;
            session.barrierPreviousType = null;
        }
    }

    /** Loops a calming sound for the player until they finish registering/logging in. */
    private void startAmbientMusic(Player player, PlayerSession session) {
        if (!config.musicEnabled()) return;
        if (session.musicTask != null) return;
        org.bukkit.Sound sound;
        try {
            sound = org.bukkit.Sound.valueOf(config.musicSound()
                    .replace("minecraft:", "").toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException ex) {
            sound = org.bukkit.Sound.MUSIC_DISC_CAT;
        }
        final org.bukkit.Sound finalSound = sound;
        long periodTicks = Math.max(20L, config.musicLoopSeconds() * 20L);
        Runnable play = () -> {
            if (player.isOnline()) {
                player.playSound(player.getLocation(), finalSound, org.bukkit.SoundCategory.MUSIC,
                        config.musicVolume(), config.musicPitch());
            }
        };
        play.run();
        session.musicTask = Bukkit.getScheduler().runTaskTimer(plugin, play, periodTicks, periodTicks);
    }

    private void stopAmbientMusic(Player player, PlayerSession session) {
        if (session.musicTask != null) {
            session.musicTask.cancel();
            session.musicTask = null;
        }
        if (player.isOnline()) {
            player.stopSound(org.bukkit.SoundCategory.MUSIC);
        }
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
            default -> {}
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
        stopAmbientMusic(player, session);
        releaseBarrier(session);
        session.stage = AuthStage.AUTHENTICATED;
        session.failedAttempts = 0;
        if (successMessage != null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', successMessage));
        }
        com.rankauth.util.TabVisibility.restoreVisibility(plugin, player);
        hub.sendToHub(player);
    }

    /** Cleans up barrier blocks/music left over if a restricted player disconnects mid-auth. */
    public void handleQuit(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session != null && session.isRestricted()) {
            releaseBarrier(session);
        }
    }

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
                               
