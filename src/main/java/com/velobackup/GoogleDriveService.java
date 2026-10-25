package com.velobackup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class GoogleDriveService {

    private static final String CLIENT_ID = "YOUR_CLIENT_ID.apps.googleusercontent.com";
    private final Path dataDirectory;
    private final Logger logger;
    private final HttpClient httpClient;
    private final Properties tokenProperties;
    private final Path tokenFile;

    public GoogleDriveService(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.tokenProperties = new Properties();
        
        if (dataDirectory != null) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException e) {
                logger.error("Failed to create data directory", e);
            }
            this.tokenFile = dataDirectory.resolve("tokens.properties");
            loadTokens();
        } else {
            this.tokenFile = null;
        }
    }

    private void loadTokens() {
        if (tokenFile != null && Files.exists(tokenFile)) {
            try (InputStream in = Files.newInputStream(tokenFile)) {
                tokenProperties.load(in);
            } catch (IOException e) {
                logger.error("Failed to load tokens.properties", e);
            }
        }
    }

    public void saveTokens(String refreshToken) {
        tokenProperties.setProperty("refresh_token", refreshToken);
        if (tokenFile != null) {
            try (OutputStream out = Files.newOutputStream(tokenFile)) {
                tokenProperties.store(out, "VeloBackup OAuth Tokens");
            } catch (IOException e) {
                logger.error("Failed to save tokens.properties", e);
            }
        }
    }

    public String getRefreshToken() {
        return tokenProperties.getProperty("refresh_token");
    }

    public CompletableFuture<JsonObject> requestDeviceCode() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) +
                        "&scope=" + URLEncoder.encode("https://www.googleapis.com/auth/drive.file", StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://oauth2.googleapis.com/device/code"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                } else {
                    logger.error("Failed to request device code: {}", response.body());
                }
            } catch (Exception e) {
                logger.error("Exception during device code request", e);
            }
            return null;
        });
    }

    public CompletableFuture<String> pollForAccessToken(String deviceCode, int interval, int expiresIn) {
        return CompletableFuture.supplyAsync(() -> {
            long endTime = System.currentTimeMillis() + (expiresIn * 1000L);
            while (System.currentTimeMillis() < endTime) {
                try {
                    Thread.sleep(interval * 1000L);

                    String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) +
                            "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8) +
                            "&grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("https://oauth2.googleapis.com/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

                    if (response.statusCode() == 200 && json.has("refresh_token")) {
                        String refreshToken = json.get("refresh_token").getAsString();
                        saveTokens(refreshToken);
                        return refreshToken;
                    } else if (json.has("error")) {
                        String error = json.get("error").getAsString();
                        if ("authorization_pending".equals(error)) {
                            continue;
                        } else if ("slow_down".equals(error)) {
                            interval += 5;
                            continue;
                        } else {
                            logger.error("OAuth error: {}", error);
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Exception during token polling", e);
                }
            }
            return null;
        });
    }

    public CompletableFuture<String> refreshAccessToken() {
        return CompletableFuture.supplyAsync(() -> {
            String refreshToken = getRefreshToken();
            if (refreshToken == null) return null;

            try {
                String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8) +
                        "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8) +
                        "&grant_type=" + URLEncoder.encode("refresh_token", StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://oauth2.googleapis.com/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return json.get("access_token").getAsString();
                }
            } catch (Exception e) {
                logger.error("Failed to refresh access token", e);
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> uploadFile(Path filePath, String fileName) {
        return refreshAccessToken().thenCompose(accessToken -> {
            if (accessToken == null) {
                logger.error("Cannot upload: No valid access token or refresh token.");
                return CompletableFuture.completedFuture(false);
            }

            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Ensure 'velobackup' folder exists
                    String folderId = getOrCreateFolder(accessToken, "velobackup");

                    String boundary = "-------314159265358979323846";
                    String delimiter = "\r\n--" + boundary + "\r\n";
                    String closeDelimiter = "\r\n--" + boundary + "--";

                    JsonObject metadata = new JsonObject();
                    metadata.addProperty("name", fileName);
                    if (folderId != null) {
                        metadata.add("parents", com.google.gson.JsonParser.parseString("["" + folderId + ""]").getAsJsonArray());
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);

                    writer.append("--").append(boundary).append("\r\n");
                    writer.append("Content-Type: application/json; charset=UTF-8\r\n\r\n");
                    writer.append(metadata.toString()).append("\r\n");

                    writer.append("--").append(boundary).append("\r\n");
                    writer.append("Content-Type: application/zip\r\n\r\n");
                    writer.flush();

                    baos.write(Files.readAllBytes(filePath));

                    writer.append("\r\n--").append(boundary).append("--");
                    writer.flush();

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"))
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Content-Type", "multipart/related; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    return response.statusCode() == 200 || response.statusCode() == 201;
                } catch (Exception e) {
                    logger.error("Failed to upload backup to Google Drive", e);
                    return false;
                }
            });
        });
    }

    private String getOrCreateFolder(String accessToken, String folderName) {
        try {
            String query = URLEncoder.encode("name = '" + folderName + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false", StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/drive/v3/files?q=" + query))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                var files = json.getAsJsonArray("files");
                if (files != null && files.size() > 0) {
                    return files.get(0).getAsJsonObject().get("id").getAsString();
                }
            }

            // Create folder if not exists
            JsonObject metadata = new JsonObject();
            metadata.addProperty("name", folderName);
            metadata.addProperty("mimeType", "application/vnd.google-apps.folder");

            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/drive/v3/files"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(metadata.toString()))
                    .build();

            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
            if (createResp.statusCode() == 200 || createResp.statusCode() == 201) {
                JsonObject json = JsonParser.parseString(createResp.body()).getAsJsonObject();
                return json.get("id").getAsString();
            }
        } catch (Exception e) {
            logger.error("Error finding or creating folder", e);
        }
        return null;
    }
}
