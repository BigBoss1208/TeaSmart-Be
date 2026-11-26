package vn.teasmart.backend.leaf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.config.JwtConfig;
import vn.teasmart.backend.config.SecurityConfig;
import vn.teasmart.backend.controller.LeafClassificationController;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.exception.GlobalExceptionHandler;
import vn.teasmart.backend.exception.LeafAnalysisException;
import vn.teasmart.backend.security.CustomUserDetailsService;
import vn.teasmart.backend.security.JsonAccessDeniedHandler;
import vn.teasmart.backend.security.JsonAuthenticationEntryPoint;
import vn.teasmart.backend.security.JwtService;
import vn.teasmart.backend.security.TeaSmartJwtAuthenticationConverter;
import vn.teasmart.backend.service.LeafAnalysisClient;
import vn.teasmart.backend.service.UserService;

class LeafGatewayTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong RECEIVED = new AtomicLong(-1);
    private static volatile Consumer<HttpExchange> response;
    private static HttpServer upstream;
    private static ExecutorService executor;
    private static LeafTestServer gateway;

    @TempDir
    static Path spool;

    @BeforeAll
    static void start() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        upstream.setExecutor(executor);
        upstream.createContext("/predict", exchange -> {
            try (exchange) {
                RECEIVED.set(exchange.getRequestBody().transferTo(OutputStream.nullOutputStream()));
                response.accept(exchange);
            }
        });
        upstream.start();
        gateway = new LeafTestServer("http://127.0.0.1:" + upstream.getAddress().getPort(), spool, 2200);
    }

    @AfterAll
    static void stop() {
        if (gateway != null) {
            gateway.close();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @BeforeEach
    void prepareResponse() {
        RECEIVED.set(-1);
        response = exchange -> json(exchange, 200,
                prediction("video/mp4".equals(exchange.getRequestHeaders().getFirst("Content-Type"))));
    }

    @AfterEach
    void checkSpoolEmpty() throws IOException {
        try (var files = Files.list(spool)) {
            assertEquals(0, files.count(), "A completed request left a temporary upload");
        }
    }

    @Test
    void rejectsMissingOrInvalidBearer() throws Exception {
        var body = HttpRequest.BodyPublishers.ofByteArray(image("png"));
        expect(gateway.send("image/png", body, null), 401, "UNAUTHORIZED");
        expect(gateway.send("image/png", body, "bad-token"), 401, "UNAUTHORIZED");
        assertEquals(-1, RECEIVED.get());
    }

    @Test
    void routesAuthorizedJpegAndPngWithStableJsonAndNoForwardedBearer() throws Exception {
        response = exchange -> {
            assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").matches("image/(jpeg|png)"));
            json(exchange, 200, prediction(false));
        };
        for (String format : List.of("jpeg", "png")) {
            byte[] image = image(format);
            var actual = gateway.send("image/" + format, HttpRequest.BodyPublishers.ofByteArray(image), gateway.token);
            assertEquals(200, actual.statusCode(), actual.body());
            assertEquals(image.length, RECEIVED.get());
            JsonNode body = JSON.readTree(actual.body());
            assertEquals("image", body.path("media_type").stringValue());
            assertEquals(7, body.path("class_probabilities").size());
            assertTrue(body.path("video").isNull());
        }
    }

    @Test
    void emptyOrUnsupportedRawRequestsAreRejectedBeforeForwarding() throws Exception {
        expect(gateway.send("image/png", HttpRequest.BodyPublishers.noBody(), gateway.token), 400, "EMPTY_UPLOAD");
        expect(gateway.send("image/png", HttpRequest.BodyPublishers.ofInputStream(InputStream::nullInputStream),
                gateway.token), 400, "EMPTY_UPLOAD");
        expect(gateway.send("text/plain", HttpRequest.BodyPublishers.ofString("no"), gateway.token),
                415, "UNSUPPORTED_MEDIA_TYPE");
        assertEquals(-1, RECEIVED.get());
    }

    @Test
    void chunkedImageWithoutContentLengthIsForwardedWithCountedLength() throws Exception {
        byte[] picture = image("png");
        var chunked = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(picture));
        response = exchange -> {
            assertEquals(Integer.toString(picture.length), exchange.getRequestHeaders().getFirst("Content-Length"));
            json(exchange, 200, prediction(false));
        };
        var actual = gateway.send("image/png", chunked, gateway.token);
        assertEquals(200, actual.statusCode(), actual.body());
        assertEquals(picture.length, RECEIVED.get());
    }

    @Test
    void declaredOversizeIsRejectedFromHeadersAlone() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", gateway.port)) {
            socket.setSoTimeout(4000);
            String headers = "POST /api/leaf-classifications/predict HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nContent-Type: video/mp4\r\nContent-Length: 30000001\r\n"
                    + "Expect: 100-continue\r\nConnection: close\r\nAuthorization: Bearer " + gateway.token + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII));
            String status = reader.readLine();
            if (status != null && status.startsWith("HTTP/1.1 100")) {
                for (String header; (header = reader.readLine()) != null && !header.isEmpty();) {
                    // Tomcat may send an interim response before the controller inspects the headers.
                }
                status = reader.readLine();
            }
            assertTrue(status != null && status.startsWith("HTTP/1.1 413"), "Final response: " + status);
        }
        assertEquals(-1, RECEIVED.get());
    }

    @Test
    void chunkedOversizeIsRejectedFromActualBytes() throws Exception {
        var chunked = HttpRequest.BodyPublishers.ofInputStream(() -> zeros(10L * 1024 * 1024 + 1));
        expect(gateway.send("image/jpeg", chunked, gateway.token), 413, "MEDIA_TOO_LARGE");
        assertEquals(-1, RECEIVED.get());
    }

    @Test
    void rawVideoCanExceedBothDefaultMultipartLimits() throws Exception {
        long size = 28_000_000;
        var body = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> zeros(size)), size);
        var actual = gateway.send("video/mp4", body, gateway.token);
        assertEquals(200, actual.statusCode(), actual.body());
        assertEquals(size, RECEIVED.get());
        JsonNode value = JSON.readTree(actual.body());
        assertEquals("video", value.path("media_type").stringValue());
        assertEquals(1, value.path("video").path("agreeing_frames").intValue());
        assertEquals(2, value.path("video").path("sampled_frames").intValue());
    }

    @Test
    void recognizedErrorsKeepStatusAndCodeButDoNotLeakUpstreamDetails() throws Exception {
        assertFailure(429, "LEAF_BUSY");
        assertFailure(503, "MODEL_NOT_READY");
        assertFailure(422, "VIDEO_TOO_LONG");
        assertFailure(422, "INVALID_VIDEO");
        assertFailure(415, "MEDIA_TYPE_MISMATCH");
        assertFailure(413, "MEDIA_DIMENSIONS_EXCEEDED");
    }

    @Test
    void rejectsMalformedOrUnexpectedUpstreamResponse() throws Exception {
        response = exchange -> json(exchange, 200, "{\"media_type\":\"image\",\"unexpected\":\"secret\"}");
        expect(gateway.send("image/jpeg", HttpRequest.BodyPublishers.ofString("body"), gateway.token),
                502, "LEAF_INVALID_UPSTREAM_RESPONSE");
        response = exchange -> json(exchange, 500, "{\"code\":\"CRASH\",\"message\":\"private path\"}");
        expect(gateway.send("image/jpeg", HttpRequest.BodyPublishers.ofString("body"), gateway.token),
                502, "LEAF_INVALID_UPSTREAM_RESPONSE");
        response = exchange -> json(exchange, 200, "\"" + "a".repeat(40_000) + "\"");
        expect(gateway.send("image/jpeg", HttpRequest.BodyPublishers.ofString("body"), gateway.token),
                502, "LEAF_INVALID_UPSTREAM_RESPONSE");
    }

    @Test
    void truncatedUpstreamBodyReturns502() throws Exception {
        response = exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        };
        expect(gateway.send("image/png", HttpRequest.BodyPublishers.ofByteArray(image("png")), gateway.token),
                502, "LEAF_INVALID_UPSTREAM_RESPONSE");
    }

    @Test
    void responseBodyTimeoutReturns504AndCleansSpool() throws Exception {
        response = exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(3500);
            } catch (IOException expectedAfterTimeout) {
                // The gateway may cancel while the mock still holds the response.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        expect(gateway.send("image/png", HttpRequest.BodyPublishers.ofByteArray(image("png")), gateway.token),
                504, "LEAF_UPSTREAM_TIMEOUT");
    }

    @Test
    void unreachableServiceReturns503() throws Exception {
        int unused;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            unused = socket.getLocalPort();
        }
        var client = new LeafAnalysisClient("http://127.0.0.1:" + unused, 500, 1500);
        try (var body = zeros(1)) {
            var failure = assertThrows(LeafAnalysisException.class, () -> client.predict("image/jpeg", body, 1));
            assertEquals(503, failure.status().value());
            assertEquals("LEAF_SERVICE_UNAVAILABLE", failure.code());
        }
    }

    private static void assertFailure(int status, String code) throws Exception {
        response = exchange -> json(exchange, status,
                "{\"code\":\"" + code + "\",\"message\":\"private path in traceback\"}");
        expect(gateway.send("video/mp4", HttpRequest.BodyPublishers.ofString("body"), gateway.token), status, code);
    }

    private static void expect(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, JSON.readTree(response.body()).path("code").stringValue());
        assertTrue(!response.body().contains("private path"));
    }

    private static String prediction(boolean video) {
        var root = JSON.createObjectNode();
        root.put("media_type", video ? "video" : "image");
        root.put("model_id", "mobilenetv2-epoch20");
        root.put("class_index", 0);
        root.put("label", "Leaf 0");
        root.put("prediction_score", 0.7);
        var probabilities = root.putArray("class_probabilities");
        for (int index = 0; index < 7; index++) {
            probabilities.addObject().put("class_index", index).put("label", "Leaf " + index)
                    .put("probability", index == 0 ? 0.7 : 0.05);
        }
        root.putArray("warnings");
        root.put("notice", "Reference only.");
        if (video) {
            var detail = root.putObject("video");
            detail.put("duration_seconds", 2.0);
            detail.put("sampled_frames", 2);
            detail.put("agreeing_frames", 1);
            detail.put("agreement_ratio", 0.5);
            var frames = detail.putArray("frames");
            frames.addObject().put("timestamp_seconds", 0.4).put("class_index", 0)
                    .put("label", "Leaf 0").put("prediction_score", 0.85);
            frames.addObject().put("timestamp_seconds", 1.4).put("class_index", 1)
                    .put("label", "Leaf 1").put("prediction_score", 0.4);
        } else {
            root.putNull("video");
        }
        return JSON.writeValueAsString(root);
    }

    private static void json(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static byte[] image(String format) throws IOException {
        try (var buffer = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, buffer));
            return buffer.toByteArray();
        }
    }

    private static InputStream zeros(long length) {
        return new InputStream() {
            private long remaining = length;

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int size) {
                if (size == 0) {
                    return 0;
                }
                if (remaining <= 0) {
                    return -1;
                }
                int count = (int) Math.min(remaining, size);
                Arrays.fill(buffer, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
    }
}

@EnabledIfEnvironmentVariable(named = "LEAF_GATEWAY_SMOKE_DIR", matches = ".+")
class LeafLiveGatewayTest {
    @TempDir
    Path spool;

    @Test
    void forwardsRealTrainImageAndVideoToFastApi() throws Exception {
        Path samples = Path.of(System.getenv("LEAF_GATEWAY_SMOKE_DIR"));
        try (var gateway = new LeafTestServer("http://127.0.0.1:8003", spool, 60_000)) {
            for (String file : List.of("leaf.jpg", "leaf.png", "leaf.mp4")) {
                boolean video = file.endsWith(".mp4");
                String mime = video ? "video/mp4" : file.endsWith(".png") ? "image/png" : "image/jpeg";
                var response = gateway.send(mime, HttpRequest.BodyPublishers.ofFile(samples.resolve(file)), gateway.token);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode body = JsonMapper.builder().build().readTree(response.body());
                assertEquals("mobilenetv2-epoch20", body.path("model_id").stringValue());
                assertEquals(video ? "video" : "image", body.path("media_type").stringValue());
                assertEquals(7, body.path("class_probabilities").size());
                if (video) {
                    assertEquals(8, body.path("video").path("sampled_frames").intValue());
                    assertEquals(4, body.path("video").path("agreeing_frames").intValue());
                }
                System.out.printf("Spring + FastAPI %s: HTTP %d, %s, score %.4f%n",
                        file, response.statusCode(), body.path("label").stringValue(), body.path("prediction_score").doubleValue());
            }
        }
        try (var files = Files.list(spool)) {
            assertEquals(0, files.count());
        }
    }
}

final class LeafTestServer implements AutoCloseable {
    private final ConfigurableApplicationContext context;
    private final HttpClient http = HttpClient.newHttpClient();
    final int port;
    final String token;

    LeafTestServer(String baseUrl, Path tempDirectory, int timeoutMillis) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                        "--spring.profiles.active=leaf-gateway-test",
                        "--teasmart.leaf-ai.base-url=" + baseUrl,
                        "--teasmart.leaf-ai.request-timeout-ms=" + timeoutMillis,
                        "--teasmart.jwt.issuer=leaf-gateway-test",
                        "--teasmart.jwt.secret-base64=" + Base64.getEncoder().encodeToString(secret),
                        "--teasmart.jwt.expiration-seconds=300",
                        "--java.io.tmpdir=" + tempDirectory);
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
        token = context.getBean(JwtService.class).generateAccessToken(Config.user());
    }

    HttpResponse<String> send(String mime, HttpRequest.BodyPublisher body, String bearer) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/leaf-classifications/predict"))
                .header("Content-Type", mime).timeout(Duration.ofSeconds(70)).POST(body);
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        context.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({LeafClassificationController.class, LeafAnalysisClient.class, GlobalExceptionHandler.class,
            SecurityConfig.class, JwtConfig.class, JwtService.class, TeaSmartJwtAuthenticationConverter.class,
            JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class})
    static class Config {
        static User user() {
            User user = new User();
            user.setUserId(51L);
            return user;
        }

        @Bean
        UserService userService() {
            UserService service = mock(UserService.class);
            User user = user();
            when(service.requireActiveUser(51L)).thenReturn(user);
            when(service.authorities(user)).thenReturn(List.of("CUSTOMER"));
            return service;
        }

        @Bean
        CustomUserDetailsService customUserDetailsService() {
            return mock(CustomUserDetailsService.class);
        }
    }
}
