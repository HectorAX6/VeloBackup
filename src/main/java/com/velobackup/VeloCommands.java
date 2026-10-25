package com.velobackup;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;

public class VeloCommands {

    public static BrigadierCommand create(GoogleDriveService driveService, BackupManager backupManager) {
        // Handled via plugin registration directly in VeloBackupPlugin or here
        return null;
    }

    // We can implement Command directly for simple command registration
    public static com.velocitypowered.api.command.Command createLinkCommand(GoogleDriveService driveService) {
        return invocation -> {
            CommandSource source = invocation.source();
            source.sendMessage(net.kyori.adventure.text.Component.text("Requesting Google Drive device link code..."));

            driveService.requestDeviceCode().thenAccept(json -> {
                if (json != null) {
                    String userCode = json.get("user_code").getAsString();
                    String verificationUrl = json.get("verification_url").getAsString();
                    String deviceCode = json.get("device_code").getAsString();
                    int interval = json.get("interval").getAsInt();
                    int expiresIn = json.get("expires_in").getAsInt();

                    source.sendMessage(net.kyori.adventure.text.Component.text("========================================"));
                    source.sendMessage(net.kyori.adventure.text.Component.text("Please open: " + verificationUrl));
                    source.sendMessage(net.kyori.adventure.text.Component.text("Enter Code: " + userCode));
                    source.sendMessage(net.kyori.adventure.text.Component.text("========================================"));

                    driveService.pollForAccessToken(deviceCode, interval, expiresIn).thenAccept(token -> {
                        if (token != null) {
                            source.sendMessage(net.kyori.adventure.text.Component.text("§aGoogle Drive successfully linked!"));
                        } else {
                            source.sendMessage(net.kyori.adventure.text.Component.text("§cGoogle Drive linking failed or timed out."));
                        }
                    });
                } else {
                    source.sendMessage(net.kyori.adventure.text.Component.text("§cFailed to connect to Google OAuth service."));
                }
            });
        };
    }

    public static com.velocitypowered.api.command.Command createBackupCommand(BackupManager backupManager) {
        return invocation -> {
            CommandSource source = invocation.source();
            source.sendMessage(net.kyori.adventure.text.Component.text("Triggering manual backup upload..."));
            backupManager.createBackup(() -> source.sendMessage(net.kyori.adventure.text.Component.text("Backup process completed! Check console for details.")));
        };
    }
}
