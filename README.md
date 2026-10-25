# LifeDesk — Trợ lý năng suất cá nhân (offline-first, Java 25 + JavaFX)

LifeDesk là ứng dụng desktop cho Windows giúp quản lý **công việc, ghi chú Markdown, tài chính cá nhân,
thói quen & mục tiêu, sức khỏe, học tập (flashcard), tệp, tìm kiếm xuyên module, trợ lý AI và automation agent** —
chạy hoàn toàn **offline, không cần tài khoản, không database server, dữ liệu nằm trên máy bạn**.

- Java 25 LTS · JavaFX 25 · Maven · Gson · SLF4J/Logback · commonmark · JUnit 5
- Persistence: JSON (ghi atomic + `.bak` + quarantine) và Markdown thuần
- Đóng gói: `jpackage` (app-image / msi)

---

## 1. Yêu cầu môi trường

| Thành phần | Phiên bản |
|---|---|
| JDK | 25 LTS (đã kiểm chứng với `25.0.4.1`; JDK 21+ thường chạy được) |
| Maven | 3.9.x |
| Hệ điều hành | Windows 10/11 x64 (dự án khai báo JavaFX classifier `win`) |
| Internet | Chỉ cần lần đầu để Maven tải dependency. Ứng dụng chạy 100% offline. |

## 2. Build & chạy

```bat
cd lifedesk

:: Chạy toàn bộ test (61 test JUnit 5)
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1
mvn test

:: Đóng gói jar + lib
mvn package -DskipTests

:: Chạy ứng dụng
java -Dstdout.encoding=UTF-8 -cp "target\lifedesk.jar;target\lib\*" com.lifedesk.app.Launcher

:: Chạy ứng dụng với thư mục dữ liệu tùy chọn
java -Dlifedesk.data=D:\MyLifeDeskData -cp "target\lifedesk.jar;target\lib\*" com.lifedesk.app.Launcher
```

Hoặc dùng Maven:

```bat
mvn compile exec:java -Dexec.mainClass=com.lifedesk.app.Launcher
```

### Smoke test tự động (không cần GUI)

```bat
:: Kiểm chứng toàn bộ tầng dịch vụ: CRUD, persistence sau reload, BigDecimal,
:: CSV dedupe, backup/restore, agent policy, rule engine... (39 check)
java -cp "target\lifedesk.jar;target\lib\*" com.lifedesk.app.SmokeMain services

:: Mở UI thật, tự chụp screenshot từng view rồi thoát
java -cp "target\classes;target\lib\*" com.lifedesk.app.SmokeMain ui target\ui-smoke.png
```

## 3. Đóng gói Windows (jpackage)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Type app-image `
  -Jdk "C:\Program Files\Java\jdk-25.0.4.1" `
  -Mvn "C:\đường\dẫn\mvn.cmd"
# Kết quả: target\jpackage\LifeDesk\LifeDesk.exe (kèm runtime JDK riêng, 165MB)

# Bộ cài MSI (cần cài WiX Toolset 3.x trên máy build):
powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Type msi ...
```

> **Lưu ý đã kiểm chứng trên máy build:** `jpackage` tạo app-image thành công, nhưng chạy
> `LifeDesk.exe` trên máy này bị **Application Control policy (Smart App Control) chặn** vì exe
> chưa ký số (`An Application Control policy has blocked this file`). Trên máy đích: cho phép
> chạy exe trong policy, hoặc ký số, hoặc chạy bằng lệnh `java -cp ... Launcher` ở mục 2
> (đã kiểm chứng chạy đúng).

## 4. Cấu hình dữ liệu

