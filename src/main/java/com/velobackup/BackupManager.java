package com.velobackup;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final GoogleDriveService driveService;

    public BackupManager(ProxyServer server, Logger logger, Path dataDirectory, GoogleDriveService driveService) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.driveService = driveService;
    }

    public void createBackup(Runnable callback) {
        server.getScheduler().buildTask(null, () -> {
            try {
                String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                Path backupFile = dataDirectory.resolve("backup_" + timeStamp + ".zip");
                
                Path serverDir = dataDirectory.getParent(); // plugins parent or proxy root
                if (serverDir == null) {
                    serverDir = Paths.get(".");
                }

                logger.info("Creating backup archive...");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupFile))) {
                    // Add velocity.toml if exists
                    Path toml = serverDir.resolve("velocity.toml");
                    if (Files.exists(toml)) {
                        addToZip(toml, serverDir, zos);
                    }
                    // Add plugins folder if exists
                    Path pluginsDir = serverDir.resolve("plugins");
                    if (Files.exists(pluginsDir)) {
                        Files.walk(pluginsDir)
                            .filter(path -> !path.equals(dataDirectory)) // Don't backup plugin's own tmp state recursively if inside plugins
                            .forEach(path -> {
                                try {
                                    if (!Files.isDirectory(path)) {
                                        addToZip(path, serverDir, zos);
                                    }
                                } catch (IOException ignored) {}
                            });
                    }
                }

                logger.info("Backup archive created successfully. Uploading to Google Drive...");
                driveService.uploadFile(backupFile, backupFile.getFileName().toString()).thenAccept(success -> {
                    if (success) {
                        logger.info("Backup uploaded successfully to Google Drive!");
                        try {
                            Files.deleteIfExists(backupFile);
                        } catch (IOException ignored) {}
                    } else {
                        logger.error("Failed to upload backup to Google Drive.");
                    }
                    if (callback != null) callback.run();
                });

            } catch (Exception e) {
                logger.error("Failed to create backup", e);
                if (callback != null) callback.run();
            }
        }).schedule();
    }

    private void addToZip(Path file, Path root, ZipOutputStream zos) throws IOException {
        ZipEntry zipEntry = new ZipEntry(root.relativize(file).toString());
        zos.putNextEntry(zipEntry);
        Files.copy(file, zos);
        zos.closeEntry();
    }
}
