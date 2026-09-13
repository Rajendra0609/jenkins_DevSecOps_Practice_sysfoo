package com.example.sysfoo.service;

import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Adapts an in-memory byte array (a generated thumbnail — see
 * ImageThumbnailService) to the MultipartFile interface, so
 * FileStorageBackend.save(MultipartFile, String) can be reused for it
 * without needing a second, byte[]-based save method on that interface.
 * Never constructed from actual user input — only ever wraps bytes this
 * server itself generated.
 */
class ByteArrayMultipartFile implements MultipartFile {

    private final byte[] content;
    private final String contentType;

    ByteArrayMultipartFile(byte[] content, String contentType) {
        this.content = content;
        this.contentType = contentType;
    }

    @Override
    @NonNull
    public String getName() {
        return "thumbnail";
    }

    @Override
    public String getOriginalFilename() {
        return "thumbnail.jpg";
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    @NonNull
    public byte[] getBytes() {
        return content;
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(@NonNull java.io.File dest) throws IOException, IllegalStateException {
        Files.write(dest.toPath(), content);
    }
}
