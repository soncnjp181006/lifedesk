package com.lifedesk.notes;

import com.lifedesk.common.Fingerprint;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NotesServiceTest {

    @TempDir
    Path dir;

    private NotesService service() {
        return new NotesService(new JsonStore(dir, new StorageIssueLog()));
    }

    @Test
    void createReadUpdateDelete() {
        NotesService svc = service();
        NoteMeta n = svc.create("Ghi chú A", "work", "# A\n\nnội dung", Set.of("tag1"));
        assertEquals("# A\n\nnội dung", svc.readContent(n.id));

        SaveResult r = svc.saveContent(n.id, "# A v2", Fingerprint.sha256("# A\n\nnội dung"));
        assertEquals(SaveResult.SAVED, r);
        assertEquals("# A v2", svc.readContent(n.id));
        // content persisted as real markdown file on disk
        assertTrue(Files.exists(dir.resolve(n.contentFile)));
        assertTrue(n.contentFile.endsWith(".md"));

        svc.delete(n.id);
        assertFalse(Files.exists(dir.resolve(n.contentFile)));
        assertFalse(svc.list().stream().anyMatch(x -> x.id.equals(n.id)));
    }

    @Test
    void externalModificationProducesConflictCopyInsteadOfOverwrite() throws Exception {
        NotesService svc = service();
        NoteMeta n = svc.create("Xung đột", "", "bản gốc", Set.of());
        String saved = svc.readContent(n.id);

        // Someone edits the file outside LifeDesk
        Path file = dir.resolve(n.contentFile);
        Files.writeString(file, "bản sửa bên ngoài");

        // The editor still holds `saved`; saving must preserve the external version.
        SaveResult result = svc.saveContent(n.id, "bản trong app", saved);
        assertEquals(SaveResult.SAVED_WITH_CONFLICT_COPY, result);
        assertEquals("bản trong app", svc.readContent(n.id));
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("-conflict-")),
                    "external version must be preserved as a -conflict file");
        }
    }

    @Test
    void titleChangeRenamesFileKeepsId() {
        NotesService svc = service();
        NoteMeta n = svc.create("Tên cũ", "", "abc", Set.of());
        String oldFile = n.contentFile;
        svc.updateMeta(n, "Tên mới", null, null, false);
        assertEquals(n.id, svc.get(n.id).id, "id must not change when title changes");
        assertFalse(Files.exists(dir.resolve(oldFile)));
        assertTrue(Files.exists(dir.resolve(n.contentFile)));
        assertEquals("Tên mới", svc.get(n.id).title);
        assertEquals("abc", svc.readContent(n.id));
    }

    @Test
    void folderMoveRelocatesContentFile() {
        NotesService svc = service();
        NoteMeta n = svc.create("Chuyển thư mục", "", "nd", Set.of());
        svc.updateMeta(n, null, "projects/2026", null, false);
        assertTrue(Files.exists(dir.resolve(n.contentFile)));
        assertTrue(n.contentFile.startsWith("notes/projects/2026/"));
        assertEquals("nd", svc.readContent(n.id));
    }

    @Test
    void searchFindsContentAndTags() {
        NotesService svc = service();
        svc.create("Java cơ bản", "edu", "# Java\n\nhọc lập trình java", Set.of());
        svc.create("Nấu ăn", "", "cách phở bò", Set.of());
        assertFalse(svc.search("lap trinh").isEmpty());
        assertFalse(svc.search("phở").isEmpty());
        assertTrue(svc.search("khôngtồnai").isEmpty());
    }

    @Test
    void wikiLinksAndTagsParsed() {
        assertEquals(Set.of("Ghi chú khác", "Tiếp"), NoteLinkParser.wikiLinks("[[Ghi chú khác]] và [[Tiếp]]"));
        assertEquals(Set.of("work", "urgent_1"), NoteLinkParser.tags("đang #work và #urgent_1!"));
    }

    @Test
    void indexPersistsAcrossReload() {
        NotesService svc = service();
        svc.create("Lưu bền", "", "nội dung lưu bền", Set.of());
        NotesService reloaded = new NotesService(new JsonStore(dir, new StorageIssueLog()));
        assertTrue(reloaded.findByTitle("Lưu bền").isPresent());
    }
}
