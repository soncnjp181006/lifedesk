package com.lifedesk.storage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonStoreTest {

    @TempDir
    Path dir;

    private SchemaMigrator v1() {
        return SchemaMigrator.none(1);
    }

    @Test
    void writeThenReadRoundTrip() {
        JsonStore store = new JsonStore(dir, new StorageIssueLog());
        JsonObject data = new JsonObject();
        data.addProperty("name", " LifeDesk ✓ tiếng Việt ");
        data.addProperty("n", 42);
        store.write("x/test.json", 1, data);

        JsonStore.StoredDoc doc = store.read("x/test.json", v1());
        assertNotNull(doc);
        assertEquals(1, doc.schemaVersion());
        assertEquals(" LifeDesk ✓ tiếng Việt ", doc.data().get("name").getAsString());
        assertEquals(42, doc.data().get("n").getAsInt());
    }

    @Test
    void readMissingReturnsNull() {
        JsonStore store = new JsonStore(dir, new StorageIssueLog());
        assertNull(store.read("nope/missing.json", v1()));
    }

    @Test
    void corruptPrimaryRecoversFromBak() throws Exception {
        JsonStore store = new JsonStore(dir, new StorageIssueLog());
        JsonObject data = new JsonObject();
        data.addProperty("v", "good");
        store.write("x/keep.json", 1, data);

        // Simulate corruption of the primary file; the .bak from the previous write remains valid
        // (write once more to create a .bak, then corrupt the primary).
        JsonObject data2 = new JsonObject();
        data2.addProperty("v", "second");
        store.write("x/keep.json", 1, data2);
        Files.writeString(store.resolve("x/keep.json"), "{ this is not json");

        JsonStore.StoredDoc doc = store.read("x/keep.json", v1());
        assertNotNull(doc);
        assertEquals("second", doc.data().get("v").getAsString());
    }

    @Test
    void fullyCorruptFileIsQuarantinedAndReported() throws Exception {
        StorageIssueLog issues = new StorageIssueLog();
        JsonStore store = new JsonStore(dir, issues);
        Files.createDirectories(store.resolve("x"));
        Files.writeString(store.resolve("x/broken.json"), "not-json-at-all");

        assertNull(store.read("x/broken.json", v1()));
        assertTrue(issues.hasErrors());
        boolean quarantined = false;
        try (var files = Files.list(store.resolve("x"))) {
            quarantined = files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-"));
        }
        assertTrue(quarantined, "corrupt file must be quarantined, never silently dropped");
    }

    @Test
    void textWriteReadAndDelete() {
        JsonStore store = new JsonStore(dir, new StorageIssueLog());
        store.writeText("notes/a.md", "# Tiêu đề\n\nNội dung **markdown**");
        assertEquals("# Tiêu đề\n\nNội dung **markdown**", store.readText("notes/a.md"));
        store.delete("notes/a.md");
        assertNull(store.readText("notes/a.md"));
    }

    @Test
    void pathTraversalRejected() {
        JsonStore store = new JsonStore(dir, new StorageIssueLog());
        assertThrows(RuntimeException.class, () -> store.resolve("../../etc/passwd"));
    }

    @Test
    void concurrentWritesKeepFileValid() throws Exception {
        JsonStore store = new JsonStore(dir, new StorageIssueLog());
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                for (int w = 0; w < 20; w++) {
                    JsonObject o = new JsonObject();
                    o.addProperty("writer", id);
                    o.addProperty("round", w);
                    store.write("x/concurrent.json", 1, o);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(10000);
        }
        JsonStore.StoredDoc doc = store.read("x/concurrent.json", v1());
        assertNotNull(doc, "file must remain parseable after concurrent writers");
        List<String> content = Files.readAllLines(store.resolve("x/concurrent.json"));
        assertFalse(content.isEmpty());
    }
}
