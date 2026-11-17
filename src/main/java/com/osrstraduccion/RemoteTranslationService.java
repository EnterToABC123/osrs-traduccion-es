package com.osrstraduccion;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Descarga los diccionarios publicados en GitHub y los guarda en la carpeta de datos del plugin
 * (siempre a través de {@link Filepath}).
 */
final class RemoteTranslationService {
    static final String DEFAULT_MANIFEST_URL =
        "https://api.github.com/repos/EnterToABC123/"
            + "osrs-translate-translations/contents/manifest.json?ref=main";

    /** Únicos servidores a los que el plugin se conecta (también tras una redirección). */
    static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "github.com",
        "api.github.com",
        "raw.githubusercontent.com")));

    static final List<String> REQUIRED_FILES = Collections.unmodifiableList(Arrays.asList(
        "translations.json",
        "translations_skills.json",
        "translations_quests.json",
        "translations_items.json",
        "translations_menu.json",
        "translations_overhead.json",
        "translations_game_message.json",
        "translations_welcome.json",
        "translations_settings.json"
    ));

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    private static final long MAX_TRANSLATION_BYTES = 64L * 1024L * 1024L;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final String STAGING_PREFIX = "staging-";
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final Filepath cacheRoot;

    RemoteTranslationService(OkHttpClient httpClient, Gson gson, Filepath cacheRoot) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.cacheRoot = cacheRoot;
    }

    Filepath getActiveDirectory(String language) {
        String safeLanguage;
        try {
            safeLanguage = requireSafeName(language, "idioma");
        } catch (IOException e) {
            return null;
        }

        Filepath languageRoot = cacheRoot.joinSegment(safeLanguage);
        Filepath activeFile = languageRoot.joinSegment("active.txt");
        if (!activeFile.isFile()) {
            return null;
        }

        try {
            String releaseId = readString(activeFile).trim();
            if (!SAFE_NAME.matcher(releaseId).matches()) {
                return null;
            }

            Filepath releaseDirectory = languageRoot.join("releases", releaseId);
            return isCompleteRelease(releaseDirectory) ? releaseDirectory : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    UpdateResult update(String manifestUrl, String language) throws IOException {
        URI manifestUri = requireHttpsUri(manifestUrl, "manifesto");
        RemoteManifest manifest = readManifest(manifestUri);
        validateManifest(manifest);

        String safeLanguage = requireSafeName(language, "idioma");
        LanguageManifest languageManifest = manifest.languages.get(safeLanguage);
        if (languageManifest == null || languageManifest.files == null) {
            throw new IOException("Idioma nao encontrado no manifesto: " + safeLanguage);
        }

        String releaseId = buildReleaseId(manifest.version, languageManifest);
        Filepath languageRoot = cacheRoot.joinSegment(safeLanguage);
        Filepath releasesRoot = languageRoot.joinSegment("releases");
        Filepath releaseDirectory = releasesRoot.joinSegment(releaseId);
        Filepath previousDirectory = getActiveDirectory(safeLanguage);
        boolean installed = false;

        if (!isValidRelease(releaseDirectory, languageManifest)) {
            deleteIfPresent(releaseDirectory);
            installRelease(manifestUri, languageManifest, releasesRoot, releaseDirectory);
            installed = true;
        }

        activateRelease(languageRoot, releaseId);
        deleteOldReleases(releasesRoot, releaseId);
        boolean changed = installed
            || previousDirectory == null
            || !previousDirectory.equals(releaseDirectory);
        return new UpdateResult(changed, releaseDirectory, manifest.version);
    }

    private RemoteManifest readManifest(URI manifestUri) throws IOException {
        byte[] bytes = downloadBytes(manifestUri, MAX_MANIFEST_BYTES);
        try {
            RemoteManifest manifest = gson.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                RemoteManifest.class
            );
            if (manifest == null) {
                throw new IOException("Manifesto remoto vazio");
            }
            return manifest;
        } catch (JsonParseException e) {
            throw new IOException("Manifesto remoto invalido", e);
        }
    }

    private void validateManifest(RemoteManifest manifest) throws IOException {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IOException("Versao de schema nao suportada: " + manifest.schemaVersion);
        }
        if (manifest.version == null || manifest.version.trim().isEmpty()) {
            throw new IOException("Manifesto sem versao");
        }
        if (manifest.languages == null || manifest.languages.isEmpty()) {
            throw new IOException("Manifesto sem idiomas");
        }
    }

    private void installRelease(
        URI manifestUri,
        LanguageManifest languageManifest,
        Filepath releasesRoot,
        Filepath releaseDirectory
    ) throws IOException {
        releasesRoot.createDirectories();
        Filepath stagingDirectory = releasesRoot.createTempDir(STAGING_PREFIX);
        boolean installed = false;

        try {
            for (String fileName : REQUIRED_FILES) {
                TranslationFile remoteFile = languageManifest.files.get(fileName);
                validateFileEntry(fileName, remoteFile);

                URI fileUri = requireHttpsUri(
                    manifestUri.resolve(remoteFile.url).toString(),
                    fileName
                );
                downloadAndValidate(fileUri, stagingDirectory.joinSegment(fileName), remoteFile.sha256);
            }

            stagingDirectory.joinSegment(".complete").write("ok\n");
            moveDirectory(stagingDirectory, releaseDirectory);
            installed = true;
        } finally {
            if (!installed) {
                try {
                    deleteIfPresent(stagingDirectory);
                } catch (IOException ignored) {
                    // Preserve the original download or validation failure.
                }
            }
        }
    }

    private void validateFileEntry(String fileName, TranslationFile remoteFile) throws IOException {
        if (remoteFile == null || remoteFile.url == null || remoteFile.url.trim().isEmpty()) {
            throw new IOException("URL ausente para " + fileName);
        }
        if (remoteFile.sha256 == null || !SHA_256.matcher(remoteFile.sha256).matches()) {
            throw new IOException("SHA-256 invalido para " + fileName);
        }
    }

    private void downloadAndValidate(URI uri, Filepath target, String expectedSha256) throws IOException {
        MessageDigest digest = sha256Digest();
        Request request = request(uri);

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = requireSuccessfulBody(response, uri, MAX_TRANSLATION_BYTES);
            long total = 0;

            try (InputStream input = body.byteStream();
                 OutputStream output = target.openOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_TRANSLATION_BYTES) {
                        throw new IOException("Arquivo remoto excede o limite: " + uri);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        }

        String actualSha256 = toHex(digest.digest());
        if (!actualSha256.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
            throw new IOException("SHA-256 divergente para " + target.getFileName());
        }

        try (InputStream input = target.openInputStream()) {
            TranslationLookupHelper.parseJsonMapStrict(input);
        } catch (Exception e) {
            throw new IOException("JSON de traducao invalido: " + target.getFileName(), e);
        }
    }

    private byte[] downloadBytes(URI uri, int maximumBytes) throws IOException {
        Request request = request(uri);
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = requireSuccessfulBody(response, uri, maximumBytes);
            try (InputStream input = body.byteStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maximumBytes) {
                        throw new IOException("Resposta remota excede o limite: " + uri);
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    private Request request(URI uri) {
        return new Request.Builder()
            .get()
            .header("Accept", "application/vnd.github.raw+json, application/json")
            .header("User-Agent", "OSRS-Traduccion-ES/1.0")
            .url(uri.toString())
            .build();
    }

    private ResponseBody requireSuccessfulBody(Response response, URI uri, long maximumBytes)
        throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("HTTP " + response.code() + " ao baixar " + uri);
        }
        if (!response.request().url().isHttps()
            || !isAllowedHost(response.request().url().host())) {
            throw new IOException("Redirecionamento para URL nao permitida: " + response.request().url());
        }

        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Resposta remota vazia: " + uri);
        }
        if (body.contentLength() > maximumBytes) {
            throw new IOException("Resposta remota excede o limite: " + uri);
        }
        return body;
    }

    private void activateRelease(Filepath languageRoot, String releaseId) throws IOException {
        languageRoot.createDirectories();
        Filepath temporary = languageRoot.createTempFile("active-", ".tmp");
        try {
            temporary.write(releaseId + "\n");
            moveFile(temporary, languageRoot.joinSegment("active.txt"));
        } finally {
            temporary.deleteIfExists();
        }
    }

    private boolean isCompleteRelease(Filepath directory) {
        if (!directory.joinSegment(".complete").isFile()) {
            return false;
        }
        for (String fileName : REQUIRED_FILES) {
            if (!directory.joinSegment(fileName).isFile()) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidRelease(Filepath directory, LanguageManifest languageManifest) {
        if (!isCompleteRelease(directory)) {
            return false;
        }

        try {
            for (String fileName : REQUIRED_FILES) {
                TranslationFile remoteFile = languageManifest.files.get(fileName);
                validateFileEntry(fileName, remoteFile);
                if (!sha256(directory.joinSegment(fileName))
                    .equals(remoteFile.sha256.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String buildReleaseId(String version, LanguageManifest languageManifest)
        throws IOException {
        MessageDigest digest = sha256Digest();
        for (String fileName : REQUIRED_FILES) {
            TranslationFile remoteFile = languageManifest.files.get(fileName);
            validateFileEntry(fileName, remoteFile);
            digest.update(fileName.getBytes(StandardCharsets.UTF_8));
            digest.update(remoteFile.sha256.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        }

        String safeVersion = version.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.+$", "");
        if (safeVersion.isEmpty()) {
            safeVersion = "release";
        }
        return safeVersion + "-" + toHex(digest.digest()).substring(0, 12);
    }

    private URI requireHttpsUri(String value, String label) throws IOException {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Apenas URL HTTPS e permitida para " + label);
            }
            if (uri.getRawUserInfo() != null || uri.getPort() != -1 || !isAllowedHost(uri.getHost())) {
                throw new IOException("Servidor nao permitido para " + label + ": " + uri.getHost());
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IOException("URL invalida para " + label, e);
        }
    }

    static boolean isAllowedHost(String host) {
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private String requireSafeName(String value, String label) throws IOException {
        if (value == null || !SAFE_NAME.matcher(value.trim()).matches() || value.trim().endsWith(".")) {
            throw new IOException(label + " invalido");
        }
        return value.trim();
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private String sha256(Filepath file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = file.openInputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String readString(Filepath file) throws IOException {
        try (BufferedReader reader = file.openBufferedReader()) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /** Filepath.deleteRecursively falla si la carpeta no existe; el código original lo toleraba. */
    private static void deleteIfPresent(Filepath directory) throws IOException {
        if (directory.exists()) {
            directory.deleteRecursively();
        }
    }

    private void moveDirectory(Filepath source, Filepath target) throws IOException {
        if (target.exists()) {
            deleteIfPresent(source);
            return;
        }
        try {
            source.moveTo(target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            source.moveTo(target);
        }
    }

    private void moveFile(Filepath source, Filepath target) throws IOException {
        try {
            source.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            source.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteOldReleases(Filepath releasesRoot, String activeReleaseId) {
        if (!releasesRoot.isDirectory()) {
            return;
        }
        List<Filepath> releases;
        try (Stream<Filepath> children = releasesRoot.walk(1)) {
            releases = children
                .filter(path -> !path.equals(releasesRoot) && path.isDirectory())
                .collect(Collectors.toList());
        } catch (IOException e) {
            return;
        }
        for (Filepath release : releases) {
            String name = release.getFileName();
            if (!name.equals(activeReleaseId) && !name.startsWith(STAGING_PREFIX)) {
                try {
                    deleteIfPresent(release);
                } catch (IOException ignored) {
                    // Old cache cleanup must not invalidate an activated release.
                }
            }
        }
    }

    static final class UpdateResult {
        private final boolean changed;
        private final Filepath activeDirectory;
        private final String version;

        private UpdateResult(boolean changed, Filepath activeDirectory, String version) {
            this.changed = changed;
            this.activeDirectory = activeDirectory;
            this.version = version;
        }

        boolean isChanged() {
            return changed;
        }

        Filepath getActiveDirectory() {
            return activeDirectory;
        }

        String getVersion() {
            return version;
        }
    }

    private static final class RemoteManifest {
        private int schemaVersion;
        private String version;
        private Map<String, LanguageManifest> languages;
    }

    private static final class LanguageManifest {
        private Map<String, TranslationFile> files;
    }

    private static final class TranslationFile {
        private String url;
        private String sha256;
    }
}
