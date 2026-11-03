package com.lifedesk.edusmart;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle owner for the embedded EduSmart backend (Spring Boot jar serving the
 * EduSmart Next.js UI). Runs as a child JVM bound to loopback on a private port;
 * LifeDesk's WebView reaches it through an iframe in the "Học tập (EduSmart)"
 * module. Data (H2 db + uploads) lives under <dataDir>/edusmart.
 */
public final class EduSmartBackend {
    private static final Logger LOG = LoggerFactory.getLogger(EduSmartBackend.class);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(90);

    public enum State { MISSING, STOPPED, STARTING, RUNNING, ERROR }

    private final Path dataDir;
    private final Path workDir;

    private volatile State state = State.STOPPED;
    private volatile String error = "";
    private volatile int port = 0;
    private volatile Process proc;

    public EduSmartBackend(Path dataDir) {
        this.dataDir = dataDir;
        this.workDir = dataDir.resolve("edusmart");
        if (findJar() == null) {
            state = State.MISSING;
            error = "Không tìm thấy edusmart.jar (đặt tại vendor/edusmart.jar gần thư mục LifeDesk).";
        }
    }

    private Path findJar() {
        String prop = System.getProperty("lifedesk.edusmart.jar", "");
        if (!prop.isBlank() && Files.isRegularFile(Path.of(prop))) {
            return Path.of(prop);
        }
        Path userDir = Path.of(System.getProperty("user.dir", "."));
        for (String rel : List.of("vendor/edusmart.jar", "edusmart.jar")) {
            Path candidate = userDir.resolve(rel);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public synchronized JsonObject status() {
        JsonObject out = new JsonObject();
        Process live = proc;
        if (state == State.RUNNING && (live == null || !live.isAlive())) {
            state = State.ERROR;
            error = live == null ? "Tiến trình EduSmart không còn chạy."
                    : "Tiến trình EduSmart đã thoát với mã " + live.exitValue() + ".";
        }
        out.addProperty("state", state.name());
        out.addProperty("port", port);
        out.addProperty("error", error);
        out.addProperty("url", state == State.RUNNING ? "http://127.0.0.1:" + port + "/" : "");
        return out;
    }

    /** Returns immediately: STOPPED/ERROR/MISSING start asynchronously; RUNNING is a no-op. */
    public synchronized void startAsync() {
        if (state == State.RUNNING || state == State.STARTING || state == State.MISSING) {
            return;
        }
        state = State.STARTING;
        error = "";
        Thread boot = new Thread(this::boot, "lifedesk-edusmart");
        boot.setDaemon(true);
        boot.start();
    }

    private void boot() {
        Path jar = findJar();
        if (jar == null) {
            state = State.MISSING;
            error = "Không tìm thấy edusmart.jar (đặt tại vendor/edusmart.jar gần thư mục LifeDesk).";
            return;
        }
        try {
            Files.createDirectories(workDir);
            int free = freePort();
            List<String> cmd = new ArrayList<>(List.of(
                    javaBinary(),
                    "-Xms64m", "-Xmx384m",
                    "-jar", jar.toAbsolutePath().toString(),
                    "--server.port=" + free,
                    "--server.address=127.0.0.1"));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(workDir.resolve("edusmart.log").toFile()));
            pb.redirectErrorStream(true);
            pb.environment().put("SPRING_PROFILES_ACTIVE", "h2");
            pb.environment().put("JWT_SECRET", jwtSecret());
            // App desktop: học sinh không phải đăng nhập, EduSmart tự lấy JWT cục bộ
            pb.environment().put("LOCAL_LOGIN_ENABLED", "true");
            inheritOptionalConfig(pb.environment(), jar, "AI_API_KEY", "ai-key.txt");
            inheritOptionalConfig(pb.environment(), jar, "AI_BASE_URL", "ai-base-url.txt");
            inheritOptionalConfig(pb.environment(), jar, "AI_MODEL", "ai-model.txt");
            inheritOptionalConfig(pb.environment(), jar, "GOOGLE_CLIENT_ID", "google-client-id.txt");
            proc = pb.start();
            port = free;
            if (waitUntilReady(free)) {
                state = State.RUNNING;
                LOG.info("EduSmart backend ready on http://127.0.0.1:{}", free);
            } else {
                state = State.ERROR;
                error = "EduSmart không khởi động xong trong " + START_TIMEOUT.toSeconds()
                        + " giây. Xem log: " + workDir.resolve("edusmart.log");
            }
        } catch (Exception e) {
            LOG.error("EduSmart start failed", e);
            state = State.ERROR;
            error = "Lỗi khởi động EduSmart: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** Stable per-install secret so H2-backed sessions survive restarts. */
    private String jwtSecret() throws IOException {
        Path secretFile = workDir.resolve("jwt-secret.txt");
        if (Files.isRegularFile(secretFile)) {
            String saved = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
            if (!saved.isBlank()) {
                return saved;
            }
        }
        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        StringBuilder hex = new StringBuilder();
        for (byte b : rnd) {
            hex.append(String.format("%02x", b));
        }
        Files.writeString(secretFile, hex.toString(), StandardCharsets.UTF_8);
        return hex.toString();
    }

    /** Lets power users supply ai-key.txt / ai-base-url.txt / ai-model.txt / google-client-id.txt next to the jar or in the work dir. */
    private void inheritOptionalConfig(java.util.Map<String, String> env, Path jar, String envKey, String fileName) {
        for (Path dir : List.of(jar.toAbsolutePath().getParent(), workDir)) {
            if (dir == null) {
                continue;
            }
            Path file = dir.resolve(fileName);
            if (Files.isRegularFile(file)) {
                try {
                    String value = Files.readString(file, StandardCharsets.UTF_8).trim();
                    if (!value.isBlank()) {
                        env.put(envKey, value);
                        return;
                    }
                } catch (IOException ignored) {
                    // treat as absent
                }
            }
        }
    }

    private boolean waitUntilReady(int localPort) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Process live = proc;
            if (live != null && !live.isAlive()) {
                error = "Tiến trình EduSmart đã thoát với mã " + live.exitValue()
                        + ". Xem log: " + workDir.resolve("edusmart.log");
                return false;
            }
            try {
                HttpResponse<Void> res = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localPort + "/"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() >= 200 && res.statusCode() < 500) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public synchronized void stop() {
        Process live = proc;
        proc = null;
        port = 0;
        if (state == State.MISSING) {
            return;
        }
        state = State.STOPPED;
        error = "";
        if (live != null && live.isAlive()) {
            live.destroy();
            try {
                if (!live.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    live.destroyForcibly();
                }
            } catch (InterruptedException e) {
                live.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            LOG.info("EduSmart backend stopped");
        }
    }

    public Path workDir() {
        return workDir;
    }
}
