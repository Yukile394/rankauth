package com.rankauth.auth;

import com.rankauth.config.ConfigManager;
import com.rankauth.database.DatabaseManager;
import com.rankauth.email.EmailService;
import com.rankauth.hub.HubIntegration;
import com.rankauth.model.OpIpRecord;
import com.rankauth.model.PlayerAccount;
import com.rankauth.security.PasswordUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;

public final class AuthManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final DatabaseManager db;
    private final EmailService email;
    private final SessionManager sessions;
    private final HubIntegration hub;

    /**
     * Persists across a kick/rejoin — unlike PlayerSession (which is wiped on
     * quit), this remembers a UUID is locked out even after the player has
     * been disconnected from the server for repeated wrong passwords.
     */
    private final java.util.Map<UUID, Long> loginLockouts = new java.util.concurrent.ConcurrentHashMap<>();

    public AuthManager(JavaPlugin plugin, ConfigManager config, DatabaseManager db,
                        EmailService email, SessionManager sessions, HubIntegration hub) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.email = email;
        this.sessions = sessions;
        this.hub = hub;
    }

    /** Whether this UUID is currently blocked from logging in due to repeated wrong passwords. */
    public boolean isLoginLocked(UUID uuid) {
        Long until = loginLockouts.get(uuid);
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            loginLockouts.remove(uuid);
            return false;
        }
        return true;
    }

    public long getLoginLockRemainingSeconds(UUID uuid) {
        Long until = loginLockouts.get(uuid);
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void lockLogin(UUID uuid, long seconds) {
        loginLockouts.put(uuid, System.currentTimeMillis() + (seconds * 1000L));
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
                PlayerSession session = sessions.getOrCreate(player.getUniqueId(), AuthStage.AWAITING_REGISTER);
                session.stage = AuthStage.AWAITING_REGISTER;
                session.safeLocation = safeLoc;
                placeBarrier(session, safeLoc);
                startAmbientMusic(player, session);
                startTimeout(player, session, config.registrationTimeSeconds());
                if (!player.isOp()) {
                    sendWelcomeScreen(player);
                }
                sendRegisterTitle(player);
                player.sendMessage(config.message("register-prompt"));
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
            sendLoginTitle(player, config.maxFailedLoginAttempts() - session.failedAttempts);
            player.sendMessage(config.message("login-prompt"));
        }));
    }

    /** Title shown while waiting for /register — mirrors the login title's look with Silvera branding. */
    private void sendRegisterTitle(Player player) {
        net.kyori.adventure.text.Component main = com.rankauth.util.ColorUtil.component(config.titleMainRaw());
        net.kyori.adventure.text.Component sub = com.rankauth.util.ColorUtil.component(config.registerSubtitleRaw());
        player.showTitle(net.kyori.adventure.title.Title.title(main, sub,
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(500))));
    }

    /** Title shown while waiting for /login, with the remaining-attempts count before lockout. */
    private void sendLoginTitle(Player player, int attemptsLeft) {
        net.kyori.adventure.text.Component main = com.rankauth.util.ColorUtil.component(config.titleMainRaw());
        net.kyori.adventure.text.Component sub = com.rankauth.util.ColorUtil.component(config.loginSubtitleRaw(attemptsLeft));
        player.showTitle(net.kyori.adventure.title.Title.title(main, sub,
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(500))));
    }

    /** Green "successfully logged in" title flashed right before the player is sent to hub. */
    private void sendSuccessTitle(Player player) {
        net.kyori.adventure.text.Component main = com.rankauth.util.ColorUtil.component(config.successTitleRaw());
        player.showTitle(net.kyori.adventure.title.Title.title(main, net.kyori.adventure.text.Component.empty(),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(150), java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(500))));
    }

    private void playSuccessSound(Player player) {
        org.bukkit.Sound sound;
        try {
            sound = org.bukkit.Sound.valueOf(config.successSound()
                    .replace("minecraft:", "").toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException ex) {
            sound = org.bukkit.Sound.ENTITY_PLAYER_LEVELUP;
        }
        if (player.isOnline()) {
            player.playSound(player.getLocation(), sound, 1f, 1f);
        }
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
                        player.kick(com.rankauth.util.ColorUtil.component(config.message("registration-timeout")));
                    }
                    sessions.remove(player.getUniqueId());
                });
    }

    /** /register <şifre> <email> — validates the password, hashes it, and registers immediately. No email is sent. */
    public void handleRegisterCommand(Player player, String password, String emailAddress) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage == AuthStage.AWAITING_LOGIN || session.stage == AuthStage.AUTHENTICATED) {
            player.sendMessage(config.message("need-register"));
            return;
        }
        String invalidReason = PasswordUtil.validate(password, config);
        if (invalidReason != null) {
            player.sendMessage(config.message("weak-password"));
            return;
        }

        db.emailExists(emailAddress).whenComplete((exists, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (err != null) {
                player.sendMessage(config.message("register-failed"));
                plugin.getLogger().warning("Failed to check email uniqueness for " + player.getName() + ": " + err.getMessage());
                return;
            }
            if (Boolean.TRUE.equals(exists)) {
                player.sendMessage(config.message("email-taken"));
                return;
            }
            session.pendingPasswordHash = PasswordUtil.hash(password);
            session.pendingEmail = emailAddress;
            completeRegistration(player, session);
        }));
    }

    private void completeRegistration(Player player, PlayerSession session) {
        PlayerAccount account = new PlayerAccount(
                player.getUniqueId(), player.getName(), session.pendingPasswordHash,
                session.pendingEmail, true, System.currentTimeMillis(), System.currentTimeMillis(),
                resolvePlayerIp(player), System.currentTimeMillis() + (config.sessionDurationSeconds() * 1000L));

        db.createAccount(account)
                .whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (err != null) {
                        player.sendMessage(config.message("register-failed"));
                        plugin.getLogger().warning("Failed to finalize registration for " + player.getName());
                        return;
                    }
                    finishAuth(player, session, config.message("register-success"));
                }));
    }

    public void handleLoginCommand(Player player, String password) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage != AuthStage.AWAITING_LOGIN) {
            player.sendMessage(config.message("need-login"));
            return;
        }
        if (session.lockedUntil > System.currentTimeMillis()) {
            player.sendMessage(config.message("rate-limited"));
            return;
        }

        db.getAccount(player.getUniqueId()).whenComplete((accountOpt, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (err != null || accountOpt.isEmpty()) {
                player.sendMessage(config.message("wrong-password"));
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
        int max = config.maxFailedLoginAttempts();
        if (session.failedAttempts >= max) {
            long lockSeconds = config.failedLoginLockoutSeconds();
            lockLogin(player.getUniqueId(), lockSeconds);
            sessions.cancelTasks(session);
            stopAmbientMusic(player, session);
            releaseBarrier(session);
            player.kick(com.rankauth.util.ColorUtil.component(
                    "[" + config.messagePrefix() + "] " + config.message("login-locked-kick")));
            sessions.remove(player.getUniqueId());
            return;
        }
        player.sendMessage(config.message("wrong-password"));
        sendLoginTitle(player, max - session.failedAttempts);
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
                player.sendMessage(config.message("op-check-failed"));
                return;
            }
            Optional<OpIpRecord> record = recordOpt;
            if (record.isEmpty()) {
                db.upsertOpIpRecord(player.getUniqueId(), player.getName(), currentIp);
                finalizeLogin(player, session, account);
                return;
            }
            if (!record.get().trustedIp.equals(currentIp)) {
                player.sendMessage(config.message("op-ip-locked"));
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
            player.sendMessage(successMessage);
        }
        com.rankauth.util.TabVisibility.restoreVisibility(plugin, player);
        playSuccessSound(player);
        sendSuccessTitle(player);
        // Near-instant handoff to hub — delayed by a single tick just long enough
        // for the success sound/title to actually fire before the teleport.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                hub.sendToHub(player);
            }
        }, 1L);
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
                admin.sendMessage(com.rankauth.util.ColorUtil.translate("&#FFA500İşlem sırasında bir hata oluştu."));
                return;
            }
            admin.sendMessage(com.rankauth.util.ColorUtil.translate("&#FFB6C1" + target.getName() + " için kayıtlı IP kilidi kaldırıldı."));
        }));
    }

    public SessionManager getSessions() {
        return sessions;
    }
                    }
