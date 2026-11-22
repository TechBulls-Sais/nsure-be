package com.nsure.portfolio;

import com.nsure.common.exception.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Claim and policy documents on local disk under {@code nsure.documents.dir}, one folder per claim or policy
 * reference. Stored names are always generated, never the uploader's file name.
 */
@Component
public class DocumentFiles {

    private final Path root;

    public DocumentFiles(@Value("${nsure.documents.dir:data/documents}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    /** Saves the bytes and returns their path relative to the root, to store on the document row. */
    String write(String folder, String extension, byte[] bytes) {
        String storagePath = folder + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(storagePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the document", e);
        }
        return storagePath;
    }

    public Resource read(String storagePath, String what) {
        Path path = resolve(storagePath);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("The file for " + what + " is missing from storage.");
        }
        return new FileSystemResource(path);
    }

    /** The SHA-256 of the stored file, to compare with the hash recorded when it was issued (Blueprint §8). */
    String sha256Of(String storagePath, String what) {
        return DocumentCustody.sha256(bytes(storagePath, what));
    }

    /** The stored file's bytes. */
    byte[] bytes(String storagePath, String what) {
        Path path = resolve(storagePath);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("The file for " + what + " is missing from storage.");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the document", e);
        }
    }

    /** Deletes a disposed document's file (NS-SH09A-006 approved disposal); true when it is gone. */
    public boolean dispose(String storagePath) {
        try {
            Files.deleteIfExists(resolve(storagePath));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Removes a file whose row was not saved; an orphan left behind is harmless. */
    void deleteQuietly(String storagePath) {
        try {
            Files.deleteIfExists(resolve(storagePath));
        } catch (IOException | IllegalArgumentException ignored) {
            // Nothing points at it.
        }
    }

    private Path resolve(String storagePath) {
        Path path = root.resolve(storagePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid document path");
        }
        return path;
    }
}
