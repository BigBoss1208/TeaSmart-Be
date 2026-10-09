package vn.teasmart.backend.storage;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.teasmart.backend.exception.ReviewImageException;

@Component
public class ReviewImageValidator {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Semaphore decoders = new Semaphore(2);
    public record Normalized(byte[] bytes, String extension, String contentType) {}

    public List<Normalized> normalize(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "At least one image is required.");
        if (files.size() > 5) throw error(HttpStatus.CONFLICT, "REVIEW_IMAGE_LIMIT_EXCEEDED", "At most five images are allowed.");
        if (!decoders.tryAcquire()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_UNAVAILABLE", "Image processing is busy; retry later.");
        try {
            List<Normalized> result = new ArrayList<>();
            for (MultipartFile file : files) result.add(normalizeOne(file));
            return result;
        } finally { decoders.release(); }
    }
    private Normalized normalizeOne(MultipartFile file) {
        if (file == null || file.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Image must not be empty.");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw tooLarge();
            String format = signature(bytes);
            validateContainer(bytes, format);
            try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw invalid();
                ImageReader reader = readers.next();
                try {
                    var decodingWarning = new java.util.concurrent.atomic.AtomicBoolean(false);
                    reader.addIIOReadWarningListener((source, warning) -> decodingWarning.set(true));
                    reader.setInput(imageInput, false, true);
                    if (!reader.getFormatName().equalsIgnoreCase(format)) throw invalid();
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 20_000_000L) throw tooLarge();
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height || decodingWarning.get()) {
                        if (decoded != null) decoded.flush();
                        throw invalid();
                    }
                    // A fresh raster drops EXIF, ICC, text and other uploaded metadata.
                    boolean jpeg = format.equals("JPEG");
                    BufferedImage clean = new BufferedImage(width, height,
                            jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
                    var graphics = clean.createGraphics();
                    try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); decoded.flush(); }
                    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(jpeg ? "JPEG" : "PNG");
                    if (!writers.hasNext()) { clean.flush(); throw invalid(); }
                    ImageWriter writer = writers.next();
                    // Bound the seekable writer output directly. ImageIO's default file cache can
                    // mask an underlying size-limit failure with EOFException while flushing.
                    try (var output = new BoundedImageOutput()) {
                        writer.setOutput(output);
                        writer.write(null, new IIOImage(clean, null, null), writer.getDefaultWriteParam());
                        byte[] encoded = output.toByteArray();
                        if (encoded.length < 1) throw invalid();
                        return new Normalized(encoded, jpeg ? "jpg" : "png", jpeg ? "image/jpeg" : "image/png");
                    } finally { writer.dispose(); clean.flush(); }
                } finally { reader.dispose(); }
            }
        } catch (ReviewImageException e) { throw e; }
        catch (IOException | RuntimeException e) {
            // ImageIO may wrap a bounded-output failure in IIOException.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ReviewImageException failure) throw failure;
            }
            throw invalid();
        }
    }
    private void validateContainer(byte[] bytes, String format) {
        // ImageIO can recover truncated streams; uploads require a complete container.
        if (format.equals("JPEG")) {
            if (bytes.length < 4 || (bytes[bytes.length - 2] & 255) != 255
                    || (bytes[bytes.length - 1] & 255) != 217) throw invalid();
        } else if (format.equals("PNG")) {
            int offset = 8;
            boolean first = true, hasPixels = false;
            while (offset < bytes.length) {
                if (bytes.length - offset < 12) throw invalid();
                long length = unsignedInt(bytes, offset);
                if (length > bytes.length - offset - 12L) throw invalid();
                int size = (int) length;
                String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (first && (!type.equals("IHDR") || size != 13)) throw invalid();
                if (!first && type.equals("IHDR")) throw invalid();
                var crc = new java.util.zip.CRC32();
                crc.update(bytes, offset + 4, size + 4);
                if (crc.getValue() != unsignedInt(bytes, offset + 8 + size)) throw invalid();
                offset += size + 12;
                if (type.equals("IDAT")) hasPixels = true;
                if (type.equals("IEND")) {
                    if (size != 0 || offset != bytes.length || !hasPixels) throw invalid();
                    return;
                }
                first = false;
            }
            throw invalid();
        }
    }
    private long unsignedInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 255L) << 24) | ((bytes[offset + 1] & 255L) << 16)
                | ((bytes[offset + 2] & 255L) << 8) | (bytes[offset + 3] & 255L);
    }
    private String signature(byte[] b) {
        if (b.length >= 3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "JPEG";
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b,8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "PNG";
        if (b.length >= 20 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F'
                && b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P') {
            long declared = (b[4]&255L) | ((b[5]&255L)<<8) | ((b[6]&255L)<<16) | ((b[7]&255L)<<24);
            if (declared + 8 != b.length) throw invalid();
            if (b.length > 20 && b[12]=='V' && b[13]=='P' && b[14]=='8' && b[15]=='X' && (b[20]&2)!=0) throw invalid();
            return "WebP";
        }
        throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE_TYPE", "Only JPEG, PNG and static WebP are supported.");
    }
    private static ReviewImageException error(HttpStatus status,String code,String message) { return new ReviewImageException(status,code,message); }
    private static ReviewImageException invalid() { return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"INVALID_IMAGE_CONTENT","Image cannot be decoded safely."); }
    private static ReviewImageException tooLarge() { return error(HttpStatus.PAYLOAD_TOO_LARGE,"REVIEW_IMAGE_TOO_LARGE","Image exceeds the byte or pixel limit."); }
    private static class BoundedImageOutput extends javax.imageio.stream.ImageOutputStreamImpl {
        private byte[] buffer = new byte[8192];
        private int size;
        private void capacity(int length) throws IOException {
            checkClosed();
            if (length < 0 || streamPos > MAX_BYTES - (long) length) throw tooLarge();
            int end = (int) streamPos + length;
            if (end > buffer.length) buffer = Arrays.copyOf(buffer, Math.min(MAX_BYTES, Math.max(end, buffer.length * 2)));
        }
        @Override public void write(int value) throws IOException {
            capacity(1); bitOffset = 0; buffer[(int) streamPos++] = (byte) value; size = Math.max(size, (int) streamPos);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            capacity(length); bitOffset = 0;
            System.arraycopy(bytes, offset, buffer, (int) streamPos, length);
            streamPos += length; size = Math.max(size, (int) streamPos);
        }
        @Override public int read() throws IOException {
            checkClosed(); bitOffset = 0;
            return streamPos >= size ? -1 : buffer[(int) streamPos++] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkClosed(); Objects.checkFromIndexSize(offset, length, bytes.length); bitOffset = 0;
            if (length == 0) return 0;
            if (streamPos >= size) return -1;
            int count = Math.min(length, size - (int) streamPos);
            System.arraycopy(buffer, (int) streamPos, bytes, offset, count); streamPos += count; return count;
        }
        @Override public void seek(long position) throws IOException {
            if (position > MAX_BYTES) throw tooLarge();
            super.seek(position);
        }
        @Override public long length() { return size; }
        byte[] toByteArray() { return Arrays.copyOf(buffer, size); }
    }
}
