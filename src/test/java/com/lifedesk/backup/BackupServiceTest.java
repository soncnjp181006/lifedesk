package com.lifedesk.backup;

import com.lifedesk.app.AppContext;
import com.lifedesk.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {

    @TempDir
    Path dir;

    private AppContext context() {
        AppConfig config = new AppConfig();
        config.dataDir = dir.toString();
        config.remindersEnabled = false;
        return new AppContext(dir, config);
    }

    @Test
    void backupVerifyRestoreRoundTrip() throws Exception {
        AppContext ctx = context();
        ctx.tasks().create("Việc trước backup", null, null, LocalDate.now(), null, "", null, null);
        ctx.notes().create("Ghi chú trước backup", "", "nội dung", null);

        BackupService backup = new BackupService();
        Path zip = backup.backup(dir, dir.resolve("backups"));
        assertTrue(Files.exists(zip));
        assertTrue(backup.verify(zip).ok(), "fresh backup must verify cleanly");
        assertTrue(backup.integrityCheck(dir).isEmpty());

        // Corrupt the tasks store, then restore must repair it
        Files.writeString(dir.resolve("tasks/tasks.json"), "{corrupt!");
        assertFalse(backup.integrityCheck(dir).isEmpty());

        var report = backup.restore(zip, dir);
        assertTrue(report.problems().isEmpty());
        assertTrue(report.restored() > 0);
        assertTrue(backup.integrityCheck(dir).isEmpty());

        // Data content is back
        var reloaded = context();
        assertTrue(reloaded.tasks().search("Việc trước backup", null, null).size() == 1);
        assertTrue(reloaded.notes().findByTitle("Ghi chú trước backup").isPresent());
        reloaded.shutdown();
    }

    @Test
    void verifyDetectsTampering() throws Exception {
        AppContext ctx = context();
        ctx.finance().addTransaction(LocalDate.now(), com.lifedesk.finance.TxType.EXPENSE,
                new BigDecimal("1000"), ctx.finance().categories(com.lifedesk.finance.TxType.EXPENSE).get(0).id,
                com.lifedesk.finance.PaymentMethod.CASH, "x", null, null, false);
        BackupService backup = new BackupService();
        Path zip = backup.backup(dir, dir.resolve("backups"));

        // Tamper: rewrite the zip with modified content (simulate by rebuilding a zip entry)
        Path tampered = dir.resolve("tampered.zip");
        try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip));
             var zout = new java.util.zip.ZipOutputStream(Files.newOutputStream(tampered))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (e.getName().startsWith("finance/")) {
                    data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("1000", "9999").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zout.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
        }
        assertFalse(backup.verify(tampered).ok(), "tampered backup must fail checksum verification");
    }

    @Test
    void restoreRejectsInvalidZipWithoutTouchingData() throws Exception {
        AppContext ctx = context();
        ctx.tasks().create("Nguyên vẹn", null, null, null, null, "", null, null);
        BackupService backup = new BackupService();
        Path badZip = dir.resolve("bad.zip");
        Files.writeString(badZip, "PK fake not really a zip");

        assertThrows(RuntimeException.class, () -> backup.restore(badZip, dir));
        // data untouched
        var reloaded = context();
        assertEquals(1, reloaded.tasks().search("Nguyên vẹn", null, null).size());
        reloaded.shutdown();
    }

    @Test
    void nonJsonFilesIgnoredByIntegrityCheckButBackedUp() throws Exception {
        AppContext ctx = context();
        ctx.notes().create("Md file", "", "# nội dung md", null);
        BackupService backup = new BackupService();
        assertTrue(backup.integrityCheck(dir).isEmpty());
        Path zip = backup.backup(dir, dir.resolve("backups"));
        var verify = backup.verify(zip);
        assertTrue(verify.ok());
        // the markdown note file must be listed inside the zip manifest
        String manifestJson = null;
        try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals("manifest.json")) {
                    manifestJson = new String(zin.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertNotNull(manifestJson);
        assertTrue(manifestJson.contains("notes/"), "manifest must include note files");
    }
}