- **Config:** `%APPDATA%\LifeDesk\config\config\app-config.json`
- **Dữ liệu mặc định:** `%APPDATA%\LifeDesk\data\`
- Ghi đè bằng thuộc tính hệ thống: `-Dlifedesk.data=<đường dẫn>` (và `-Dlifedesk.config=...` cho config dir).
- Cấu trúc dữ liệu:

```
data/
├── config/            (app-config, theme ngoài data/config/themes/*.css)
├── tasks/tasks.json
├── notes/             (index.json + file .md thật, .bak và .conflict-* khi có xung đột)
├── finance/           (transactions-YYYY.json phân vùng theo năm, categories, budgets, debts, fingerprints)
├── habits/            (habits, goals, logs-YYYY.json, changelog.json)
├── health/            (entries.json, metrics.json)
├── learning/          (courses, sessions, decks, flashcards)
├── index/             (search-index.json — chỉ mục tìm kiếm, có thể dựng lại bất cứ lúc nào)
├── automation/        (workflows.json, runs.json, audit-YYYYMM.jsonl)
├── notifications/
├── security/          (policy.json)
└── backups/           (zip backup + manifest + checksum SHA-256)
```

Quy tắc an toàn dữ liệu đã cài đặt:
- Ghi **atomic**: file tạm → fsync → `ATOMIC_MOVE` thay file đích; bản cũ giữ thành `.bak`.
- File hỏng → tự thử `.bak`; hỏng cả hai → chuyển thành `.corrupt-<ts>` và **báo lỗi cho người dùng** (không im lặng).
- Mọi JSON có `schemaVersion` + `SchemaMigrator` để nâng cấp tương lai.
- Khóa single-instance bằng file lock (`data/.lifedesk.lock`) — mở 2 bản cùng dữ liệu sẽ bị từ chối.
- Backup/restore kèm manifest + checksum SHA-256; restore **không xóa** file dư mà chuyển vào `backups/orphaned-*`.

## 5. AI trong LifeDesk (Ollama / Kilo / Rule-based)

Vào **Cài đặt → AI**:

- **Tắt AI hoàn toàn**: bỏ tick "Bật tính năng AI" — mọi module khác vẫn chạy bình thường.
- **Provider**:
  - `rule-based` — engine quy tắc offline, KHÔNG cần LLM, trả lời từ dữ liệu thật
    (việc hôm nay/quá hạn, tạo task, tổng hợp tài chính, tóm tắt ghi chú dạng rút trích, tìm kiếm, gợi ý...).
    Khi không hiểu, nó nói rõ là nằm ngoài khả năng — không giả vờ suy luận ngôn ngữ tự nhiên.
  - `ollama` — điền endpoint (mặc định `http://localhost:11434`), bấm **"Lấy danh sách model"** để fetch
    model đã cài, tìm kiếm theo tên, chọn model dùng. Có nút **tải model** (pull) — chỉ chạy khi bạn
    bấm và xác nhận; ứng dụng không bao giờ tự tải model.
  - `kilo` — điền Base URL OpenAI-compatible (mặc định `https://api.kilo.ai/v1`, có thể đổi path) + API key
    (lưu cục bộ), bấm kiểm tra kết nối, fetch model (`/models`), tìm kiếm và chọn model.
- Có thể cho phép LLM đọc tổng quan dữ liệu của bạn (bật/tắt riêng) — dữ liệu chỉ rời máy khi bạn chọn provider cloud.

## 6. Automation Agent & bảo mật

- Agent **chỉ** hành động qua Tool Registry (11 tool: task.create/list/update_status/search, note.create/search/append/delete,
  file.search, habit.log, dashboard.summary) — **không có tool chạy shell**, tool lạ bị policy từ chối.
- Phân quyền 3 mức: `READ` (chạy trong phạm vi được cấp), `WRITE` (cần phê duyệt kế hoạch trước khi chạy),
  `DESTRUCTIVE` (cần xác nhận trực tiếp từng bước lúc đang chạy; không có UI/handler → tự động từ chối).
- Chống vòng lặp vô hạn: giới hạn số bước (mặc định 25), tổng thời gian (300s), timeout mỗi bước (60s),
  retry có giới hạn (2), phát hiện lặp (tool+params) quá 3 lần.
- Trạng thái run: `PENDING / RUNNING / WAITING_APPROVAL / COMPLETED / FAILED / CANCELLED`.
- Audit log JSONL theo tháng tại `data/automation/audit-YYYYMM.jsonl` — tham số được sanitize
  (che api_key/token/password, cắt chuỗi dài); **không có code path nào tắt audit**.
- `file.search` chỉ tìm trong thư mục người dùng đã cấp quyền (Cài đặt → Quyền truy cập tệp), có PathGuard chống path traversal.
- File manager chỉ đọc: không có thao tác xóa/đổi tên/di chuyển; mở file bằng app mặc định của OS.

## 7. Backup / Restore / Kiểm tra toàn vẹn

Vào **Cài đặt → Dữ liệu & Backup**:

- **Tạo bản sao lưu ngay** → zip trong `data/backups/` kèm `manifest.json` (SHA-256 từng file).
- **Khôi phục** → kiểm tra checksum trước khi ghi; file hiện tại không có trong backup được chuyển sang
  `backups/orphaned-<ts>/` (không xóa).
- **Kiểm tra toàn vẹn** → quét mọi file JSON, báo danh sách file hỏng (rỗng = khỏe).
- Khuyến nghị: backup trước khi nâng cấp phiên bản.

## 8. Hướng dẫn nhanh trong app

- **Công việc**: tạo task (title, ưu tiên, hạn, giờ, nhóm, tag, checklist, lặp lại DAILY/WEEKLY/MONTHLY),
  hoàn thành task lặp sẽ tự sinh phiên tiếp theo; chuyển qua lại List ⇄ Board.
- **Ghi chú**: markdown editor + preview (WebView, link `[[Ghi chú]]` click được, link ngoài cần xác nhận),
  tự động lưu sau 1.5s, phát hiện file bị sửa ngoài app và giữ bản đó thành `-conflict-*.md`.
- **Tài chính**: giao dịch BigDecimal, ngân sách theo tháng/danh mục, phải thu/phải trả, báo cáo 6 tháng + pie danh mục, xuất/nhập CSV (tự chống trùng).
- **Thói quen & Mục tiêu**: lưới 14 ngày bấm điểm danh, chuỗi ngày liên tục, tổng kết tuần; điều chỉnh quá khứ bắt buộc nhập lý do (ghi nhật ký thay đổi).
- **Sức khỏe**: ngủ/vận động/cân nặng + chỉ số tự chọn, biểu đồ xu hướng 30 ngày. Chỉ ghi nhận — **không tư vấn y tế**.
- **Học tập**: khóa học + buổi học, flashcard Leitner (box 1–6, lịch ôn 1/2/4/7/15/30 ngày), phiên ôn tập có Đúng/Sai.
- **Tìm kiếm**: ô trên topbar (Enter) — kết quả nhóm theo module, double-click mở bản ghi.

## 9. Cấu trúc mã nguồn

```
src/main/java/com/lifedesk/
├── app/        Launcher, LifeDeskApp, AppContext, SingleInstanceLock, ReminderScheduler, SmokeMain
├── common/     exception, validation, CSV, fingerprint, executors, TextSearch
├── config/     AppConfig, AiConfig, ConfigService
├── storage/    JsonStore (atomic + .bak + migration), GsonFactory, StorageIssueLog
├── theme/      ThemeManager (5 theme + theme ngoài)
├── tasks/ notes/ finance/ habits/ health/ learning/ files/ search/ notifications/
├── markdown/   MarkdownService (escapeHtml + URL sanitizer), MarkdownPreviewPane
├── ai/         AiProvider, RuleBasedEngine, OllamaProvider, OpenAiCompatibleProvider (Kilo), AiManager
├── automation/ Tool, ToolRegistry, AgentExecutor, AgentService, WorkflowScheduler, tools/*
├── security/   SecurityPolicy, PathGuard, AuditLogger
├── backup/     BackupService (zip + manifest + verify + restore + integrity)
└── ui/         MainWindow, views/* (12 view), components/*
src/test/java/  61 test JUnit 5 (storage, tasks, finance, notes, habits, markdown, agent policy, backup, integration)
```

## 10. Giấy phép & ghi chú

- JavaFX (GPLv2+Classpath), Gson (Apache 2.0), commonmark (BSD 2-clause), SLF4J/Logback (MIT/EPL),
  JUnit (EPL) — đều dùng qua Maven Central.
- Dữ liệu người dùng chỉ nằm trong thư mục dữ liệu bạn chọn. Không có telemetry, không gọi ra Internet
  trừ khi bạn tự cấu hình provider AI cloud (Kilo) và gửi câu hỏi.
