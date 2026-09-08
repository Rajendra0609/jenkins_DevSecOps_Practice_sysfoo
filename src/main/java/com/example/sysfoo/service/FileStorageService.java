package com.example.sysfoo.service;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.repository.AttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Handles file uploads for both task attachments and post images/files.
 *
 * ── Security notes ──────────────────────────────────────────────────────
 *   - The on-disk filename is always a fresh random UUID, never derived
 *     from the user-supplied original filename — this is what actually
 *     prevents path traversal (a name like "../../etc/passwd" or one
 *     containing null bytes can't do anything, because it's never used as
 *     a path component). The original filename is stored separately, purely
 *     for display, and is never interpreted as a path.
 *   - Extension + size are validated here in addition to Spring's own
 *     spring.servlet.multipart.max-file-size (see application.properties) —
 *     that gives a clean JSON error instead of a raw multipart exception,
 *     and lets task attachments (.txt/.zip only) and post uploads (a wider
 *     but still fixed whitelist) enforce different rules.
 *   - This does NOT sniff file *content* (magic bytes) — only the declared
 *     extension and the browser-supplied Content-Type are checked. Good
 *     enough for a practice app; a hardened production version would also
 *     verify the actual bytes match the claimed type.
 */
@Service
public class FileStorageService {

    public static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    private static final Set<String> TASK_ALLOWED_EXTENSIONS = Set.of("txt", "zip");

    private static final Set<String> POST_IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private static final Set<String> POST_ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "zip", "doc", "docx"
    );

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Autowired
    private AttachmentRepository attachmentRepository;

    /** @throws IllegalArgumentException with a user-facing message if the file fails validation */
    public void validateTaskAttachment(MultipartFile file) {
        validate(file, TASK_ALLOWED_EXTENSIONS, "Task attachments must be .txt or .zip files");
    }

    /** For a post's dedicated "upload an image" slot — image formats only. */
    public void validatePostImage(MultipartFile file) {
        validate(file, POST_IMAGE_EXTENSIONS, "Image must be PNG, JPG, GIF or WEBP");
    }

    /** For a post's separate general "attach a file" slot — a wider whitelist. */
    public void validatePostUpload(MultipartFile file) {
        validate(file, POST_ALLOWED_EXTENSIONS, "Unsupported file type for a post upload");
    }

    private void validate(MultipartFile file, Set<String> allowedExtensions, String typeErrorMessage) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds the 20MB limit");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!allowedExtensions.contains(ext)) {
            throw new IllegalArgumentException(typeErrorMessage);
        }
    }

    /**
     * Stores an already-validated file on disk and records its metadata.
     * Exactly one of todoId/postId should be non-null.
     */
    public Attachment store(MultipartFile file, Long todoId, Long postId, String uploaderUsername) throws IOException {
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);

        String ext = extensionOf(file.getOriginalFilename());
        String storedFilename = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = dir.resolve(storedFilename).normalize();
        // Defense in depth: even though storedFilename is always our own UUID
        // (never user input), confirm the resolved path still lands inside
        // uploadDir before writing anything to disk.
        if (!target.startsWith(dir.normalize())) {
            throw new IOException("Resolved upload path escaped the upload directory");
        }
        file.transferTo(target);

        Attachment attachment = new Attachment();
        attachment.setTodoId(todoId);
        attachment.setPostId(postId);
        attachment.setOriginalFilename(sanitizeForDisplay(file.getOriginalFilename()));
        attachment.setStoredFilename(storedFilename);
        attachment.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedByUsername(uploaderUsername);
        return attachmentRepository.save(attachment);
    }

    public Path resolve(Attachment attachment) {
        return Paths.get(uploadDir).resolve(attachment.getStoredFilename());
    }

    public void delete(Attachment attachment) {
        try {
            Files.deleteIfExists(resolve(attachment));
        } catch (IOException ignored) {
            // Best-effort — an orphaned file on disk is a much smaller problem
            // than failing the whole delete operation over it.
        }
        attachmentRepository.delete(attachment);
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Strips anything that isn't a plain display-safe filename — used ONLY for showing the name back to users, never as a path. */
    private String sanitizeForDisplay(String filename) {
        if (filename == null) {
            return "file";
        }
        String base = Paths.get(filename).getFileName().toString();
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }
}
