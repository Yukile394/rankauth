package com.rankauth;

import com.rankauth.auth.AuthManager;
import com.rankauth.auth.SessionManager;
import com.rankauth.command.LoginCommand;
import com.rankauth.command.OpSistemiKaldirCommand;
import com.rankauth.command.RankAuthCommand;
import com.rankauth.command.RegisterCommand;
import com.rankauth.config.ConfigManager;
import com.rankauth.database.DatabaseManager;
import com.rankauth.email.EmailService;
import com.rankauth.hub.HubIntegration;
import com.rankauth.listener.ChatListener;
import com.rankauth.listener.PlayerConnectionListener;
import com.rankauth.listener.RestrictionListener;
import com.rankauth.security.PasswordUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class RankAuthPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EmailService emailService;
    private SessionManager sessionManager;
    private AuthManager authManager;
    private HubIntegration hubIntegration;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this, configManager);
        try {
            databaseManager.initialize();
        } catch (Exception e) {
            getLogger().severe("Database could not be initialized, disabling RankAuth: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.emailService = new EmailService(this, configManager);
        this.sessionManager = new SessionManager();
        this.hubIntegration = new HubIntegration(this, configManager);
        this.authManager = new AuthManager(this, configManager, databaseManager, emailService, sessionManager, hubIntegration);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this, authManager, sessionManager, configManager), this);
        getServer().getPluginManager().registerEvents(new RestrictionListener(sessionManager, configManager), this);
        getServer().getPluginManager().registerEvents(new ChatListener(authManager, sessionManager), this);

        getCommand("register").setExecutor(new RegisterCommand(authManager, configManager));
        getCommand("login").setExecutor(new LoginCommand(authManager, configManager));
        getCommand("opsistemikaldir").setExecutor(new OpSistemiKaldirCommand(authManager));
        getCommand("rankauth").setExecutor(new RankAuthCommand(this, configManager));

        getLogger().info("RankAuth enabled.");
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.cancelAll();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        getLogger().info("RankAuth disabled.");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
