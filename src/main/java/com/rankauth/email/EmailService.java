package com.rankauth.email;

import com.rankauth.config.ConfigManager;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EmailService {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            r -> new Thread(r, "RankAuth-SMTP"));

    public EmailService(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Named (not anonymous) Authenticator — avoids shadowJar issues with synthetic inner classes. */
    private static final class SmtpAuthenticator extends Authenticator {
        private final String username;
        private final String password;

        SmtpAuthenticator(String username, String password) {
            this.username = username;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(username, password);
        }
    }

    /** Sends the verification code email asynchronously. Never logs the code or the SMTP password. */
    public CompletableFuture<Void> sendVerificationCode(String toAddress, String code) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.host", config.smtpHost());
                props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", String.valueOf(config.smtpStartTls()));

                String smtpUser = config.smtpUsername();
                String smtpPass = config.smtpPassword();

                Session session = Session.getInstance(props, new SmtpAuthenticator(smtpUser, smtpPass));

                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(config.smtpFromAddress(), config.smtpFromName()));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
                message.setSubject(config.smtpSubject());
                message.setText("Doğrulama kodunuz: " + code + "\n\nBu kod " +
                        (config.verificationExpirationSeconds() / 60) + " dakika içinde geçerliliğini yitirecektir.\n" +
                        "Bu isteği siz yapmadıysanız bu e-postayı yok sayabilirsiniz.");

                Transport.send(message);
                future.complete(null);
            } catch (Exception e) {
                // Never log recipient address details beyond what's needed, and never log the code.
                plugin.getLogger().warning("Failed to send verification email: " + e.getClass().getSimpleName());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
