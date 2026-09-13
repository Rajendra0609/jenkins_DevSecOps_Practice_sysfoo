package com.example.sysfoo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Default (and, for now, only — see FileStorageBackend's javadoc) storage
 * backend: uploads live on the local disk at app.upload-dir. This is fine
 * for a single instance with a persistent volume mounted at that path; it
 * is NOT safe for more than one instance, since each would have its own,
 * different disk.
 *
 * matchIfMissing = true: existing deployments with no app.storage.backend
 * set at all keep working exactly as before. Explicitly setting the
 * property to anything other than "local" (e.g. ahead of an "s3" backend
 * existing) makes this bean NOT get created — Spring then fails to start
 * with a clear "no FileStorageBackend bean found" rather than silently
 * ignoring the setting and using local disk anyway.
 */
@Component
@ConditionalOnProperty(name = "app.storage.backend", havingValue = "local", matchIfMissing = true)
public class LocalDiskFileStorageBackend implements FileStorageBackend {

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public void save(MultipartFile file, String storageKey) throws IOException {
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);

        Path target = dir.resolve(storageKey).normalize();
        // Defense in depth: even though storageKey is always our own UUID
        // (never user input — see FileStorageService), confirm the resolved
        // path still lands inside uploadDir before writing anything to disk.
        if (!target.startsWith(dir.normalize())) {
            throw new IOException("Resolved upload path escaped the upload directory");
        }
        file.transferTo(target);
    }

    @Override
    public Resource load(String storageKey) {
        return new FileSystemResource(Paths.get(uploadDir).resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(Paths.get(uploadDir).resolve(storageKey));
        } catch (IOException ignored) {
            // Best-effort — see interface contract.
        }
    }
}
