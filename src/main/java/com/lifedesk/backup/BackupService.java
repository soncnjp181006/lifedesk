package com.lifedesk.backup;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.lifedesk.common.AppException;
import com.lifedesk.common.Fingerprint;
import com.lifedesk.common.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup, restore and integrity checking for the data directory.
 *
 * A backup is a ZIP containing every data file plus a manifest.json with a
 * SHA-256 checksum per file. Restore verifies checksums before touching the
 * data directory; files on disk that are not in the backup are moved aside
 * (data/backups/orphaned-*) instead of deleted, so nothing is destroyed.
 */
public class BackupService {
    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    /** Shared Gson (with java.time adapters) — a plain GsonBuilder cannot serialize Instant on Java 25. */
    private static final Gson GSON = com.lifedesk.storage.GsonFactory.create();

    /** Run a backup of {@code dataDir} into {@code backupDir}; returns the zip path. */
    public Path backup(Path dataDir, Path backupDir) {
        try {
            Files.createDirectories(backupDir);
            String name = "lifedesk-backup-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
            Path target = backupDir.resolve(name);
            Path tmp = backupDir.resolve(name + ".tmp");

            List<Path> files;
            try (Stream<Path> walk = Files.walk(dataDir)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> !isExcluded(dataDir, p))
                        .sorted(Comparator.comparing(dataDir::relativize))
                        .toList();
            }

            Manifest manifest = new Manifest();
            manifest.createdAt = Instant.now();
            manifest.appVersion = "1.0.0";
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                for (Path file : files) {
                    String rel = dataDir.relativize(file).toString().replace('\\', '/');
                    byte[] bytes = Files.readAllBytes(file);
                    zip.putNextEntry(new ZipEntry(rel));
                    zip.write(bytes);
                    zip.closeEntry();
                    manifest.files.add(new Entry(rel, bytes.length, Fingerprint.sha256(
                            new String(bytes, StandardCharsets.UTF_8) // content hash (text files)
                    )));
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.move(tmp, target);
            LOG.info("Backup created: {} ({} files)", target.getFileName(), files.size());
            pruneOldBackups(backupDir, 20);
            return target;
        } catch (IOException e) {
            throw new StorageException("Tạo bản sao lưu thất bại: " + e.getMessage(), e);
        }
    }

    private boolean isExcluded(Path dataDir, Path p) {
        Path rel = dataDir.relativize(p);
        String name = p.getFileName().toString();
        if (name.equals(".lifedesk.lock") || name.endsWith(".tmp")) {
            return true;
        }
        return rel.startsWith("backups");
    }

    private void pruneOldBackups(Path backupDir, int keep) throws IOException {
        List<Path> zips;
        try (Stream<Path> s = Files.list(backupDir)) {
            zips = s.filter(p -> p.getFileName().toString().startsWith("lifedesk-backup-")
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (int i = keep; i < zips.size(); i++) {
            Files.deleteIfExists(zips.get(i));
            LOG.debug("Pruned old backup {}", zips.get(i).getFileName());
        }
    }

    public record VerifyReport(boolean ok, List<String> problems) {
    }

    /** Full zip content read once: entry name -> bytes, plus the parsed manifest. */
    private record ZipContent(java.util.LinkedHashMap<String, byte[]> entries, Manifest manifest) {
    }

    private ZipContent readZip(Path zip) throws IOException {
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        Manifest manifest = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (entry.getName().equals("manifest.json")) {
                    manifest = GSON.fromJson(new String(data, StandardCharsets.UTF_8), Manifest.class);
                } else {
                    entries.put(entry.getName(), data);
                }
            }
        }
        if (manifest == null) {
            throw new IOException("Thiếu manifest.json trong bản sao lưu");
        }
        return new ZipContent(entries, manifest);
    }

    /** Verify a backup zip: manifest present, entry count matches, checksums match. */
    public VerifyReport verify(Path zip) {
        List<String> problems = new ArrayList<>();
        try {
            ZipContent content = readZip(zip);
            Manifest manifest = content.manifest();
            for (Entry expected : manifest.files) {
                byte[] data = content.entries().get(expected.path);
                if (data == null) {
                    problems.add("Thiếu file trong zip: " + expected.path);
                    continue;
                }
                String hash = Fingerprint.sha256(new String(data, StandardCharsets.UTF_8));
                if (!hash.equals(expected.sha256)) {
                    problems.add("Checksum không khớp: " + expected.path);
                }
            }
            if (content.entries().size() != manifest.files.size()) {
                problems.add("Số file trong zip (" + content.entries().size()
                        + ") khác manifest (" + manifest.files.size() + ")");
            }
        } catch (IOException e) {
            problems.add("Không đọc được file zip: " + e.getMessage());
        }
        return new VerifyReport(problems.isEmpty(), problems);
    }

    /** Restore report describing what was replaced / moved aside / problems. */
    public record RestoreReport(int restored, List<String> orphaned, List<String> problems) {
    }

    /**
     * Restore data files from a verified backup zip into {@code dataDir}.
     * Files on disk not covered by the backup are moved to orphaned-<ts>/.
     */
    public RestoreReport restore(Path zip, Path dataDir) {
        VerifyReport verify = verify(zip);
        if (!verify.ok()) {
            throw new AppException("Bản sao lưu không hợp lệ, đã hủy khôi phục:\n- "
                    + String.join("\n- ", verify.problems()));
        }
        List<String> orphaned = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int restored = 0;
        try {
            ZipContent content = readZip(zip);

            // Move current files not present in backup aside (never hard-delete).
            Path orphanDir = dataDir.resolve("backups").resolve("orphaned-" + System.currentTimeMillis());
            try (Stream<Path> walk = Files.walk(dataDir)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String rel = dataDir.relativize(p).toString().replace('\\', '/');
                    if (rel.startsWith("backups/") || rel.equals(".lifedesk.lock")) {
                        continue;
                    }
                    boolean inBackup = content.manifest().files.stream().anyMatch(e -> e.path.equals(rel));
                    if (!inBackup) {
                        Files.createDirectories(orphanDir.resolve(rel).getParent());
                        Files.move(p, orphanDir.resolve(rel));
                        orphaned.add(rel);
                    }
                }
            }
            // Extract backup entries atomically (tmp + move).
            for (java.util.Map.Entry<String, byte[]> e : content.entries().entrySet()) {
                Path target = dataDir.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".restore-tmp");
                Files.write(tmp, e.getValue());
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                restored++;
            }
        } catch (IOException e) {
            problems.add("Lỗi khôi phục: " + e.getMessage());
        }
        LOG.info("Restore finished: {} files restored, {} orphaned, {} problems", restored, orphaned.size(), problems.size());
        return new RestoreReport(restored, orphaned, problems);
    }

    /** Scan all .json files in the data dir; returns a list of corrupt files (empty = healthy). */
    public List<String> integrityCheck(Path dataDir) {
        List<String> corrupt = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dataDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try (java.io.BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                            JsonParser.parseReader(r);
                        } catch (Exception e) {
                            corrupt.add(dataDir.relativize(p).toString());
                        }
                    });
        } catch (IOException e) {
            corrupt.add("Không quét được thư mục dữ liệu: " + e.getMessage());
        }
        return corrupt;
    }

    public static final class Manifest {
        public Instant createdAt;
        public String appVersion;
        public List<Entry> files = new ArrayList<>();
    }

    public static final class Entry {
        public String path;
        public long size;
        public String sha256;

        public Entry() {
        }

        public Entry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
