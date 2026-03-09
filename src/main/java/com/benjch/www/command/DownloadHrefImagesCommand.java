package com.benjch.www.command;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.benjch.www.cli.Command;

public class DownloadHrefImagesCommand implements Command {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadHrefImagesCommand.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "avif");

    @Option(name = "-startUrl", usage = "Page URL to start crawling", required = true)
    private String startUrl;

    @Option(name = "-outputDir", usage = "Folder where full-size images will be downloaded", required = false)
    private String outputDir = "C:\\Users\\NR5145\\HD_D\\benjch\\current\\joypad1";

    @Option(name = "-maxPages", usage = "Maximum number of HTML pages to crawl", required = false)
    private int maxPages = 200;

    @Option(name = "-maxImages", usage = "Maximum number of images to download", required = false)
    private int maxImages = 2000;

    @Override
    public void execute() {
        try {
            URI start = URI.create(startUrl);
            Path targetDir = Path.of(outputDir).toAbsolutePath().normalize();
            Files.createDirectories(targetDir);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();

            Set<String> visitedPages = new HashSet<>();
            Set<String> discoveredImageUrls = new LinkedHashSet<>();
            ArrayDeque<URI> toVisit = new ArrayDeque<>();
            toVisit.add(start);

            while (!toVisit.isEmpty() && visitedPages.size() < maxPages) {
                URI pageUri = toVisit.poll();
                if (pageUri == null) {
                    continue;
                }
                String normalizedPage = normalizeWithoutFragment(pageUri);
                if (!visitedPages.add(normalizedPage)) {
                    continue;
                }

                Document doc;
                try {
                    doc = loadHtml(client, pageUri);
                } catch (Exception e) {
                    LOGGER.warn("Impossible de charger la page {}: {}", pageUri, e.getMessage());
                    continue;
                }

                Elements anchors = doc.select("a[href]");
                for (Element anchor : anchors) {
                    String href = anchor.attr("abs:href").trim();
                    if (href.isEmpty()) {
                        continue;
                    }
                    URI hrefUri;
                    try {
                        hrefUri = URI.create(href);
                    } catch (Exception e) {
                        continue;
                    }

                    if (looksLikeImageUrl(hrefUri)) {
                        discoveredImageUrls.add(normalizeWithoutFragment(hrefUri));
                        continue;
                    }

                    if (isSameHost(start, hrefUri) && isHtmlLikeUrl(hrefUri)) {
                        String normalizedCandidate = normalizeWithoutFragment(hrefUri);
                        if (!visitedPages.contains(normalizedCandidate)) {
                            toVisit.add(hrefUri);
                        }
                    }
                }
            }

            int downloadedCount = 0;
            for (String imageUrl : discoveredImageUrls) {
                if (downloadedCount >= maxImages) {
                    break;
                }
                URI imageUri = URI.create(imageUrl);
                try {
                    Path downloaded = downloadImage(client, imageUri, targetDir);
                    downloadedCount++;
                    LOGGER.info("[{}] Téléchargée: {}", downloadedCount, downloaded.getFileName());
                } catch (Exception e) {
                    LOGGER.warn("Echec téléchargement {}: {}", imageUri, e.getMessage());
                }
            }

            LOGGER.info("Terminé. Pages visitées: {}, images trouvées: {}, images téléchargées: {}, dossier: {}",
                    visitedPages.size(), discoveredImageUrls.size(), downloadedCount, targetDir);

        } catch (Exception e) {
            throw new RuntimeException("Erreur pendant le téléchargement des images", e);
        }
    }

    @Override
    public String getName() {
        return "downloadHrefImages";
    }


    public static void main(String[] args) {
        try {
            DownloadHrefImagesCommand command = new DownloadHrefImagesCommand();
            new CmdLineParser(command).parseArgument(args);
            command.execute();
        } catch (Exception e) {
            LOGGER.error("Unable to run downloadHrefImages command", e);
            System.exit(2);
        }
    }

    Document loadHtml(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("User-Agent", "Mozilla/5.0 (compatible; myManualScrapper/1.0)")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return Jsoup.parse(response.body(), uri.toString());
    }

    Path downloadImage(HttpClient client, URI imageUri, Path outputDirectory) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(imageUri)
                .header("User-Agent", "Mozilla/5.0 (compatible; myManualScrapper/1.0)")
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }

        String fileName = resolveFileName(imageUri, response);
        Path target = uniqueTarget(outputDirectory.resolve(fileName));
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    static String resolveFileName(URI imageUri, HttpResponse<?> response) {
        String fromPath = Path.of(Objects.requireNonNullElse(imageUri.getPath(), "image")).getFileName().toString();
        if (!fromPath.isBlank() && fromPath.contains(".")) {
            return sanitizeFileName(fromPath);
        }

        String ext = extensionFromContentType(response.headers().firstValue("Content-Type").orElse(""));
        if (ext.isBlank()) {
            ext = "jpg";
        }
        return "image." + ext;
    }

    static String extensionFromContentType(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("jpeg")) return "jpg";
        if (lower.contains("png")) return "png";
        if (lower.contains("gif")) return "gif";
        if (lower.contains("webp")) return "webp";
        if (lower.contains("bmp")) return "bmp";
        if (lower.contains("tiff")) return "tiff";
        if (lower.contains("avif")) return "avif";
        return "";
    }

    static boolean looksLikeImageUrl(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0 || lastDot == path.length() - 1) {
            return false;
        }
        String ext = path.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(ext);
    }

    static boolean isHtmlLikeUrl(URI uri) {
        String path = Objects.requireNonNullElse(uri.getPath(), "").toLowerCase(Locale.ROOT);
        if (path.isBlank() || path.endsWith("/")) {
            return true;
        }
        return path.endsWith(".php") || path.endsWith(".html") || path.endsWith(".htm") || !path.contains(".");
    }

    static boolean isSameHost(URI root, URI candidate) {
        return Objects.equals(root.getHost(), candidate.getHost())
                && Objects.equals(root.getScheme(), candidate.getScheme());
    }

    static String normalizeWithoutFragment(URI uri) {
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toString();
        } catch (URISyntaxException e) {
            return uri.toString();
        }
    }

    static Path uniqueTarget(Path candidate) throws IOException {
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String fileName = candidate.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";

        int index = 1;
        while (true) {
            Path next = candidate.getParent().resolve(base + "_" + String.format("%03d", index) + ext);
            if (!Files.exists(next)) {
                return next;
            }
            index++;
        }
    }

    static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
