package com.benjch.www.photosorter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void keepNameShouldIncrementWithoutOverwrite() throws Exception {
        PhotoService service = new PhotoService(new ThumbnailCache());
        Path keepDir = tempDir.resolve("keep");
        Files.createDirectories(keepDir);

        Path first = service.findAvailableName(keepDir, "toto", ".jpg");
        assertEquals("toto.jpg", first.getFileName().toString());

        Files.createFile(keepDir.resolve("toto.jpg"));
        Path second = service.findAvailableName(keepDir, "toto", ".jpg");
        assertEquals("toto_01.jpg", second.getFileName().toString());

        Files.createFile(keepDir.resolve("toto_01.jpg"));
        Files.createFile(keepDir.resolve("toto_02.jpg"));
        Path third = service.findAvailableName(keepDir, "toto", ".jpg");
        assertEquals("toto_03.jpg", third.getFileName().toString());
    }

    @Test
    void keepImageShouldUseParentFolderName() throws Exception {
        PhotoService service = new PhotoService(new ThumbnailCache());
        Path sourceDir = tempDir.resolve("my_game");
        Path keepDir = tempDir.resolve("keep");
        Files.createDirectories(sourceDir);
        Files.createDirectories(keepDir);

        Path source = sourceDir.resolve("image.png");
        Files.writeString(source, "fake-image-content");

        PhotoService.KeepResult result = service.keepImage(source.toString(), keepDir.toString());

        assertEquals("my_game.jpg", result.filename());
        assertTrue(Files.exists(keepDir.resolve("my_game.jpg")));
    }

    @Test
    void keepImageShouldIncrementWithoutVariantSuffix() throws Exception {
        PhotoService service = new PhotoService(new ThumbnailCache());
        Path sourceDir = tempDir.resolve("my_game");
        Path keepDir = tempDir.resolve("keep");
        Files.createDirectories(sourceDir);
        Files.createDirectories(keepDir);

        Path source = sourceDir.resolve("image.jpg");
        Files.writeString(source, "fake-image-content");
        Files.createFile(keepDir.resolve("my_game.jpg"));

        PhotoService.KeepResult result = service.keepImage(source.toString(), keepDir.toString());

        assertEquals("my_game_01.jpg", result.filename());
        assertTrue(Files.exists(keepDir.resolve("my_game_01.jpg")));
    }
}
