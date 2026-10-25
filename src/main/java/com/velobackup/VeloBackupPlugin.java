package com.velobackup;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
    id = "velobackup",
    name = "VeloBackup",
    version = "1.0.0",
    description = "Automated Google Drive backups for Velocity",
    authors = {"Developer"}
)
public class VeloBackupPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private BackupManager backupManager;
    private GoogleDriveService googleDriveService;

    @Inject
    public VeloBackupPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Initializing VeloBackup...");
        this.googleDriveService = new GoogleDriveService(dataDirectory, logger);
        this.backupManager = new BackupManager(server, logger, dataDirectory, googleDriveService);
        
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("link-drive").build(),
            new VeloCommands(googleDriveService, backupManager)
        );
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("backup-now").build(),
            new VeloCommands(googleDriveService, backupManager)
        );
        logger.info("VeloBackup initialized successfully!");
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public GoogleDriveService getGoogleDriveService() {
        return googleDriveService;
    }
}
