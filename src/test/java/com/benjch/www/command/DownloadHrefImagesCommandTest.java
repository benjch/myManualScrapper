package com.benjch.www.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadHrefImagesCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectImageUrlsFromHref() {
        assertTrue(DownloadHrefImagesCommand.looksLikeImageUrl(URI.create("https://site/pic/fullsize.jpg")));
        assertTrue(DownloadHrefImagesCommand.looksLikeImageUrl(URI.create("https://site/pic/fullsize.JPEG")));
        assertFalse(DownloadHrefImagesCommand.looksLikeImageUrl(URI.create("https://site/affiche_mag.php?mag=84")));
    }

    @Test
    void shouldCreateUniqueTargetName() throws Exception {
        Path base = tempDir.resolve("cover.jpg");
        Files.createFile(base);
        Files.createFile(tempDir.resolve("cover_001.jpg"));

        Path unique = DownloadHrefImagesCommand.uniqueTarget(base);

        assertEquals("cover_002.jpg", unique.getFileName().toString());
    }

    @Test
    void shouldResolveExtensionFromContentTypeWhenPathHasNoExtension() {
        HttpResponse<Void> response = new FakeResponse("image/png");

        String filename = DownloadHrefImagesCommand.resolveFileName(URI.create("https://site/image"), response);

        assertEquals("image.png", filename);
    }

    private static class FakeResponse implements HttpResponse<Void> {
        private final HttpHeaders headers;

        FakeResponse(String contentType) {
            this.headers = HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (a, b) -> true);
        }

        @Override
        public int statusCode() { return 200; }
        @Override
        public HttpRequest request() { return null; }
        @Override
        public Optional<HttpResponse<Void>> previousResponse() { return Optional.empty(); }
        @Override
        public HttpHeaders headers() { return headers; }
        @Override
        public Void body() { return null; }
        @Override
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override
        public URI uri() { return URI.create("https://site/image"); }
        @Override
        public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
    }
}
