package com.rankauth.util;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Consumer;

/** Drives the BossBar countdown shown during registration/login and fires a callback on expiry. */
public final class AuthTimer {

    private AuthTimer() {}

    public static BossBar createBar(String title) {
        return Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
    }

    public static BukkitTask start(JavaPlugin plugin, Player player, BossBar bar, int totalSeconds,
                                    Consumer<Integer> onTick, Runnable onExpire) {
        bar.addPlayer(player);
        bar.setProgress(1.0);
        final int[] remaining = {totalSeconds};
        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                bar.removeAll();
                if (taskHolder[0] != null) taskHolder[0].cancel();
                return;
            }
            int secondsLeft = remaining[0];
            int minutes = secondsLeft / 60;
            int secs = secondsLeft % 60;
            bar.setTitle(String.format("Kayıt işlemini tamamlayın. Kalan süre: %d:%02d", minutes, secs));
            bar.setProgress(Math.max(0.0, Math.min(1.0, (double) secondsLeft / totalSeconds)));
            onTick.accept(secondsLeft);
            if (secondsLeft <= 0) {
                bar.removeAll();
                if (taskHolder[0] != null) taskHolder[0].cancel();
                onExpire.run();
                return;
            }
            remaining[0]--;
        }, 0L, 20L);
        return taskHolder[0];
    }
}
