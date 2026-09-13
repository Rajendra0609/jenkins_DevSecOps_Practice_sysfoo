package com.example.sysfoo.service;

import com.example.sysfoo.model.Attachment;
import com.example.sysfoo.repository.AttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
 *   - SECURITY FIX: this now sniffs the first few bytes of every upload
 *     (its "magic number") and confirms they match what the extension
 *     claims — see {@link #validateMagicBytes}. Previously only the
 *     declared extension and the browser-supplied Content-Type were
 *     checked, both of which are just strings the uploader fully controls;
 *     renaming a script or HTML file to "photo.png" was enough to get past
 *     validation. Plain-text files (.txt) have no fixed signature, so
 *     those are instead checked for the ABSENCE of binary content (no NUL
 *     bytes in the first chunk) as a best-effort signal.
 *   - CORRECTNESS FIX ("local disk file storage only... breaks the moment
 *     you run more than one instance"): actual byte storage is delegated to
 *     a {@link FileStorageBackend} bean (currently only
 *     {@link LocalDiskFileStorageBackend} — see that interface's javadoc
 *     for what a horizontally-scalable S3/MinIO backend would need). This
 *     class still owns validation, the UUID-based storage key, and the
 *     Attachment database record either way.
 */
@Service
public class FileStorageService {

    public static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    private static final Set<String> TASK_ALLOWED_EXTENSIONS = Set.of("txt", "zip");

    private static final Set<String> POST_IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private static final Set<String> POST_ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "zip", "doc", "docx"
    );

    @Autowired
    private FileStorageBackend storageBackend;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private ImageThumbnailService imageThumbnailService;

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
        validateMagicBytes(file, ext);
    }

    /**
     * Confirms the file's actual bytes match its claimed extension.
     * Throws IllegalArgumentException with a generic message (never
     * revealing what we detected instead) if they don't.
     */
    private void validateMagicBytes(MultipartFile file, String ext) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(16);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file");
        }

        boolean ok = switch (ext) {
            case "png" -> startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> startsWith(header, 0xFF, 0xD8, 0xFF);
            case "gif" -> startsWith(header, 0x47, 0x49, 0x46, 0x38); // "GIF8"
            case "webp" -> startsWithAscii(header, 0, "RIFF") && startsWithAscii(header, 8, "WEBP");
            case "pdf" -> startsWithAscii(header, 0, "%PDF-");
            // .docx (and modern .doc-as-zip variants) are ZIP containers, same signature as .zip
            case "zip", "docx" -> startsWith(header, 0x50, 0x4B, 0x03, 0x04)
                    || startsWith(header, 0x50, 0x4B, 0x05, 0x06)
                    || startsWith(header, 0x50, 0x4B, 0x07, 0x08);
            // legacy binary .doc — OLE Compound File signature
            case "doc" -> startsWith(header, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            // No universal magic number for plain text — reject if the header
            // looks binary (contains a NUL byte) instead.
            case "txt" -> !containsNulByte(header);
            default -> true; // no rule defined for this extension — extension whitelist already narrowed it
        };

        if (!ok) {
            throw new IllegalArgumentException("The file's content doesn't match its extension");
        }
    }

    private boolean startsWith(byte[] data, int... expectedUnsignedBytes) {
        if (data.length < expectedUnsignedBytes.length) {
            return false;
        }
        for (int i = 0; i < expectedUnsignedBytes.length; i++) {
            if ((data[i] & 0xFF) != expectedUnsignedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithAscii(byte[] data, int offset, String ascii) {
        byte[] expected = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsNulByte(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stores an already-validated file and records its metadata.
     * Exactly one of todoId/postId should be non-null.
     *
     * ENHANCEMENT ("image thumbnailing for uploaded post images"): a
     * post-image upload also gets a generated thumbnail stored alongside
     * the original (see ImageThumbnailService) — the original is always
     * kept too (needed for "view full size" / anywhere the real image
     * matters), this is purely an additional, smaller copy for list views.
     * Thumbnail generation is best-effort: if it fails for any reason, the
     * upload still succeeds with just the original, exactly as it always did.
     */
    public Attachment store(MultipartFile file, Long todoId, Long postId, String uploaderUsername) throws IOException {
        String ext = extensionOf(file.getOriginalFilename());
        String storedFilename = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        byte[] bytes = file.getBytes();
        storageBackend.save(file, storedFilename);

        Attachment attachment = new Attachment();
        attachment.setTodoId(todoId);
        attachment.setPostId(postId);
        attachment.setOriginalFilename(sanitizeForDisplay(file.getOriginalFilename()));
        attachment.setStoredFilename(storedFilename);
        attachment.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedByUsername(uploaderUsername);

        if (postId != null && attachment.isImage()) {
            imageThumbnailService.generateThumbnail(bytes).ifPresent(thumbBytes -> {
                String thumbKey = "thumb-" + storedFilename + ".jpg";
                try {
                    storageBackend.save(new ByteArrayMultipartFile(thumbBytes, "image/jpeg"), thumbKey);
                    attachment.setThumbnailStoredFilename(thumbKey);
                } catch (IOException e) {
                    // Best-effort — see method javadoc. The original upload
                    // already succeeded; a missing thumbnail just means
                    // FileController falls back to serving the full image.
                }
            });
        }

        return attachmentRepository.save(attachment);
    }

    /**
     * Loads a previously-stored attachment's bytes for download — see
     * FileController. Check Resource.exists() before serving it.
     *
     * @param preferThumbnail if true and a thumbnail exists for this
     *                        attachment, serves that instead of the
     *                        original — see FileController's ?thumb=true.
     *                        Silently falls back to the original when
     *                        there's no thumbnail (older upload, non-image,
     *                        or thumbnail generation failed at upload time).
     */
    public Resource loadAsResource(Attachment attachment, boolean preferThumbnail) {
        if (preferThumbnail && attachment.getThumbnailStoredFilename() != null) {
            return storageBackend.load(attachment.getThumbnailStoredFilename());
        }
        return storageBackend.load(attachment.getStoredFilename());
    }

    public Resource loadAsResource(Attachment attachment) {
        return loadAsResource(attachment, false);
    }

    public void delete(Attachment attachment) {
        storageBackend.delete(attachment.getStoredFilename());
        if (attachment.getThumbnailStoredFilename() != null) {
            storageBackend.delete(attachment.getThumbnailStoredFilename());
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
