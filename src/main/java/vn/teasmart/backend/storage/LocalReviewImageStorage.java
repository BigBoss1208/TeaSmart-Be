package vn.teasmart.backend.storage;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.exception.ReviewImageException;

@Component
public class LocalReviewImageStorage implements ReviewImageStorage {
    private static final Logger LOG = LoggerFactory.getLogger(LocalReviewImageStorage.class);
    private final Path root;
    public LocalReviewImageStorage(@Value("${teasmart.review-images.storage-root}") String configured) {
        Path candidate = Path.of(configured).normalize();
        if (!candidate.isAbsolute()) throw new IllegalArgumentException("Image storage root must be absolute.");
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (candidate.startsWith(cwd)) throw new IllegalArgumentException("Image storage must be outside the repository.");
        for (Path p = candidate; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || Files.exists(p.resolve(".git"))) {
                throw new IllegalArgumentException("Unsafe image storage root.");
            }
        }
        root = candidate;
    }
    private void prepare() throws IOException {
        for (Path p = root; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && !p.toRealPath().equals(p.toAbsolutePath().normalize()))) throw new IOException("Unsafe storage");
        }
        Files.createDirectories(root);
        for (String dir : new String[]{"staging", "files", "cleanup"}) {
            Path p = root.resolve(dir);
            if (Files.isSymbolicLink(p) || (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && !p.toRealPath().equals(p.toAbsolutePath().normalize()))) throw new IOException("Unsafe storage");
            Files.createDirectories(p);
        }
    }
    private Path path(String dir, String key) throws IOException {
        prepare();
        if (!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)")) {
            throw new IOException("Invalid storage key");
        }
        Path p = root.resolve(dir).resolve(key).normalize();
        if (!p.startsWith(root.resolve(dir)) || Files.isSymbolicLink(p)) throw new IOException("Unsafe storage");
        return p;
    }
    @Override public String stage(byte[] bytes, String extension) {
        String key = UUID.randomUUID() + "." + extension;
        try { Files.write(path("staging", key), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); return key; }
        catch (IOException e) { discard(key); throw unavailable(); }
    }
    @Override public void finalizeFile(String key) {
        try {
            Path target = path("files", key);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Storage collision");
            Files.move(path("staging", key), target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e) { throw unavailable(); }
    }
    @Override public byte[] read(String key) {
        try (var channel = Files.newByteChannel(path("files", key), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size < 1 || size > 5242880) throw new IOException("Invalid stored size");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Truncated file");
            return buffer.array();
        } catch (IOException e) { throw unavailable(); }
    }
    @Override public void discard(String key) {
        try { Files.deleteIfExists(path("staging", key)); Files.deleteIfExists(path("files", key)); }
        catch (IOException e) { recordCleanup(key); }
    }
    @Override public void deleteAfterCommit(String key) { discard(key); }
    private void recordCleanup(String key) {
        try { Files.writeString(path("cleanup", key), "retry", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException e) { LOG.error("Unable to persist image cleanup marker; operator reconciliation required."); }
        LOG.warn("Image cleanup pending; retry or operator reconciliation required.");
    }
    @Override public void retryDeletes() {
        try {
            prepare();
            try (var markers = Files.list(root.resolve("cleanup"))) {
                for (Path marker : markers.toList()) {
                    String key = marker.getFileName().toString();
                    try {
                        Files.deleteIfExists(path("staging", key)); Files.deleteIfExists(path("files", key));
                        Files.deleteIfExists(path("cleanup", key));
                    } catch (IOException e) { LOG.warn("Image cleanup retry deferred."); }
                }
            }
        } catch (IOException e) { LOG.warn("Image cleanup retry unavailable."); }
    }
    private ReviewImageException unavailable() {
        return new ReviewImageException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_UNAVAILABLE", "Image storage is unavailable.");
    }
}
