package com.example.sysfoo.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * A place to put uploaded file bytes and get them back later, addressed by
 * an opaque storage key (never a user-controlled path — see
 * FileStorageService for how the key is generated).
 *
 * ── CORRECTNESS FIX: "local disk file storage only... breaks the moment
 *    you run more than one instance" ─────────────────────────────────────
 * FileStorageService used to talk to java.nio.file.Files directly, so
 * "where uploads live" and "how a task attachment gets validated / an
 * attachment record gets created" were the same class. That's exactly what
 * makes horizontal scaling impossible: every instance would need the SAME
 * disk, which a plain multi-container deployment doesn't give you. Pulling
 * storage behind this interface is what would let a second implementation
 * (S3, MinIO, Azure Blob, ...) drop in without touching validation,
 * Attachment records, or either controller that uses FileStorageService.
 *
 * ── Why there's only one implementation here ─────────────────────────────
 * An S3-backed implementation needs an AWS SDK dependency this sandbox
 * can't add (its build can only resolve dependencies already vendored —
 * see project notes on the build environment), and there's no S3-compatible
 * endpoint available here to develop and verify it against. Shipping an
 * untested cloud-storage integration would be worse than not shipping one.
 * What's here is the seam a real S3FileStorageBackend can be written
 * against later, in an environment that has both:
 *   1. Add a dependency — either the AWS SDK v2 S3 module, or (simpler)
 *      Spring Cloud AWS's S3 starter, which wraps it in a Spring-friendly API.
 *   2. Implement FileStorageBackend: save() -> s3Client.putObject(...) with
 *      the storage key as the S3 object key; load() -> wrap
 *      s3Client.getObject(...) in an InputStreamResource; delete() ->
 *      s3Client.deleteObject(...), swallowing errors per this interface's
 *      contract.
 *   3. Add app.storage.backend=s3 (+ bucket/region/credentials) config, and
 *      make LocalDiskFileStorageBackend / the new S3 one each
 *      @ConditionalOnProperty on that value so exactly one is active.
 *   4. For a real migration (not just new uploads), a one-off job to copy
 *      existing local files up to the bucket and update Attachment rows if
 *      the storage key format changes.
 */
public interface FileStorageBackend {

    /** Persists the file's bytes under storageKey. Overwrites if the key already exists. */
    void save(MultipartFile file, String storageKey) throws IOException;

    /** Loads previously-stored bytes as a Resource. Check Resource.exists() — a missing key is not an exception. */
    Resource load(String storageKey);

    /** Best-effort delete — never throws. An orphaned object is a much smaller problem than failing the caller's whole operation over it. */
    void delete(String storageKey);
}
