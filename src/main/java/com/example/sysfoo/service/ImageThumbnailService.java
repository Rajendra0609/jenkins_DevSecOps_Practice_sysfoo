package com.example.sysfoo.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * ENHANCEMENT ("image thumbnailing for uploaded post images — currently
 * full-size originals are served every time"): generates a small preview
 * so the Watering Hole board (which can show many posts on one page) isn't
 * pulling down full-resolution images just to render a card-sized preview.
 *
 * ── Why plain java.awt/javax.imageio instead of a library like
 *    Thumbnailator ──────────────────────────────────────────────────────
 * Same constraint as everywhere else new functionality was added this
 * pass: this project's build can only resolve dependencies already in
 * pom.xml (see the project's notes on its sandboxed build environment).
 * ImageIO + BufferedImage + Graphics2D ship with every JDK, so this needed
 * nothing new — at the cost of a bit more code than a one-line library
 * call would take.
 *
 * ── Headless safety ───────────────────────────────────────────────────────
 * java.awt classes normally expect a display; on a headless server that
 * throws HeadlessException the moment something tries to touch a real
 * Toolkit/GUI component. Pure in-memory BufferedImage manipulation
 * (exactly what this class does) doesn't need a display and works fine
 * with -Djava.awt.headless=true, which the static initializer below sets
 * defensively in case the JVM wasn't already started with it — Spring Boot
 * does not set this automatically.
 */
@Service
public class ImageThumbnailService {

    static {
        // Defensive: harmless if already set (e.g. via -D flag), and avoids
        // a HeadlessException surprise on a server that forgot to set it.
        System.setProperty("java.awt.headless", "true");
    }

    private static final int MAX_DIMENSION = 480;

    /**
     * @param imageBytes the original, already-validated (see
     *                   FileStorageService.validateMagicBytes) image bytes
     * @return JPEG-encoded thumbnail bytes, or empty if the bytes couldn't
     *         be decoded as an image for any reason — callers should treat
     *         that as "skip the thumbnail, keep the original", never as a
     *         reason to fail the whole upload.
     */
    public Optional<byte[]> generateThumbnail(byte[] imageBytes) {
        try (InputStream in = new ByteArrayInputStream(imageBytes)) {
            BufferedImage original = ImageIO.read(in);
            if (original == null) {
                return Optional.empty();
            }

            int width = original.getWidth();
            int height = original.getHeight();
            if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
                // Already small — re-encoding would only lose quality for no size benefit.
                return Optional.empty();
            }

            double scale = Math.min((double) MAX_DIMENSION / width, (double) MAX_DIMENSION / height);
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));

            // TYPE_INT_RGB (not ARGB): JPEG has no alpha channel, and
            // flattening onto white here (rather than leaving it to
            // whatever ImageIO's writer would otherwise guess) keeps a
            // transparent PNG's thumbnail looking sane instead of showing
            // stray black where the transparency was.
            BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumbnail.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, targetWidth, targetHeight);
                g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean wrote = ImageIO.write(thumbnail, "jpg", out);
            if (!wrote) {
                return Optional.empty();
            }
            return Optional.of(out.toByteArray());
        } catch (IOException | RuntimeException e) {
            // Corrupt image, unsupported format ImageIO can't decode
            // (e.g. some WEBP variants — ImageIO's built-in plugins don't
            // cover every WEBP flavor), out-of-memory on a huge image, etc.
            // None of these should take down the upload itself.
            return Optional.empty();
        }
    }
}
