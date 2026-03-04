package com.benjch.www.photosorter;

import java.awt.Desktop;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.Iterator;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public class PhotoService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("<img[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:image/([a-zA-Z0-9.+-]+);base64,(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NUMBERED_FILE_PATTERN = Pattern.compile("^(\\d+)(?:\\..+)?$");

    private final ThumbnailCache thumbnailCache;

    public PhotoService(ThumbnailCache thumbnailCache) {
        this.thumbnailCache = thumbnailCache;
    }

    public FolderEntries listFolderEntries(String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            List<FolderEntry> roots = new ArrayList<>();
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                roots.add(new FolderEntry(root.toString(), root.toString(), Instant.EPOCH.toEpochMilli()));
            }
            roots.sort(Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER));
            return new FolderEntries("", List.of(), roots);
        }

        Path folder = resolveSafePath(rawPath);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Not a directory: " + rawPath);
        }

        List<ImageEntry> images = new ArrayList<>();
        List<FolderEntry> folders = new ArrayList<>();

        try (Stream<Path> stream = Files.list(folder)) {
            stream.forEach(path -> {
                try {
                    if (Files.isDirectory(path)) {
                        folders.add(new FolderEntry(path.toString(), path.getFileName().toString(), Files.getLastModifiedTime(path).toMillis()));
                    } else if (isImage(path)) {
                        ImageSize imageSize = readImageSize(path);
                        images.add(new ImageEntry(path.toString(), path.getFileName().toString(), Files.getLastModifiedTime(path).toMillis(), imageSize.width(), imageSize.height(), extensionOf(path.getFileName().toString()).replace(".", "")));
                    }
                } catch (IOException ignored) {
                    // ignore unreadable entries
                }
            });
        }

        images.sort(Comparator.comparing(ImageEntry::name, String.CASE_INSENSITIVE_ORDER));
        folders.sort(Comparator.comparing(FolderEntry::name, String.CASE_INSENSITIVE_ORDER));

        return new FolderEntries(folder.toString(), images, folders);
    }

    public byte[] loadImage(String rawPath) throws IOException {
        Path path = resolveSafePath(rawPath);
        return Files.readAllBytes(path);
    }

    public byte[] loadThumbnail(String rawPath, int size) throws IOException {
        Path path = resolveSafePath(rawPath);
        long mtime = Files.getLastModifiedTime(path).toMillis();
        String key = path + "|" + mtime + "|" + size;
        byte[] cached = thumbnailCache.get(key);
        if (cached != null) {
            return cached;
        }

        byte[] originalBytes = Files.readAllBytes(path);
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalBytes));
        byte[] output;
        if (original == null) {
            output = originalBytes;
        } else {
            int width = original.getWidth();
            int height = original.getHeight();
            double ratio = Math.min((double) size / width, (double) size / height);
            int targetWidth = Math.max(1, (int) Math.round(width * ratio));
            int targetHeight = Math.max(1, (int) Math.round(height * ratio));
            Image resized = original.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
            BufferedImage buffer = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            buffer.getGraphics().drawImage(resized, 0, 0, null);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ImageIO.write(buffer, "jpg", byteArrayOutputStream);
            output = byteArrayOutputStream.toByteArray();
        }

        thumbnailCache.put(key, output);
        return output;
    }

    public void deleteImage(String rawPath) throws IOException {
        Path path = resolveSafePath(rawPath);
        if (!isImage(path)) {
            throw new IllegalArgumentException("Delete only allowed on image files");
        }

        boolean movedToTrash = false;
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                movedToTrash = desktop.moveToTrash(path.toFile());
            }
        }
        if (!movedToTrash) {
            Files.deleteIfExists(path);
        }
        thumbnailCache.invalidateByPrefix(path.toString());
    }

    public KeepResult keepImage(String rawPath, String keepDir) throws IOException {
        Path source = resolveSafePath(rawPath);
        if (!isImage(source)) {
            throw new IllegalArgumentException("Keep only allowed on image files");
        }

        Path keepPath = resolveSafePath(keepDir);
        Files.createDirectories(keepPath);
        String folderName = source.getParent() != null && source.getParent().getFileName() != null
                ? source.getParent().getFileName().toString()
                : baseNameWithoutExtension(source.getFileName().toString());
        Path target = findAvailableName(keepPath, folderName, ".jpg");

        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return new KeepResult(target.toString(), target.getFileName().toString());
    }


    public ClipboardImportResult importSingleImageFromClipboard(String rawFolderPath, String imageBase64, String mimeType) throws IOException {
        Path folder = resolveSafePath(rawFolderPath);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Not a directory: " + rawFolderPath);
        }
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("Image presse-papier vide");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Contenu image invalide");
        }

        String extension = detectImageExtension(bytes, mimeType);
        Path target = buildClipboardImageTarget(folder, extension);
        Files.write(target, bytes);
        return new ClipboardImportResult(target.toString(), target.getFileName().toString());
    }

    private static Path buildClipboardImageTarget(Path folder, String extension) throws IOException {
        Set<Integer> usedNumbers = new java.util.HashSet<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .filter(name -> name != null)
                    .map(Path::toString)
                    .map(PhotoService::baseNameWithoutExtension)
                    .map(NUMBERED_FILE_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .forEach(matcher -> {
                        try {
                            usedNumbers.add(Integer.parseInt(matcher.group(1)));
                        } catch (NumberFormatException ignored) {
                            // ignore out-of-range values
                        }
                    });
        }

        int nextNumber = 1;
        while (usedNumbers.contains(nextNumber)) {
            nextNumber++;
        }
        String filename = String.format("%02d%s", nextNumber, extension);
        return folder.resolve(filename);
    }

    private static String detectImageExtension(byte[] bytes, String mimeType) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            if (imageInputStream != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
                if (readers.hasNext()) {
                    String formatName = readers.next().getFormatName();
                    return normalizeExtension(formatName);
                }
            }
        } catch (IOException ignored) {
            // fallback below
        }

        String fromMime = extensionFromMimeType(mimeType);
        if (!fromMime.isBlank()) {
            return fromMime;
        }
        throw new IllegalArgumentException("Impossible de détecter l'extension de l'image");
    }

    public ImportResult importImagesFromHtml(String rawFolderPath, String html) throws IOException {
        Path folder = resolveSafePath(rawFolderPath);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Not a directory: " + rawFolderPath);
        }
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("HTML vide dans le presse-papier");
        }

        LinkedHashSet<String> sources = extractImageSources(html);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Aucune image trouvée dans le HTML");
        }

        List<String> imported = new ArrayList<>();
        int counter = 1;
        for (String source : sources) {
            try {
                ImportedFile importedFile = importSingleImage(folder, source, counter);
                imported.add(importedFile.fileName());
                counter++;
            } catch (Exception ignored) {
                // Ignore one broken source and continue with others.
            }
        }
        if (imported.isEmpty()) {
            throw new IllegalArgumentException("Impossible de charger les images du HTML");
        }
        return new ImportResult(imported.size(), imported);
    }

    private ImportedFile importSingleImage(Path folder, String source, int counter) throws IOException, InterruptedException {
        String trimmedSource = source == null ? "" : source.trim();
        if (trimmedSource.isEmpty()) {
            throw new IllegalArgumentException("Image source vide");
        }

        if (trimmedSource.toLowerCase(Locale.ROOT).startsWith("data:image/")) {
            Matcher matcher = DATA_URL_PATTERN.matcher(trimmedSource);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Data URL image invalide");
            }
            String extension = normalizeExtension(matcher.group(1));
            byte[] bytes = Base64.getDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
            Path target = buildUniqueTarget(folder, "clipboard-image", extension, counter);
            Files.write(target, bytes);
            return new ImportedFile(target.getFileName().toString());
        }

        URI uri = URI.create(trimmedSource);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Source image non supportée: " + trimmedSource);
        }

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri).GET().build();
        java.net.http.HttpResponse<byte[]> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("Download failed for image");
        }

        String extension = extensionFromUriOrContentType(uri, response.headers().firstValue("Content-Type").orElse(""));
        Path target = buildUniqueTarget(folder, baseNameFromUri(uri), extension, counter);
        Files.write(target, response.body());
        return new ImportedFile(target.getFileName().toString());
    }

    private static LinkedHashSet<String> extractImageSources(String html) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        Matcher matcher = IMG_SRC_PATTERN.matcher(html);
        while (matcher.find()) {
            String source = matcher.group(2);
            if (source != null && !source.isBlank()) {
                sources.add(source.trim());
            }
        }
        return sources;
    }

    private static Path buildUniqueTarget(Path folder, String baseName, String extension, int counter) {
        String safeBase = sanitizeBaseName(baseName);
        Path target = folder.resolve(safeBase + extension);
        if (!Files.exists(target)) {
            return target;
        }
        int idx = counter;
        while (true) {
            Path candidate = folder.resolve(safeBase + "_" + idx + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            idx++;
        }
    }

    private static String sanitizeBaseName(String input) {
        String fallback = (input == null || input.isBlank()) ? "clipboard-image" : input;
        String value = fallback.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (value.isBlank()) {
            return "clipboard-image";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String baseNameFromUri(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return "clipboard-image";
        }
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        if (fileName.isBlank()) {
            return "clipboard-image";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extensionFromUriOrContentType(URI uri, String contentType) {
        String uriPath = uri.getPath() == null ? "" : uri.getPath();
        int dot = uriPath.lastIndexOf('.');
        if (dot >= 0 && dot < uriPath.length() - 1) {
            String ext = normalizeExtension(uriPath.substring(dot + 1));
            if (!ext.isBlank()) {
                return ext;
            }
        }
        String guessed = URLConnection.guessContentTypeFromName(uriPath);
        String fromMime = extensionFromMimeType(contentType.isBlank() ? guessed : contentType);
        return fromMime.isBlank() ? ".jpg" : fromMime;
    }

    private static String extensionFromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "";
        }
        String value = mimeType.toLowerCase(Locale.ROOT);
        if (value.contains("image/jpeg") || value.contains("image/jpg")) return ".jpg";
        if (value.contains("image/png")) return ".png";
        if (value.contains("image/gif")) return ".gif";
        if (value.contains("image/webp")) return ".webp";
        return "";
    }

    private static String normalizeExtension(String rawExtension) {
        if (rawExtension == null || rawExtension.isBlank()) {
            return ".jpg";
        }
        String cleaned = rawExtension.toLowerCase(Locale.ROOT).replace(".", "");
        if ("jpg".equals(cleaned) || "jpeg".equals(cleaned)) return ".jpg";
        if ("png".equals(cleaned)) return ".png";
        if ("gif".equals(cleaned)) return ".gif";
        if ("webp".equals(cleaned)) return ".webp";
        return ".jpg";
    }

    Path findAvailableName(Path keepDir, String base, String extension) {
        Path first = keepDir.resolve(base + extension);
        if (!Files.exists(first)) {
            return first;
        }
        int counter = 1;
        while (true) {
            String suffix = counter < 100 ? String.format("_%02d", counter) : "_" + counter;
            Path candidate = keepDir.resolve(base + suffix + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    public static boolean isImage(Path path) {
        String ext = extensionOf(path.getFileName().toString()).replace(".", "");
        return SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }


    private static String baseNameWithoutExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0) {
            return fileName;
        }
        return fileName.substring(0, idx);
    }

    private static String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return fileName.substring(idx).toLowerCase(Locale.ROOT);
    }

    private ImageSize readImageSize(Path path) {
        try (InputStream inputStream = Files.newInputStream(path);
                ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            if (imageInputStream == null) {
                return new ImageSize(0, 0);
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return new ImageSize(0, 0);
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream, true, true);
                return new ImageSize(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            return new ImageSize(0, 0);
        }
    }

    public static String contentTypeFor(String rawPath) {
        String ext = extensionOf(rawPath);
        return switch (ext) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    public static Path resolveSafePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Path required");
        }
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Absolute path required");
        }
        return path;
    }

    public record FolderEntries(String currentPath, List<ImageEntry> images, List<FolderEntry> folders) {
    }

    public record ImageEntry(String path, String name, long modifiedAt, int width, int height, String extension) {
    }

    public record FolderEntry(String path, String name, long modifiedAt) {
    }

    public record KeepResult(String path, String filename) {
    }

    public record ImportResult(int importedCount, List<String> files) {
    }

    public record ClipboardImportResult(String path, String filename) {
    }

    private record ImportedFile(String fileName) {
    }

    private record ImageSize(int width, int height) {
    }
}
