package vn.teasmart.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.LeafPredictionResponse;
import vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse;
import vn.teasmart.backend.exception.LeafAnalysisException;
import vn.teasmart.backend.service.LeafAnalysisClient;

@RestController
public class LeafClassificationController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeafClassificationController.class);
    private static final long IMAGE_LIMIT = 10 * 1024 * 1024;
    private static final long VIDEO_LIMIT = 30_000_000;

    private final LeafAnalysisClient client;
    private final Path tempDirectory;

    public LeafClassificationController(LeafAnalysisClient client,
            @Value("${java.io.tmpdir}") String tempDirectory) {
        this.client = client;
        this.tempDirectory = Path.of(tempDirectory);
    }

    @PostMapping(value = "/api/leaf-classifications/predict", produces = MediaType.APPLICATION_JSON_VALUE)
    public LeafPredictionResponse predict(HttpServletRequest request) {
        String contentType = mediaType(request);
        long limit = contentType.equals("video/mp4") ? VIDEO_LIMIT : IMAGE_LIMIT;
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            throw tooLarge();
        }
        if (declared == 0) {
            throw empty();
        }

        Path path = null;
        try {
            path = Files.createTempFile(tempDirectory, "teasmart-leaf-", ".upload");
            long length;
            try (InputStream input = uploadBody(request); OutputStream output = Files.newOutputStream(path)) {
                length = copyUpload(input, output, limit);
            }
            if (length == 0) {
                throw empty();
            }
            if (declared >= 0 && length != declared) {
                throw interruptedUpload();
            }
            try (InputStream body = Files.newInputStream(path, StandardOpenOption.READ,
                    StandardOpenOption.DELETE_ON_CLOSE)) {
                return client.predict(contentType, body, length);
            }
        } catch (IOException exception) {
            LOGGER.warn("Leaf temporary upload storage unavailable.");
            throw new LeafAnalysisException(HttpStatus.SERVICE_UNAVAILABLE, "LEAF_UPLOAD_UNAVAILABLE",
                    "Tạm thời không thể xử lý tệp tải lên.");
        } finally {
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.warn("Could not clean up a temporary leaf upload.");
                }
            }
        }
    }

    @ExceptionHandler(LeafAnalysisException.class)
    public ResponseEntity<ErrorResponse> handleLeafError(LeafAnalysisException error) {
        return ResponseEntity.status(error.status()).body(new ErrorResponse(error.code(), error.getMessage()));
    }

    private static String mediaType(HttpServletRequest request) {
        String rawType = request.getContentType();
        String type = rawType == null ? "" : rawType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if ((encoding != null && !encoding.equalsIgnoreCase("identity"))
                || !(type.equals("image/jpeg") || type.equals("image/png") || type.equals("video/mp4"))) {
            throw new LeafAnalysisException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Chỉ hỗ trợ ảnh JPG, JPEG, PNG hoặc video MP4.");
        }
        return type;
    }

    private static InputStream uploadBody(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (IOException exception) {
            throw interruptedUpload();
        }
    }

    private static long copyUpload(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long count = 0;
        while (true) {
            int read;
            try {
                read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - count + 1));
            } catch (IOException exception) {
                throw interruptedUpload();
            }
            if (read < 0) {
                return count;
            }
            count += read;
            if (count > limit) {
                throw tooLarge();
            }
            output.write(buffer, 0, read);
        }
    }

    private static LeafAnalysisException empty() {
        return new LeafAnalysisException(HttpStatus.BAD_REQUEST, "EMPTY_UPLOAD", "Tệp tải lên rỗng.");
    }

    private static LeafAnalysisException tooLarge() {
        return new LeafAnalysisException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE",
                "Tệp tải lên vượt giới hạn dung lượng.");
    }

    private static LeafAnalysisException interruptedUpload() {
        return new LeafAnalysisException(HttpStatus.BAD_REQUEST, "UPLOAD_INTERRUPTED",
                "Không thể đọc đầy đủ tệp tải lên.");
    }
}
