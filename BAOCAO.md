# LifeDesk — Báo cáo phân tích tính năng & hiện trạng kỹ thuật

> Phạm vi: toàn bộ `src/main/java` (**130 file `.java`, 23 package**), `src/test/java` (11 class test),
> `src/main/resources` (5 palette CSS + `base.css` + 48 icon SVG), `scripts/`, `docs/TESTING.md`, `README.md`, `pom.xml`.
> Tổng số file trong `src/` là 196. (Số liệu cập nhật 2026-10-27, sau đợt rewrite UI thêm `IconView` + bộ icon SVG.)
> Phương pháp: đọc tĩnh mã nguồn, đối chiếu tài liệu; các số liệu JUnit/test dẫn chiếu `docs/TESTING.md`.
> Riêng mục **II.2 "Code chạy như thế nào"** được kiểm chứng bằng **thực thi thật ngày 2026-10-27**:
> compile 130 file bằng `javac --release 25`, chạy app bằng `java -cp`, `SmokeMain services` 40/40 PASS.
> Máy phân tích **không có Maven**, nên các lệnh `mvn` trong báo cáo là đường dẫn chuẩn của dự án
> (theo `pom.xml` và `scripts/`), không phải lệnh đã chạy trên máy này.
> Đường dẫn tham chiếu tính từ gốc dự án.

---

## Mục lục

- [I. Tổng quan dự án & công nghệ](#i-tổng-quan-dự-án--công-nghệ)
- [II. Kiến trúc](#ii-kiến-trúc)
  - [II.1 Luồng khởi động](#ii1-luồng-khởi-động)
  - [II.2 Code chạy như thế nào — build, chạy, và một hành động đi qua các tầng](#ii2-code-chạy-như-thế-nào--build-chạy-và-một-hành-động-đi-qua-các-tầng)
  - [II.3 Composition root & tầng service](#ii3-composition-root--tầng-service)
  - [II.4 Tầng lưu trữ — `storage/JsonStore.java`](#ii4-tầng-lưu-trữ--storagejsonstorejava)
  - [II.5 Bố cục dữ liệu trên đĩa](#ii5-bố-cục-dữ-liệu-trên-đĩa)
  - [II.6 Hệ thống theme](#ii6-hệ-thống-theme)
  - [II.7 Config & common](#ii7-config--common)
- [III. Chi tiết từng module tính năng](#iii-chi-tiết-từng-module-tính-năng)
- [IV. Hệ thống phụ trợ](#iv-hệ-thống-phụ-trợ)
- [V. Test & độ phủ](#v-test--độ-phủ)
- [VI. Đánh giá hiện trạng & khoảng trống tài liệu ↔ code](#vi-đánh-giá-hiện-trạng--khoảng-trống-tài-liệu--code)
- [VII. Kết luận & khuyến nghị](#vii-kết-luận--khuyến-nghị)

<div style="page-break-before: always;"></div>

## I. Tổng quan dự án & công nghệ

**LifeDesk** là bộ ứng dụng desktop năng suất cá nhân **offline-first**, không tài khoản, không database server,
toàn bộ dữ liệu nằm trên máy người dùng dưới dạng JSON + Markdown thuần.

| Hạng mục | Chi tiết |
|---|---|
| Ngôn ngữ / runtime | Java 25 LTS (`pom.xml` → `maven.compiler.release=25`) |
| GUI | JavaFX 25, khai báo classifier `win` cho **mọi** module JavaFX → mục tiêu Windows-only (comment giải thích tại `pom.xml:27-30`) |
| Build | Maven, artifact `com.lifedesk:lifedesk:1.0.0`, packaging `jar` |
| Persistence | Gson 2.11.0 (envelope JSON) + file `.md` thật cho ghi chú |
| Markdown | commonmark 0.24.0 + extension GFM tables + task-list-items |
| Logging | SLF4J 2.0.16 + Logback 1.5.12 (`src/main/resources/logback.xml`) |
| Test | JUnit 5.10.2 — **73** method `@Test` (đếm trực tiếp trong `src/test/java`) |
| Entry point | `com.lifedesk.app.Launcher` (`pom.xml` → `main.class`) |
| Đóng gói | `jpackage` app-image / MSI qua `scripts/package.ps1` |

**Dependency plugin build**: compiler 3.13.0, surefire 3.2.5 (UTF-8), jar 3.4.1 (Main-Class + `Class-Path` trỏ `lib/`),
dependency 3.6.1 (copy runtime jar sang `target/lib` để jpackage), exec 3.5.0 (chạy `SmokeMain`).
**Không có** `javafx-maven-plugin` — app chạy qua classpath trực tiếp.

**Điểm đáng chú ý về mặt lựa chọn công nghệ**: dự án **không** dùng framework DI, **không** dùng library HTTP bên thứ ba
(chỉ `java.net.http` của JDK), **không** có MCP, **không** có hotkey toàn cục (jnativehook), **không** có ảnh/icon/font
đóng gói — toàn bộ icon là emoji text inline. `javafx-media` được khai báo trong `pom.xml` nhưng không có usage nào
trong mã nguồn; nhiều khả năng giữ lại để runtime bundle của jpackage đầy đủ.

---

<div style="page-break-before: always;"></div>

## II. Kiến trúc

### II.1 Luồng khởi động

```text
Launcher.main
  └─ đặt sysprop lifedesk.log.dir  → %APPDATA%/LifeDesk/logs (fallback ~/.lifedesk/logs)
     (phải đặt TRƯỚC khi logback khởi tạo — src/main/java/com/lifedesk/app/Launcher.java:5)
  └─ LifeDeskApp.main
LifeDeskApp.init()                       app/LifeDeskApp.java:36-58
  ├─ ConfigService(ConfigService.defaultConfigDir()).load() → AppConfig
  ├─ ensureDataDir(config.dataDir)  (mkdir + write-probe)
  ├─ sysprop -Dlifedesk.data ghi đè đường dẫn dữ liệu (không ghi ngược vào config)
  ├─ SingleInstanceLock.acquire(dataDir)      → data/.lifedesk.lock (FileLock cấp OS)
  └─ new AppContext(dataDir, config)
LifeDeskApp.start()                      app/LifeDeskApp.java:61-77
  ├─ Scene (StackPane placeholder, size từ config)
  ├─ new MainWindow(stage, scene, ctx) → scene.setRoot(mainWindow.root())
  ├─ title "LifeDesk — Trợ lý năng suất cá nhân", min 1080×680, stage.show()
  ├─ ctx.startBackgroundServices()     app/AppContext.java:174-186
  │    (warm-up search index trên IO pool + bật ReminderScheduler & WorkflowScheduler)
  └─ mainWindow.onReady()              ui/MainWindow.java:534-548
       (gắn agent approval handler, notification chào mừng, listener sự cố storage)
stop()                                  app/LifeDeskApp.java:99-110
  └─ ctx.shutdown() (flush, stop agent, debounce pool, executors) → release lock → Platform.exit()
```

**JavaFX bootstrap — gọi cái gì, trên thread nào.** `LifeDeskApp` kế thừa `javafx.application.Application`;
`main()` chỉ gọi `launch(args)` (`app/LifeDeskApp.java:32-34`). Toolkit JavaFX dựng **JavaFX Application Thread**
rồi gọi ba pha theo thứ tự cố định — thứ tự này là ràng buộc thật của chương trình, không phải chi tiết trình bày:

| Pha | Thread | Việc xảy ra | Vị trí |
|---|---|---|---|
| `init()` | JavaFX-Launcher | đọc/ghi config, `ensureDataDir`, khoá single-instance, dựng `AppContext` — **chưa có Scene nên chưa có UI để báo lỗi** | `LifeDeskApp.java:36-58` |
| `start(Stage)` | FX thread | tạo `Scene` (kích thước từ config) → `MainWindow.root()` → gắn stylesheet → `stage.show()` → **sau khi cửa sổ hiện** mới bật dịch vụ nền + `onReady()` | `:61-77` |
| `stop()` | FX thread | `ctx.shutdown()` → nhả `.lifedesk.lock` → `Platform.exit()` | `:99-110` |

Hai hệ quả khi đọc code:
1. `init()` chạy **trước** khi có `Stage`; lỗi ở đây chỉ nổi lên thành ngoại lệ JavaFX
   (`throw new RuntimeException(...)` tại `:53-57`), không có dialog — vì vậy "app không mở được"
   thường chỉ để lại stack trace trong log.
2. `startBackgroundServices()` gọi **sau** `stage.show()` (`:74-75`) nên index/scheduler không chặn cửa sổ hiện ra;
   đổi lại, mọi lỗi trong `AppContext` sẽ chặn app trước khi có UI.

`SingleInstanceLock` ([app/SingleInstanceLock.java:30-58](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/app/SingleInstanceLock.java#L30))
từ chối tiến trình thứ hai mở cùng thư mục dữ liệu và báo lỗi tiếng Việt rõ ràng (`:45-51`).

Có một entry point thứ hai phục vụ kiểm chứng tự động: `app/SmokeMain.java` — 2 mode `services` (headless,
không khởi tạo toolkit) và `ui` (mở app thật rồi `Scene.snapshot()`), wired qua `exec-maven-plugin`
(`pom.xml:165-171`). Vì toolkit chỉ được tạo ở mode `ui`, mode `services` chạy được trên máy không GUI.
Chi tiết ở mục V.3.

### II.2 Code chạy như thế nào — build, chạy, và một hành động đi qua các tầng

Mục này trả lời ba câu hỏi thực dụng: (a) từ mã nguồn làm sao có một app đang chạy; (b) một thao tác của
người dùng đi qua lớp nào và ghi xuống đĩa ở đâu; (c) đoạn code nào chạy trên thread nào.

#### a) Build & chạy — đường đã kiểm chứng trên máy này

Dự án chuẩn dùng Maven (`pom.xml`), nhưng **máy phân tích không có Maven** (chỉ JDK 26). Đường chạy thật
là `javac` + `java`, với JavaFX/Gson/commonmark/SLF4J lấy từ `target/lib`:

```bash
# 1) Compile toàn bộ mã nguồn (130 file) — đã chạy: không lỗi
find src/main/java -name '*.java' > target/all_sources.txt
javac --release 25 -encoding UTF-8 -cp "target/lib/*" -d target/classes @target/all_sources.txt

# 2) Chạy app với dữ liệu/config riêng, không đụng dữ liệu thật
#    (thay <repo> bằng đường dẫn tuyệt đối tới thư mục dự án)
java -Dlifedesk.data="<repo>/target/verify-data" \
     -Dlifedesk.config="<repo>/target/verify-data/config" \
     -Dlifedesk.log.dir="<repo>/target/logs" \
     -cp "target/classes;target/lib/*" com.lifedesk.app.Launcher

# 3) Kiểm chứng headless, không cần GUI — đã chạy: PASS (40 passed, 0 failed)
java -cp "target/classes;target/lib/*" com.lifedesk.app.SmokeMain services
```

Ba sysprop trên đều là cửa ghi đè có sẵn trong code, không phải mẹo ngoài lề:
`lifedesk.data` (`LifeDeskApp.java:46-49`), `lifedesk.config` (`ConfigService.java:80-83`),
`lifedesk.log.dir` (`Launcher.java:8-14`, phải đặt **trước** khi logback khởi tạo).

Hai đặc điểm cần biết khi tự chạy:

- **Config lồng hai tầng.** `-Dlifedesk.config=X` chỉ là gốc; `ConfigService` tạo `JsonStore` tại
  `X/config` (`ConfigService.java:47`), nên file thật là `X/config/app-config.json` — trùng khớp mô tả
  bố cục ở mục II.5.
- **Cảnh báo "Unsupported JavaFX configuration".** Vì JavaFX nạp từ classpath (unnamed module), JVM in
  cảnh báo `classes were loaded from 'unnamed module'` cùng `--enable-native-access`. Đây là cảnh báo của
  JDK 25, **không phải lỗi**: app vẫn render bình thường (xác nhận bằng ảnh chụp ở mục V.3).
  Bản đóng gói jpackage có gặp cảnh báo này hay không thì báo cáo **chưa kiểm chứng** (máy không có Maven/jpackage).

Đường Maven/script của dự án (để đối chiếu, **chưa chạy trên máy này**):

| Mục đích | Lệnh | Cấu hình |
|---|---|---|
| Run dev | `scripts/run.ps1` → `javaw -cp target\classes;target\lib\* com.lifedesk.app.Launcher` | `scripts/run.ps1:16-21` |
| Smoke | `mvn exec:java` (mainClass `SmokeMain`) | `pom.xml:165-171` |
| Gom jar runtime | `mvn package` → `copy-dependencies` vào `target/lib` | `pom.xml:144-159` |
| Đóng gói | `scripts/package.ps1` → `mvn test` + `mvn package` + `jpackage` | `scripts/package.ps1:47,53,72` |

`pom.xml:23` khai báo `main.class = com.lifedesk.app.Launcher` — tức entry point của bản phát hành là
`Launcher`, còn `SmokeMain` chỉ là entry point kiểm chứng.

#### b) Một hành động end-to-end: tạo công việc

Chọn thao tác đại diện nhất vì nó đi qua đủ 5 tầng: UI → service → repository → `JsonStore` → đĩa → notify → UI.

```text
Bấm "Công việc mới" (hoặc Ctrl+N khi đang ở view Công việc)
 1. TasksView.java:116-117        Button + accelerator (Ctrl+N chỉ chạy khi view hiện hành
                                  — MainWindow.java:291-297)
 2. TasksView.java:540            dialog lưu → ctx.tasks().create(...)
 3. TaskService.java:33-47        validate → repo.insert(t) (:44) → change.fire() (:47)
 4. JsonTaskRepository.java:71-74 thêm vào List<Task> in-memory → persist()
 5. JsonTaskRepository.java:54-57 envelope {schemaVersion:1, data:{tasks:[...]}}
                                  → store.write("tasks/tasks.json")
 6. JsonStore.java:145-202        ghi file tạm → FileChannel.force(true) (:163)
                                  → nội dung cũ thành tasks.json.bak (:165-171)
                                  → Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE) (:175)
 7. ChangeNotifier                fire ngay trên thread đang gọi
 8. TasksView.java:138, :141-152  listener → scheduleRefresh → coalesce → Platform.runLater
                                  → refresh() (:336)
 9. AppContext.java:115,144-156   listener thứ hai → markSearchDirty → debounce 2s
                                  → SearchService.rebuildAll()
```

Điểm quan trọng nhất để đọc đúng: **bước 2-6 chạy trên FX thread**, vì handler của dialog là mã UI.
Nghĩa là app ghi file đồng bộ ngay trong thao tác người dùng (chấp nhận được với file vài chục KB),
và `JsonStore` vẫn phải có `ReentrantLock` vì các nguồn ghi khác (scheduler, agent chạy trên pool) có thể
chạm cùng file. Chỉ các việc dài hơi mới được đẩy sang pool qua `Fx.async` (mục c).

#### c) Mô hình thread

| Thread | Số luồng | Việc gì | Vị trí |
|---|---|---|---|
| JavaFX Application Thread | 1 | toàn bộ UI, CSS, handler người dùng; mutation phát sinh từ UI (kể cả ghi file) | toolkit JavaFX |
| JavaFX-Launcher | 1 | chỉ `init()` | toolkit |
| `lifedesk-io` | 4, daemon | warm-up search index, quét file, backup/restore, tool của agent, việc nền `Fx.async` | `AppExecutors.java:26-30` |
| `lifedesk-ai` | 2, daemon | chat provider, list model, test kết nối | `AppExecutors.java:31-35` (`AiChatView.java:177`) |
| `lifedesk-scheduler` | 2, daemon | reminder (delay 20 s, chu kỳ 60 s) + workflow (delay 45 s, chu kỳ 30 s) | `AppExecutors.java:36-40`, `ReminderScheduler.java:84`, `WorkflowScheduler.java:106` |
| `lifedesk-index` | 1, daemon | reindex tìm kiếm sau debounce 2 s | `AppContext.java:108-112`, `:146-155` |

Bất biến nối các thread này: **tầng service không import JavaFX** (`common/`, các package domain như
`tasks/`, `notes/`, `finance/`, `storage/`), nên service có thể `fire()` từ bất kỳ thread nào. UI tự marshal về FX thread bằng
`Fx.runLater` (`ui/Fx.java:10-16`) và `Fx.async(executor, work, onSuccess, onError)` (`:19-37`) —
đây là lý do các view đều có `scheduleRefresh`/`Platform.runLater` thay vì cập nhật bảng trực tiếp.
Thứ tự tắt máy cũng nằm ở đây: `AppContext.shutdown()` dừng debounce → agent → 3 pool, rồi `LifeDeskApp.stop()`
mới nhả `.lifedesk.lock` (`AppContext.java:188-195`, `LifeDeskApp.java:99-110`).

#### d) Khi có sự cố thì nhìn ở đâu

| Muốn xem | Đường dẫn | Ghi chú |
|---|---|---|
| Log ứng dụng | `%APPDATA%\LifeDesk\logs` hoặc `-Dlifedesk.log.dir` | `Launcher.java:8-14` |
| Dữ liệu | `%APPDATA%\LifeDesk\data` hoặc `-Dlifedesk.data` | cây thư mục ở mục II.5 |
| Config thật | `<config>\config\app-config.json` | lồng hai tầng, xem mục II.5 |
| Khoá một-instance | `<data>/.lifedesk.lock` | chỉ xoá khi chắc không còn tiến trình LifeDesk |
| File hỏng | `<file>.bak`, `<file>.corrupt-<ts>` | cơ chế tự phục hồi `JsonStore.java:95-105` |

Ba lệnh kiểm chứng nhanh, dùng đúng thứ tự này khi nghi ngờ:

```bash
# 1) Tầng service còn nguyên? (~15 s, không cần GUI)
java -cp "target/classes;target/lib/*" com.lifedesk.app.SmokeMain services

# 2) UI còn render đúng? (-Dlifedesk.seed nhận một thư mục dữ liệu mẫu)
java -Dlifedesk.seed="target/verify-data" -cp "target/classes;target/lib/*" \
     com.lifedesk.app.SmokeMain ui target/shot/dark/dashboard.png

# 3) Màu còn đạt WCAG? (đọc 5 palette, in bảng đối chiếu)
python scripts/contrast_check.py
```

Mode `ui` còn tự in guard `LAYOUT <VIEW> viewport=… content=… ok|OVERFLOW` cho từng view và assert
`Scene.getAccelerators()` có đủ `Ctrl+1..Ctrl+0` — hai kiểm tra này bắt được lỗi tràn ngang và lỗi mất phím tắt
mà ảnh chụp không thể hiện.

**Bằng chứng chạy (2026-10-27, máy không có Maven):**

| Kiểm chứng | Kết quả |
|---|---|
| Compile toàn bộ | 130/130 file OK, chỉ còn warning deprecation có sẵn |
| `SmokeMain services` | **PASS (40 passed, 0 failed)** |
| `SmokeMain ui` | 10 view + dashboard render, không view nào `OVERFLOW`, accelerator 10/10 |
| Ảnh chụp | 11 view × 3 theme (`target/shot/{dark,light,mate}`) |
| `scripts/contrast_check.py` | 165 phép đo, **0 vi phạm** |

### II.3 Composition root & tầng service

`AppContext` ([app/AppContext.java:72-124](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/app/AppContext.java#L72))
là **DI thủ công bằng constructor** — không annotation, không container. Thứ tự khởi tạo trong constructor là đồ thị
phụ thuộc hoàn chỉnh của app: `JsonStore` → `ThemeManager` → 8 service domain → `RuleBasedEngine` + `AiManager`
→ `SecurityPolicy`/`AuditLogger`/`ToolRegistry`/`AgentService`/`WorkflowScheduler` → `ReminderScheduler`.
11 agent tool đăng ký tại `:158-171`; 6 `ChangeNotifier` được nối vào một bộ debounce reindex tìm kiếm 2 giây
(`:115-120`, `:144-156`); snapshot AI ghi ra `data/ai/ai-config.json` (`:127-139`).

```text
Launcher → LifeDeskApp (FX bootstrap)
              ↓
         AppContext (composition root)
              ↓                                    ↓
   MainWindow (shell, navigation)          Domain services (JavaFX-free)
              ↓                                    ↓
   View subclasses (lazy, cached)          JsonStore  →  data dir
```

Bất biến kiến trúc quan trọng nhất: **tầng service không import JavaFX**. Service là POJO thuần, chạy trên executor pool,
phát thay đổi qua `ChangeNotifier` ([common/ChangeNotifier.java](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/common/ChangeNotifier.java))
là observer tự chế không phụ thuộc FX. UI nhận callback và marshal về FX thread bằng
`Fx.runLater` ([ui/Fx.java](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/ui/Fx.java)).
Nhờ vậy toàn bộ 73 test chạy được headless, và `SmokeMain services` chạy được không cần GUI.

View được tạo **lười** qua factory (`ui/MainWindow.java:81-94`, `showView` `:248-264`) và cache lại;
lớp base `ui/View.java:9-19` giữ `ctx` + `main`, có hook `id()/build()/onShow()/onHide()/openRecord()`.

### II.4 Tầng lưu trữ — `storage/JsonStore.java`

Đây là phần được thiết kế kỹ nhất của dự án và là nền cho mọi tính năng phía sau.

| Cơ chế | Implementation | Vị trí |
|---|---|---|
| Envelope format | `{"schemaVersion":N,"data":{...}}` | `storage/JsonStore.java:42-43`, `:150-152` |
| Ghi atomic | file tạm → `FileChannel.force(true)` (fsync) → `ATOMIC_MOVE` (fallback move thường) | `:145-202` |
| Bản giữ lại | nội dung cũ thăng cấp thành `<file>.bak` | `:145-202` |
| Tự hồi phục file hỏng | đọc lỗi → thử `.bak` → vẫn hỏng thì cách ly `<file>.corrupt-<ts>` + báo `StorageIssueLog` | `:83-111`, `:131-139` |
|Lock in-JVM | `ReentrantLock` theo từng path | `:266-268` |
| Lock đa tiến trình | `SingleInstanceLock` + `FileLock` OS | `app/SingleInstanceLock.java` |
| Chặn path traversal | `resolve()` từ chối escape khỏi data root | `:65-71` |
| Văn bản thô | `readText`/`writeText` cũng atomic + `.bak` (dùng cho file `.md`) | `:204-249` |
| Migration | interface `SchemaMigrator`, gọi khi version lưu < version hiện tại | `storage/SchemaMigrator.java` |

**Trạng thái migration**: mọi store hiện đều **version 1 với migration no-op**
(`ConfigService.java:32-43`, `JsonTaskRepository.java:21-31` — comment "future versions add explicit steps here").
Khung nâng cấp đã dựng sẵn nhưng chưa có dữ liệu để nâng; đây là hạ tầng chờ, không phải lỗi.

`GsonFactory` ([storage/GsonFactory.java](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/storage/GsonFactory.java))
cấu hình pretty-print, tắt HTML escaping, đăng ký adapter cho `LocalDate/LocalTime/LocalDateTime/Instant/YearMonth`,
giá trị không hợp lệ → `StorageException` (fail fast, không âm thầm nhận dữ liệu sai).

**Mẫu truy vấn chung**: mọi service load toàn bộ danh sách vào memory khi khởi tạo, mutation xong thì `persist()`
ghi lại **cả file**, rồi `change.fire()`. Đây là lựa chọn có chủ đích cho quy mô dữ liệu cá nhân (đơn giản, nhất quán,
atomic), đánh đổi là ghi O(n) mỗi thao tác — không phải vấn đề ở quy mô này nhưng là ràng buộc cần biết nếu dữ liệu
phình to (xem mục VII).

### II.5 Bố cục dữ liệu trên đĩa

Gốc dữ liệu mặc định `%APPDATA%\LifeDesk\data` (ghi đè bằng `-Dlifedesk.data`); gốc config mặc định
`%APPDATA%\LifeDesk\config` (`ConfigService.defaultConfigDir()` `:79-89`). **Hai gốc này là hai nơi khác nhau**:
`JsonStore` của config được tạo tại `<configDir>/config` ([config/ConfigService.java:45](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/config/ConfigService.java#L45)),
nên file config thật nằm ở `%APPDATA%\LifeDesk\config\config\app-config.json` — **thư mục `config` lồng hai tầng**,
đúng như `README.md:128` mô tả (đã xác minh bằng source, không phải lỗi tài liệu).
Ngược lại, `ThemeManager` resolve theme ngoài theo **gốc dữ liệu**: `<dataDir>/config/themes` (`ThemeManager.java:25`).
Hai chữ `config` này mang nghĩa khác nhau tùy caller — điểm dễ gây nhầm nhất trong toàn bộ bố cục dữ liệu.

```text
data/
├── config/themes/                CSS theme ngoài (ThemeManager.java:25) — app-config.json KHÔNG nằm đây
├── tasks/tasks.json              cả danh sách task, ghi lại mỗi thay đổi
├── notes/index.json              metadata NoteMeta
├── notes/**/<title>-<id8>.md     nội dung Markdown thật (user đọc/sửa được bằng app khác)
├── finance/categories.json, budgets.json, debts.json, fingerprints.json
├── finance/transactions-YYYY.json   PHÂN VÙNG THEO NĂM
├── habits/habits.json, goals.json, changelog.json, logs-YYYY.json (partition theo năm, lazy load)
├── health/entries.json, metrics.json
├── learning/courses.json, sessions.json, decks.json, flashcards.json
├── files/favorites.json
├── notifications/notifications.json, reminders-sent.json
├── index/search-index.json       cache dựng lại được
├── automation/workflows.json, runs.json (cap 50), audit-YYYYMM.jsonl
├── security/policy.json
├── ai/ai-config.json             snapshot từ app-config
└── backups/                      lifedesk-backup-*.zip + manifest.json + orphaned-*/
```

Hai file đặc biệt: `data/.lifedesk.lock` (single instance) và các file `.bak` / `.corrupt-<ts>` cạnh từng JSON.

### II.6 Hệ thống theme

Tách **cấu trúc** và **bảng màu** — cách làm sạch sẽ và cho phép theme ngoài mà không đụng code:

- `src/main/resources/css/base.css` (769 dòng) chứa **toàn bộ** rule cấu trúc: typography, buttons, input, combo,
  table, list, checkbox, scroll, layout chrome, card, chip, state, progress, tab, splitpane, date-picker, popup,
  dialog bar, notification popup, chat, chart, markdown, tooltip. **Không hardcode màu nào** — chỉ dùng
  looked-up color `-ld-*` (comment tuyên bố nguyên tắc này ngay đầu file `base.css:1-5`).
- Mỗi file theme (~23 dòng) chỉ định nghĩa `.root { -ld-* }`: `light.css`, `dark.css`, `mate.css`, `monokai.css`,
  `deepblue.css` (5 theme, xác nhận bằng `ls src/main/resources/css/`). Palette gồm bg/surface/card/input/elev/border/text/text-dim/accent/
  accent-text/accent-soft/danger/ok/warn/info/chart-1..5.
- `ThemeManager.stylesheetsFor(theme)` trả `[base.css, theme.css]` (`theme/ThemeManager.java:47-52`);
  `MainWindow.applyTheme()` (`ui/MainWindow.java:303-307`) gán `scene.getStylesheets()` và chuyển list cho
  `Dialogs.setGlobalStylesheets` để **dialog kế thừa theme** (điểm hay bị bỏ quên ở app JavaFX).
- **Theme ngoài**: thả file `.css` vào `<data>/config/themes/` → tự liệt kê (`availableThemes()` `:33-44`),
  tên file được sanitize qua `safeFileName`, theme không tồn tại fallback `dark.css`.
- **WebView palette**: `previewPalette(theme)` (`:74-82`) xuất màu hex cho HTML preview, vì JavaFX CSS
  không áp dụng được bên trong WebView.

`AppConfig.theme` mặc định `"Dark"`, đổi qua ComboBox trên topbar, lưu async.

### II.7 Config & common

`config/AppConfig.java`: `schemaVersion`, `dataDir`, `theme`, `sidebarCollapsed`, `remindersEnabled`,
`reminderMinutesBefore` (15), `allowedRoots` (các thư mục người dùng cấp quyền cho file tool),
`AgentLimits{maxSteps=25, maxRunSeconds=300, perStepSeconds=60, maxRetries=2}` (`:35-40`), `AiConfig`,
`dashboardWidgets` + `dashboardWidgetOrder`, `windowWidth/Height` (1360×860), `windowMaximized`.

`config/AiConfig.java`: `enabled=true`, `providerId` ∈ `"rule-based"|"ollama"|"kilo"`, `includeUserData=true`,
`maxHistoryMessages=12`, nested `Ollama{baseUrl http://localhost:11434, model, timeoutSeconds=120}` và
`Kilo{baseUrl https://api.kilo.ai/v1, modelsPath /models, chatPath /chat/completions, apiKey, model, timeoutSeconds=120}`.

`config/ConfigService.java`: tạo + lưu config mặc định lần chạy đầu (`:54-71`), `ensureDataDir` write-probe (`:104-115`),
inner class `SilentIssueLog` (`:118-124`) — log lỗi store vào thời điểm chưa có UI để báo.

`common/`: `AppException`/`StorageException`/`NotFoundException`/`ValidationException` ( hierarchy thống nhất,
message tiếng Việt); `AppExecutors` — 3 daemon pool `io=4`, `ai=2`, `scheduler=2`, shutdown gracefully 3s + io-death listener;
`ChangeNotifier`; `CsvUtil` (87 dòng, parse CSV cho finance); `Fingerprint` (SHA-256); `Ids` (UUID);
`TextSearch` — normalize NFD + `đ→d` để tìm kiếm **không phân biệt dấu** (rất phù hợp tiếng Việt);
`Validators` (non-blank/true/non-null, message VN).

---

<div style="page-break-before: always;"></div>

## III. Chi tiết từng module tính năng

Mỗi module trình bày theo 3 lớp: **tính năng người dùng** → **cài đặt trong code** → **hiện trạng**.

> Ghi chú tham chiếu: các mục II (kiến trúc, luồng chạy, storage, dữ liệu) đã được đối chiếu lại với code hiện tại.
> Riêng tham chiếu tới lớp UI (`ui/views/*`, `MainWindow`, `base.css`) được ghi **trước đợt rewrite giao diện**
> (icon SVG thay emoji, thêm `IconView`, bảng token `-ld-*` mới), nên **số dòng có thể lệch** — khi cần chính xác,
> đọc trực tiếp `ui/MainWindow.java` và `src/main/resources/css/base.css` hiện hành.

### III.1 Tasks — `src/main/java/com/lifedesk/tasks`

**Tính năng**
- CRUD với title, notes, priority (`LOW/MEDIUM/HIGH/URGENT`), status (`TODO/IN_PROGRESS/DONE/CANCELLED`),
  due **date + optional time**, group tự do, tags, **checklist theo task** có progress bar
  (`tasks/Task.java:16-30`, `checklistProgress` `:52-58`; UI `ui/views/TasksView.java:208-223`).
- Hai chế độ hiển thị: **bảng danh sách ⇄ Kanban board** grouping theo status (`TasksView.java:96-110`, `:277-308`).
- **Lặp lại DAILY/WEEKLY/MONTHLY** kèm interval và chọn thứ trong tuần; **hoàn thành task lặp tự sinh phiên kế tiếp**
  (`TaskService.java:70-96`; editor `TasksView.java:413-431`).
- Filter group/status/priority + search không phân biệt dấu (`TasksView.java:310-324`).
- Query cho dashboard: due today / overdue / upcoming N ngày (`TaskService.java:155-174`).
- **Nhắc việc trong app**: quá hạn, `reminderMinutesBefore` trước giờ deadline, và 9:00 sáng cho task không đặt giờ
  (`app/ReminderScheduler.java:107-137`).
- Liên kết note và goal (`Task.linkedNoteIds/linkedGoalIds`, editor `TasksView.java:433-470`).

**Cài đặt**
Model `Task` + `RecurrenceRule{type, interval, daysOfWeek: List<Integer> 1=Mon..7=Sun}` (`RecurrenceRule.java`),
enum `RecurrenceType`, `Priority`, `TaskStatus`, `ChecklistItem`.
Tầng repository tách interface `TaskRepository` / impl `JsonTaskRepository` — UI không bao giờ chạm file.
Persist `tasks/tasks.json`, serialize lại toàn bộ `List<Task>` mỗi lần ghi (`JsonTaskRepository.java:17`, `:54-58`).
Wiring `AppContext.java:79`; tool `task.create/list/update_status/search` (`AppContext.java:159-162`);
view `MainWindow.java:83`.

Thuật toán đáng chú ý — `nextOccurrence` (`TaskService.java:99-119`): DAILY `plusDays(max(1,interval))`;
MONTHLY `plusMonths`; WEEKLY qua `nextWeekly` (`:121-134`) quét ngày ứng viên khớp `daysOfWeek` **và**
`weeksBetween % interval == 0`; vòng lặp tới khi date ≥ today với guard `10000` iteration.
Task sinh lại reset checklist sang chưa xong, giữ nguyên tags / goal+note links / recurrence (`:86-91`).

**Hiện trạng: HOÀN CHỈNH.** Không có TODO/FIXME/empty body/`UnsupportedOperationException`.
7 test trong `tasks/TaskServiceTest.java`: validate title (`:24`), update/delete (`:29`),
**daily recurrence sinh phiên kế** (`:42`), weekly chọn đúng thứ (`:59`), due-today/overdue/upcoming (`:72`),
completedAt (`:84`), search không dấu (`:94`). Sâu hơn: `app/AppIntegrationTest.java:82-106` chứng minh reminder
fire **một lần/ngày và key sống qua restart**, `:43-58` data sống qua restart context.

---

### III.2 Notes + Markdown — `src/main/java/com/lifedesk/notes`, `src/main/java/com/lifedesk/markdown`

**Tính năng**
- Ghi chú Markdown, **thư mục lồng nhau** (path gốc-tương-đối kiểu `work/projects`), tags, star/pin
  (`ui/views/NotesView.java:105-107`, `:323-328`).
- Editor + **live preview** tách đôi, **autosave debounce 1.5s** (`NotesView.java:47`, `:240-248`).
- **Phát hiện xung đột sửa ngoài app**: nếu file `.md` trên đĩa đã đổi từ lần lưu cuối, phiên bản ngoài được giữ lại
  thành `…-conflict-<ts>.md` thay vì bị đè (`notes/NotesService.java:158-204`).
- **`[[wiki link]]`** điều hướng giữa ghi chú, **`#tag`** trích từ nội dung (`notes/NoteLinkParser.java`).
- Full-text search trên title/folder/tags/content (`NotesService.java:257-270`).
- **Cross-module link**: gắn một note vào bản ghi của finance / habit / goal / health / learning,
  tra ngược + mô tả dạng người đọc (`NotesService.java:287-347`). Đổi tên / chuyển thư mục giữ nguyên `id` (`:216-255`).

**Cài đặt**
`NoteMeta` (`notes/NoteMeta.java:9-22`): `id, title, folder, tags:Set, contentFile, starred, createdAt, updatedAt,
lastSavedHash, links:Map<String,Set<String>>`.
**Tách metadata và nội dung**: metadata ở `notes/index.json` (`NotesService.java:42`, `:88-92`),
nội dung là **file `.md` thật** tại `notes/[folder]/<safe-title>-<id8>.md` (`fileNameFor` `:364-377`) —
người dùng có thể mở/sửa bằng editor khác, và backup dạng text. In-memory `List<NoteMeta>` +
`contentCache` + `loadedHash` (`:57-63`).
Cơ chế conflict: so `Fingerprint.sha256(file hiện tại)` với `meta.lastSavedHash` trước khi đè,
trả `SaveResult.{SAVED, SAVED_WITH_CONFLICT_COPY, UNCHANGED}` (`:171-191`).
Regex `WIKI_LINK = \[\[([^\]]+)]]`, `TAG = (^|\s)#([\p{L}0-9_-]+)` (`NoteLinkParser.java:10-11`);
`resolveLink` dùng `TextSearch.normalize` nên gõ không dấu vẫn tìm được (`NotesService.java:277-283`).

Markdown layer **stateless, không persist gì**: `markdown/MarkdownService.java` dựng trên commonmark
`Parser` + `HtmlRenderer` + `TextContentRenderer` (`:29-41`), bật GFM tables + task-list (`:29-31`).
`markdown/MarkdownPreviewPane.java` là `VBox` bọc `WebView`, cài DOM `EventListener` lên anchor để trang
**không tự điều hướng** (`:47-65`); `lifedesk://note/<id>` xử lý in-app, link ngoài đi qua confirm-then-browse.

**Pipeline an toàn** (điểm mạnh nhất của module): `escapeHtml(true)` + `sanitizeUrls(true)` + `SafeUrlSanitizer`
tự viết — whitelist chỉ `http/https/mailto/lifedesk:///#anchor/relative` đi qua; `javascript:`, `data:`, `file:`,
`vbscript:` bị strip theo regex `^[a-z][a-z0-9+.-]*:.*$` (`MarkdownService.java:49-72`).
`rewriteWikiLinks` dùng `Matcher.appendReplacement` + `quoteReplacement` (`:103-122`).
`wrapPage` xuất HTML có inline CSS từ `ThemeManager.PreviewPalette` và **không có `<script>`** (`:125-158`).

**Hiện trạng: HOÀN CHỈNH cả hai module.** Notes thiếu file nội dung → degrade thành rỗng thay vì crash
(`NotesService.java:141-144`). Tests: `notes/NotesServiceTest.java` 8 test (CRUD + file trên đĩa, conflict copy,
rename giữ id, move thư mục, search, link parser, cross-module link, index sống qua reload);
`markdown/MarkdownServiceTest.java` 7 test (XSS escaping, `javascript:` URL, tables/headings/lists/code,
task-list checkbox, wiki link → `lifedesk://note/<id>` + fallback, plain-text extraction, wrap page có màu theme
và không script). `MarkdownPreviewPane` **không có unit test** vì cần JavaFX runtime — chỉ được phủ bởi UI smoke screenshot.

---

### III.3 Finance — `src/main/java/com/lifedesk/finance`

**Tính năng**
- Giao dịch thu/chi theo **danh mục tiếng Việt seed sẵn**, quick-add bar (Enter = lưu), phân trang tháng,
  filter text + type (`ui/views/FinanceView.java:69-74`, `:125-128`, `:152-169`, `:202-233`).
- 4 tab: **Giao dịch / Ngân sách / Khoản phải thu–phải trả / Báo cáo** (`FinanceView.java:125-128`).
- Quản lý danh mục add/rename/delete có guard "danh mục đang có giao dịch sẽ không xóa được" (`FinanceView.java:816-850`).
- **Ngân sách theo danh mục × tháng**, gồm **template lặp hằng tháng** (`FinanceService.java:413-443`).
- **Debt tracker**: phải thu/phải trả, hạn trả, tất toán (`FinanceService.java:470-529`, `FinanceView.java:614-631`).
- **Xuất/nhập CSV tự chống trùng** (`FinanceView.java:112-114`, `:763-781`; `FinanceService.java:536-635`).
- Báo cáo: `BarChart` thu–chi 6 tháng + `PieChart` theo danh mục (`FinanceView.java:713-749`).
- Gắn note cho từng giao dịch (`FinanceView.java:452-453`); widget dashboard `w_finance`, `w_budget`.

**Cài đặt**
`FinanceService.java` 666 dòng, **`BigDecimal` xuyên suốt** (class doc `:40-45`, enforced mọi đường).
Model: `Transaction` (`id, date, type, amount, currency="VND", categoryId, method, description, tags,
externalId, importFingerprint, createdAt` — `Transaction.java:10-22`), `Category`(`:5-8`),
`Budget{month:YearMonth, categoryId, limitAmount, recurring}`(`:8-13`),
`Debt{direction, party, amount, incurredDate, dueDate, status, note, settledAt}`(`:8-17`);
enum `TxType`, `PaymentMethod`(CASH/BANK_CARD/BANK_TRANSFER/E_WALLET/OTHER), `DebtDirection`, `DebtStatus`,
`CsvImportReport{created, skippedDuplicates, errors, errorLines, messages}`.
Persist: `finance/categories.json`, `budgets.json`, `debts.json`, `fingerprints.json` (`:79`, `:86`, `:92`, `:98`)
và **`transactions-YYYY.json` phân vùng theo năm**, quét directory để khám phá năm tồn tại (`discoverYears()` `:236-254`).

**Cơ chế dedupe — chi tiết thiết kế đáng chú ý**: `txFingerprint` = SHA-256 của khóa pipe-joined đã normalize,
amount canonical bằng `stripTrailingZeros().toPlainString()` (`:257-262`).
Rule có chủ đích tinh tế: **nhập tay chỉ chặn trùng trong cùng ngày** (cà phê hằng tháng / tiền thuê nhà là hợp lệ),
còn **CSV import dedupeAgainst toàn bộ fingerprint từ trước tới nay** (`:284-293`, comment giải thích `:284-286`);
`allowDuplicate=true` vượt qua guard (`:283`).
`budgetStatus` tính `usedRatio` bằng `BigDecimal.divide(..., 4, HALF_UP)` và có nhánh chia-cho-0 (`:453-466`).
Delete category scan **mọi năm đã khám phá** trước khi cho xóa, cascade xóa budget (`:184-198`).
CSV import **header-name driven** (không positional), chịu được thiếu cột optional, tự tạo danh mục lạ
(`resolveCategory` `:637-649`), không abort cả batch — báo lỗi từng dòng (`:593-631`).

**Hiện trạng: HOÀN CHỈNH chức năng, nhưng chứa 3 khiếm khuyết kỹ thuật thật sự.**

1. **Chỉnh giao dịch vượt ranh giới năm → `NotFoundException`.**
   `updateTransaction` chọn partition theo **ngày mới** rồi tìm id cũ trong list đó
   ([finance/FinanceService.java:319](file:///C:/Users/sangdo/Downloads/lifedesk/src/main/java/com/lifedesk/finance/FinanceService.java#L319)),
   không khớp nên rơi qua `:328` ném `NotFoundException`. **Không tồn tại logic di chuyển bản ghi giữa
   `transactions-YYYY.json`.** User sửa ngày từ 31/12 sang 01/01 sẽ gặp lỗi.
2. **`fingerprints` không bao giờ được prune hay tính lại.** `deleteTransaction` (`:331-340`) và
   `updateTransaction` (`:314-329`) để lại entry cũ trong `finance/fingerprints.json`
   (chỉ ghi `:130`, thêm `:307`, đọc `:98`, đối chiếu `:283`, `:616`). Hệ quả: giao dịch đã xóa vẫn chặn
   thêm lại bản giống hệt, và state dedupe **phân kỳ vĩnh viễn** so với sổ; `updateTransaction` cũng
   không refresh `importFingerprint` nên khóa lệch khỏi nội dung dòng.
3. **`deleteTransaction` duyệt `txByYear` (`:332`) — chỉ những năm đã lazy-load.** Tiến trình mới chưa từng gọi
   `all()`/`inRange()` sẽ báo `NotFoundException` khi xóa theo id. UI hiện an toàn vì bảng luôn gọi `all()` trước,
   nhưng đây là bẫy cho mọi caller mới.

Tests: `finance/FinanceServiceTest.java` **11 test** (mạnh nhất dự án): chặn amount ≤ 0, chặn category type mismatch,
**tổng BigDecimal chính xác từng chữ số** (`1002345.72 / 5000000.00 / 3997654.28`), same-day dedupe vs cross-day accept + force flag, recurring budget áp mọi tháng + per-month override, CSV export/import round-trip dedupe sang service mới
(0 created, mô tả có dấu phẩy sống sót), CSV bỏ dòng lỗi và báo cáo, budgetStatus ratio 0.75, rename/delete category guards,
debt update, debt lifecycle. **Không có test cho partition theo năm** và không có test cho 2 defect ở trên.

---

### III.4 Habits & Goals — `src/main/java/com/lifedesk/habits`

**Tính năng**
- Habit có lịch thứ trong tuần (rỗng = hằng ngày), màu, **optional reminder time** (`Habit.java:11-19`).
- Điểm danh trên **lưới 14 ngày** dạng nút bấm (`ui/views/HabitsView.java:179-215`), archive (`habits(includeArchived)`).
- **Streak** hiện tại + tốt nhất trong cửa sổ; **tỷ lệ hoàn thành %** (`HabitService.java:221-272`; chip UI `:160-164`).
- **Sửa quá khứ bắt buộc nhập lý do**, ghi audit log (`:185-212`) + bảng changelog (`HabitsView.java:460-476`).
- **Tổng kết tuần** (Thứ 2 → hôm nay, theo từng habit) (`:278-297`; UI `:305-313`).
- **Goals**: status, **progress mode `MANUAL` hoặc `AUTO_TASKS`** (suy từ task liên kết done/total), target date,
  linked tasks, % thủ công (`Goal.java`, `ProgressMode.java`).
- Gắn note cho habit & goal qua `NoteLinkPicker`.

**Cài đặt**
`HabitLog{habitId, date, completed, adjustmentReason, updatedAt}`;
`Goal{id, title, description, status, progressMode, manualProgress, targetDate, linkedTaskIds, …}`;
`ChangeLogEntry{id, at, entityType, entityId, entityName, field, oldValue, newValue, actor, reason}`.
Persist 4 file: `habits/habits.json`, `goals.json`, `changelog.json` (**cap 1000 entry gần nhất** — `:105-110`),
và **`logs-YYYY.json` partition theo năm, lazy load** (`:71-80`, `:112-122`). In-memory `Map<habitId, Map<date, HabitLog>>`.
Vì UI mutate object chia sẻ, service so sánh với **snapshot đã persist** để phát hiện diff (`:36-37`, `:149-168`, `:324-342`).

Thuật toán:
- `currentStreak` (`:221-238`) lùi từ hôm nay, **bỏ qua ngày không theo lịch**, "hôm nay chưa điểm" **không tính cũng không phá** streak, guard 3650.
- `bestStreak(habit, days)` rolling max trong cửa sổ (`:241-256`); `completionRate` chỉ tính ngày theo lịch (`:259-272`).
- `progressOf(g, done, total)` — `AUTO_TASKS` = `round(100*done/total)`, ngược lại manual (`:353-358`).
  Việc nhân done/total **cố ý đặt ở tầng view/dashboard** (`HabitsView.java:354-366`, `DashboardView.java:365+`)
  vì cần `TaskService`, giữ `HabitService` không phụ thuộc chéo.
- Reminder habit chỉ fire khi: theo lịch hôm nay, có `reminderTime`, trong cửa sổ 30 phút, và chưa hoàn thành (`app/ReminderScheduler.java:139-153`).

**Hiện trạng: HOÀN CHỈNH**, không stub. 7 test `habits/HabitServiceTest.java`: streak ngày liên tiếp theo lịch,
quy tắc gap-không-theo-lịch, retroactive bắt buộc reason + log, completion-rate chỉ tính ngày lịch, weekly summary,
goal auto-progress từ linked tasks, goal history khi update.

---

### III.5 Health — `src/main/java/com/lifedesk/health`

**Tính năng**
- **Nhật ký ngày thủ công**: giờ ngủ (0–24), phút vận động (0–1440), cân nặng kg, note (`HealthEntry.java`; form `ui/views/HealthView.java:79-93`).
- **Chỉ số tự đặt** (tên / đơn vị / target cá nhân) có manager thêm–sửa–xóa (`HealthService.java:126-166`; UI `:232-298`).
- Upsert/delete theo date; **`LineChart` xu hướng 30 ngày** cho sleep/exercise/weight + series custom (`HealthView.java:195-230`).
- Gắn note theo ngày, recordId dạng `date:YYYY-MM-DD` (`HealthView.java:92-93`).
- **Lập trường không-y-khoa cứng**: hằng số `DISCLAIMER` hiển thị thường trực; chỉ ghi nhận, **không tính năng chẩn đoán/tư vấn** (`HealthService.java:28-29`).

**Cài đặt**
`HealthEntry{id, date, sleepHours:BigDecimal, exerciseMinutes:Integer, weightKg:BigDecimal,
customMetrics:Map<metricId,BigDecimal>, note}`; `MetricDef{id, name, unit, target}`.
Persist `health/entries.json` + `metrics.json` (`:42-43`, `:59-69`).
Enum `HealthField{SLEEP, WEIGHT, EXERCISE}`; `SeriesPoint` record; `series()`/`customSeries()` dựng `TreeMap`
(sort theo date) filter theo range (`:174-205`).
`upsert(date, …)` = find-by-date-or-create rồi ghi các field (`:80-101`); validate `0..max` cho sleep(24)/weight(500),
check range exercise, chặn trùng tên metric (case-insensitive) (`:103-110`, `:126-156`).
`averageDays(field, days)` dùng `BigDecimal` `HALF_UP` 2dp, trả null khi rỗng (`:212-224`).
`deleteMetric` **scrub luôn id metric khỏi `customMetrics` của mọi entry** (`:158-166`) — cascade đúng.

**Hiện trạng: HOÀN CHỈNH.** 4 test `health/HealthServiceTest.java` (metric rename+target, chặn trùng tên,
delete cascade vào entries, upsert range validation). **Coverage mỏng hơn hẳn** notes/tasks/habits: không test cho
`series`, `averageDays`, `byDate`.
Có một quirk hình thức (vẫn đúng ngữ nghĩa): `HealthService.java:88-93` đóng lambda `});` và câu lệnh
`entry.sleepHours = …` nằm chung một dòng — đọc khó, không phải defect.

---

### III.6 Learning — `src/main/java/com/lifedesk/learning`

**Tính năng**
- Khóa học: source, link, tags, status, % progress thủ công, linked notes (`LearningView.java:71-93`, `LearningService.java:82-135`).
- Ghi buổi học (phút + chủ đề) sửa/xóa, thống kê tổng phút (`LearningView.java:80-86`, `LearningService.java:176-192`, `:244-263`).
- **Flashcard + Leitner spaced repetition**: deck, add/rename/delete deck, add/edit/delete card, bộ đếm "đến hạn hôm nay"
  (`LearningView.java:111-146`, `LearningService.java:204-313`).
- **Phiên ôn tập**: nút "Ôn tập (N thẻ đến hạn)" mở modal hiện mặt trước, **làm mờ đáp án**,
  `Đúng ✓` / `Sai ❌` lật từng thẻ (`LearningView.java:62-65`, `:510-556`, scheduling call `:552`).
- **Tài liệu khóa học**: gắn file local, mở bằng app mặc định OS (`java.awt.Desktop`), detach (`LearningView.java:150-160`, `:339-361`).
- Widget dashboard `w_learning` (thẻ đến hạn + khóa đang học).

**Cài đặt**
`Course{id, title, source, status, progressPercent, link, tags, linkedNoteIds, materialPaths, createdAt, updatedAt}`
(`Course.java:9-23`) + fluent accessors (`:30-56`); `CourseStatus{PLANNED, IN_PROGRESS, PAUSED, COMPLETED}`;
`Deck{id, name, courseId}`; `Flashcard{id, deckId, front, back, box 1..6, dueDate, timesCorrect, timesWrong, createdAt}`;
`StudySession{id, courseId, date, minutes, topic}`.
Persist `learning/{courses,sessions,decks,flashcards}.json` (`:44-47`, writers `:194-200`, `:340-346`) — **không partition** (khác finance).
**Leitner** `BOX_INTERVALS = {1,2,4,7,15,30}` (`:25`); `review()` (`:316-330`): đúng ⇒ `box=min(box+1,6)` và
`dueDate = today + interval[newBox]`; sai ⇒ `timesWrong++`, `box=1`, `dueDate = tomorrow`.
Cascade: `deleteCourse` xóa sessions (`:113-121`), `deleteDeck` xóa cards (`:265-273`).
Validate minutes `1..1440` (`:178-180`, `:247-249`), progress `0..100` (`:99-101`), chặn front/back rỗng (`:230-232`, `:282-284`).

**Hiện trạng: HOÀN CHỨC năng, có 2 chỗ không nhất quán + 1 khoảng trống thiết kế.**
- **Comment contradicts code**: `LearningService.java:24` ghi "Box 7 means graduated" nhưng `review()` cap tại
  `BOX_INTERVALS.length == 6` (`:321`) → **thẻ không bao giờ tốt nghiệp**; `Flashcard.java:12` ghi "box 1..6" khớp code, không khớp comment.
- **Tính toán chết**: `:326` tính `interval` cho mọi review, nhưng nhánh sai đáp án dùng literal `plusDays(1)` tại `:327`
  ⇒ `interval` bị bỏ.
- **Không có review history được persist**: phiên ôn là state modal tạm (`LearningView.java:510-556`), chỉ có
  counter `timesCorrect/timesWrong` trên thẻ. `StudySession` là manual-only và **không tự tạo khi ôn tập** —
  `SmokeMain.java:131` còn assert `minutesBetween(...) == 0` sau khi review, xác nhận hành vi này là chủ ý.

Tests: `learning/LearningServiceTest.java` **4 test** (deck rename persist + chặn blank, card edit guards,
session update/delete + validate minutes, **Leitner đi hết 6 interval và assert cap box** + reset khi sai).
Untested: course CRUD/status/progress, note linking, `materialPaths`, cascade delete, `minutesBetween`, `dueCountForDeck`.

---

### III.7 Files — `src/main/java/com/lifedesk/files`

**Tính năng**
- **Trình duyệt file CHỈ ĐỌC, chỉ trong các root người dùng đã cấp** — không tồn tại thao tác
  delete/rename/move ở bất kỳ đâu (`FileManagerService.java:27-34`; banner UI `FilesView.java:79-80`).
- Liệt kê con trực tiếp + **search đệ quy có giới hạn**: query tên, tập extension, min/max size,
  modified before/after, toggle subfolder, **`maxDepth=6`**, **`maxResults=500`** (`FileSearchOptions.java`; `:107-127`).
- **Mở file bằng app mặc định OS** (`java.awt.Desktop`), chỉ khi user chủ động (`:180-194`).
- **Favorites** persist; cấp root mới qua `DirectoryChooser` có confirm tường minh (`FilesView.java:219-236`, `:328-343`).

**Cài đặt**
`FileHit` record (`path,name,directory,sizeBytes,lastModified,extension`), `FileFavorite{path,label,addedAt}`.
**Chỉ favorites được lưu** (`files/favorites.json` — `:48-54`, `:219-223`); file thật đọc trực tiếp từ đĩa.
`allowedRoots` **không** nằm ở đây mà lấy từ `AppConfig.allowedRoots`, reset qua `setAllowedRoots` (`:61-70`).
Mọi operation đi qua `PathGuard.isInside/requireInside(allowedRoots, …)` (`requireAllowed` `:80-82`,
dùng ở `list`/`search`/`openWithDefaultApp`/`addFavorite`). `toHit` dùng `BasicFileAttributes`,
dung thứ khi file biến mất giữa scan (`:159-177`).
UI chạy search trên `javafx.concurrent.Task` **có thể cancel** qua `ctx.executors().io()` (`FilesView.java:297-311`).

**Hiện trạng: code HOÀN CHỈNH, nhưng là module ít được kiểm chứng nhất.** Không TODO/stub/empty body;
việc thiếu thao tác xóa là **thiết kế có chủ đích**. Khoảng trống nằm ở verification:
**không tồn tại package test `src/test/java/com/lifedesk/files/`** — PathGuard rejection, favorites persistence,
search filter correctness, cancel/`allowedRoots` behavior đều chưa được test nào kiểm.

---

### III.8 Automation Agent — `src/main/java/com/lifedesk/automation` (+ `tools/`)

**Tính năng** — console `ui/views/AutomationView.java`, 4 tab (`:167-171`):
1. **Kế hoạch**: builder chọn tool, add/edit/delete step, **JSON params editor từng step**, cờ
   "Tiếp tục kể cả khi lỗi", checkbox phê duyệt plan, ▶ chạy (`:86-118`, `:185-236`).
2. **Lịch sử chạy**: bảng run (status chip, plan, trigger, note, start), detail từng step, ⏹ cancel (`:121-126`, `:267-326`).
3. **Workflows**: lưu plan thành workflow, schedule editor (`NONE/DAILY/WEEKLY` + ngày + giờ/phút + enable + auto-approve),
   chạy ngay, xóa (`:129-136`, `:330-406`).
4. **Tool registry & chính sách**: dump read-only mọi tool kèm permission level và marker `BỊ CHẶN (DENIED)` + text giới hạn hiệu lực (`:150-163`).
Per-tool deny switches và agent limits nằm ở Settings tab "Agent & Nhắc việc" (`SettingsView.java:466-467`, `:490-492`).

**Cài đặt**
Khung: `Tool`, `ToolDescriptor` record (`name, description, permission, inputSchema, outputSchema`),
`ToolResult` record (`success, message, data`, static `ok/fail`, `retryable()` đọc từ `data.retryable`),
`ToolContext` (`runId, actor`), `PermissionLevel{READ, WRITE, DESTRUCTIVE}`.
Model plan/run: `AgentPlan{id,name,steps,createdAt,origin}`(`:9-16`), `PlanStep{id,tool,description,params:JsonObject,continueOnFailure}`(`:7-12`),
`AgentRun{id,planId,planName,planJson,status,statusNote,steps,createdAt/startedAt/finishedAt,trigger,transient cancelRequested,transient awaitingStepId}`(`:10-24`),
`RunStatus{PENDING,RUNNING,WAITING_APPROVAL,COMPLETED,FAILED,CANCELLED}`,
`StepRecord{stepId,tool,paramsSanitized,status,message,attempts,startedAt,finishedAt}`(`:6-15`).
Services: `AgentService` (facade + history), `AgentExecutor` (engine), `ApprovalService` (cửa xác nhận UI),
`ToolRegistry`, `WorkflowScheduler`, `Workflow`.
Persist: `automation/runs.json` **cap 50 run**, trim lâu nhất trước (`AgentService.java:27-28`, `:142-157`);
`automation/workflows.json` (`WorkflowScheduler.java:27`, `:57-61`);
**audit là JSONL không phải JSON**: `automation/audit-YYYYMM.jsonl` append-only theo tháng (`security/AuditLogger.java:33`, `:41-63`);
policy override `security/policy.json` (`SecurityPolicy.java:32`, `:58-64`).
**Đăng ký tool thủ công lúc boot** tại `AppContext.java:158-171` — **đúng 11 tool**:
`task.create/list/update_status/search`, `note.create/search/delete/append`, `file.search`, `habit.log`, `dashboard.summary`.
`ToolRegistry.register` chặn trùng tên (`:16-22`), `find()` là đường lookup duy nhất, `all()` trả unmodifiable map.

**10 đường ray an toàn** trong `AgentExecutor` (document tại `:21-33`, implement tương ứng):

| # | Cơ chế | Vị trí |
|---|---|---|
| 1 | Validate plan **trước khi submit**: plan rỗng, cap số bước, policy check từng step, `validateParams` từng step | `AgentExecutor.java:62-83` |
| 2 | **Phân tầng phê duyệt**: WRITE cần duyệt cả plan từ đầu; DESTRUCTIVE **chuyển run sang `WAITING_APPROVAL`** và block tại `approvals.requestApproval(run, step)` lúc chạy; từ chối ⇒ step `DENIED`, run `CANCELLED`, step còn lại `SKIPPED` | `:143-165` |
| 3 | **Deny-by-default**: `ApprovalService` chưa đăng ký handler → trả `false`, nên run headless/scheduled không bao giờ thực hiện step destructive | `ApprovalService.java:22-30` |
| 4 | **Chống lặp vô hạn**: `(tool, sha256(params)[0..12])` trùng quá `MAX_IDENTICAL_STEPS = 3` ⇒ fail step; cộng deadline wall-clock; timeout từng step bằng `Future.get(perStepSeconds)` + `future.cancel(true)`; retry bounded `1 + maxRetries` tôn trọng `ToolResult.retryable()` | `:36`, `:128-141`, `:89`, `:101-108`, `:176-190`, `:168-171` |
| 5 | **Cancellation**: `AtomicBoolean cancelRequested` kiểm tra giữa step và trong điều kiện retry loop | `:97-100`, `:171`; `AgentService.cancel` `:95-107` |
| 6 | **Pool cô lập**: `stepPool` daemon riêng tên `lifedesk-agent-step` để tool treo vẫn interrupt được mà không nghẽn pool runner | `:44-49` |
| 7 | **No-shell policy**: `checkPlanStep` trả `DENIED` khi `tool == null` ⇒ mọi tên chưa đăng ký (kể `shell.exec`) bị từ chối; **chỉ tồn tại DENY override** để tool không thể tự nâng quyền | `SecurityPolicy.java:86-88`, `:37-38`, `:66-75` |
| 8 | **Audit mỗi step** kể cả `SKIPPED`, sanitize secret (khóa khớp `api_key`/`token`/`secret`/`password`/`authorization` → `***`) và truncate 200 ký tự đệ quy; audit fail thì log to nhưng **không crash run** | `AgentExecutor.java:225-241`; `AuditLogger.java:27`, `:66-94`, `:59-62` |
| 9 | **Crash recovery**: run persist giữa lúc executing bị force-mark `FAILED` khi load kèm note tiếng Việt; `transient cancelRequested` re-init | `AgentService.java:121-140` |
| 10 | **Workflow auto-approve guard**: `save` từ chối `autoApprove` nếu **bất kỳ** step nào không phải `READ`; `submit` tính lại `readOnly` lúc chạy nên flag không thể stale | `WorkflowScheduler.java:67-81`, `:131-143` |

Ràng buộc trong chính tool: `file.search` yêu cầu `root` nằm trong granted roots **ở cả validate lẫn execute**
(`InfoTools.java:47-61`), cap 200 kết quả; `habit.log` **từ chối ghi ngược ngày** — "Agent chỉ được ghi nhận thói quen
cho ngày hôm nay" (`:150-154`); cap result `note.search`/`task.search`/`task.list` = 50/50/100.
Scheduler poll mỗi 30s (lần đầu 45s), fire **một lần/ngày** so `lastRunAt` theo date (`WorkflowScheduler.java:105-128`).

**Hiện trạng: HOÀN CHỈNH cho phạm vi nó tuyên bố, với 1 seam chưa xây + 1 field chết.**
- **Seam chưa build**: `AgentPlan.java:13-14` document `origin` là `"user"` hoặc `"ai:<provider>"`, nhưng nơi ghi
  `origin` **duy nhất** là `WorkflowScheduler.java:135` (`"workflow:"+id`) và `:153` (`...:manual`);
  `AutomationView.java:243-245` không set. ⇒ **Không tồn tại đường nào từ subsystem AI tới agent plan.**
  AI không thể soạn hay đề xuất plan; plan luôn do người dùng dựng tay hoặc từ workflow đã lưu.
  Đây là chỗ `ai/` và `automation/` rõ ràng **không gặp nhau**.
- **Field chết**: `StepRecord.paramsSanitized` (`StepRecord.java:9`) được khai báo và **được UI đọc**
  (`AutomationView.java:305`) nhưng **không nơi nào gán giá trị** (grep toàn repo: đúng 2 hit đó).
  ⇒ panel chi tiết lịch sử chạy luôn in `params: ` trống; params đã sanitize chỉ tồn tại trong file audit JSONL.
- **Thiếu surface tool**: không có agent tool nào chạm **finance** hay **health**; learning ngoài read-only
  `dashboard.summary` thì không tới được (`AppContext.java:159-171`).

Tests: `automation/AgentPolicyTest.java` **9 test — suite đáng tin nhất so với rủi ro nócovers**:
`unknownToolIsDenied_noShellExecution` (`:41`), `writeStepsRequirePlanApproval` (`:51`),
`deniedToolBlocksWholePlan` (`:60`), `destructiveStepWithoutApprovalHandlerCancelsRun` (`:80` — assert run `CANCELLED`,
step cuối `DENIED` **và note vẫn còn tồn tại**), `destructiveStepWithExplicitApprovalExecutes` (`:94` — assert note biến mất),
`stepLimitEnforcedAtValidation` (`:108`), `endToEndReadWriteRunCreatesTask` (`:120`), `policyDecisionMatrix` (`:136` —
đủ 6 nhánh policy kể cả `null` tool), `auditParamsAreSanitized` (`:152`).
Wait async dùng **`awaitTerminal(run)` poll thật** với deadline 15s + settle 500ms — không mock.
**Chưa được JUnit cover**: timeout từng step, deadline wall-clock, guard lặp 3 lần, nhánh retry/`retryable()`,
cancel giữa run, scheduling workflow (`checkDue`), từ chối auto-approve, cap 50 history, crash-recovery marking,
`PathGuard` confinement, quy tắc no-backdate. `docs/TESTING.md:11` nói rõ **không mock thời gian**, nên các rail
time-based về cấu trúc là khó test ở đây. `SmokeMain.java:156-201` phủ lại phần WRITE-needs-approval, unknown-tool,
destructive-denied **kèm kiểm tra file `audit-*.jsonl` tồn tại** (`:192`), READ+WRITE E2E, run persist qua reload (`:221`).

---

### III.9 AI — `src/main/java/com/lifedesk/ai`

**Tính năng**
- **Chat view** bong bóng + quick-command chips ("việc hôm nay", "quá hạn", "gợi ý", "chi tiêu tháng này",
  "thói quen", "ôn tập", "sức khỏe"), Enter-gửi, status dot provider, nút "⚙ Cài đặt AI", disclaimer quyền riêng tư
  (`ui/views/AiChatView.java:50-90`).
- **Trạng thái AI tắt** degrade sạch, không ảnh hưởng module khác (`AiChatView.java:93-114`, `AiManager.java:41-50`).
- **Cấu hình provider** Settings → AI (`SettingsView.java:58`, `:91-235`): bật/tắt, chọn provider, Ollama endpoint + model,
  nút kiểm tra kết nối (`:127-161`, `:184-188`), Kilo base URL + **API key chỉ lưu local** + model (`:167-193`),
  toggle "đưa dữ liệu của tôi làm ngữ cảnh" (`:225-227`), **trình duyệt model** ("Lấy danh sách model" + search + chọn,
  `:195-322`), và **tải model Ollama (pull) sau prompt + xác nhận tường minh** (`:203-206`, `:328-350`).
- Assistant offline chạy bằng quy tắc, help liệt kê 13 họ lệnh (`RuleBasedEngine.java:164-180`).

**Cài đặt**
Interface `AiProvider{id, displayName, isAvailable, listModels, chat, supportsModelListing}`;
`AiManager` facade; `ChatMessage` record (`role` ∈ system|user|assistant, `content`); `ModelInfo` record;
`AiException extends AppException`.
Providers: `RuleBasedEngine` (479 dòng), `OllamaProvider` (183), `OpenAiCompatibleProvider` (143, nhãn "Kilo").
HTTP: `ai/AiHttp.java` — **chỉ `java.net.http`**, static shared client, connect timeout 10s,
`get`/`postJson` với `Authorization` header tùy chọn (`:25-44`), IOException/Interrupted → `AiException` (`:46-56`).
Persist: AI **không có store riêng**, config nằm trong `app-config.json` (`ConfigService.java:55`, `:75`);
`AppContext.persistAiSnapshot()` mirror thêm provider + `providers="rule-based,ollama,kilo"` + `updatedAt`
vào `ai/ai-config.json`, failure-tolerant (`AppContext.java:122-139`); SmokeMain xác minh trên đĩa (`:229`).

**Cơ chế provider / streaming / prompt / offline-vs-online**
- **3 provider, 1 interface**, resolve theo id với fallback về rule engine (`AiManager.byId` `:31-38`);
  `providers() = List.of(ruleEngine, ollama, kilo)` (`:27-29`).
- **Dựng prompt** (`AiManager.java:73-91`): system prompt **chỉ thêm khi provider đang dùng là LLM** —
  "Bạn là trợ lý năng suất cá nhân của ứng dụng LifeDesk. Trả lời ngắn gọn, tiếng Việt. …" (`:77-79`);
  **message system thứ hai** chứa `ruleEngine.contextSnapshot()` khi `includeUserData` bật (`:80-82`);
  history trim đuôi theo `max(2, maxHistoryMessages)` (`:84-88`), UI cũng tự trim (`AiChatView.java:152-157`).
- **Context snapshot** là digest **chỉ ở mức tổng hợp**: số lượng task, thu/chi trong tháng, 10 title ghi chú gần nhất,
  tên habit + streak, số thẻ đến hạn (`RuleBasedEngine.java:459-477`).
  ⚠️ Nó **gửi title ghi chú** tới provider cloud khi `includeUserData` bật — sự đồng ý của người dùng nằm ở đúng một checkbox đó.
- **Streaming chat: KHÔNG hỗ trợ.** Cả hai LLM provider hardcode `"stream": false` (`OllamaProvider.java:91`,
  `OpenAiCompatibleProvider.java:99`); `chat()` trả một `String` đầy đủ ⇒ UI hiện bubble "…đang xử lý" rồi thay một lần
  (`AiChatView.java:128`, `:137`). **Streaming duy nhất tồn tại** là tải model Ollama: `POST /api/pull` với `stream:true`,
  `BodyHandlers.ofLines()`, progress từng dòng kèm % + bytes, timeout 30 phút, bỏ qua dòng malformed
  (`OllamaProvider.java:121-173`), nối ra UI qua `Fx.async` + progress consumer (`SettingsView.java:344-346`).
- **Endpoints**: Ollama `GET /api/tags` (probe availability 3s + list model, `:43-70`), `POST /api/chat`, `POST /api/pull`;
  Kilo `GET {modelsPath}`, `POST {chatPath}`, **cả hai path user-override được** (`OpenAiCompatibleProvider.java:135-138`)
  ⇒ tương thích **mọi** endpoint OpenAI-compatible, không riêng Kilo. Xử lý cả shape `data[]` lẫn `models[]`,
  cả entry object lẫn primitive (`:71-87`); 401/403 map tường minh "API key không hợp lệ" (`:63-65`, `:110-112`).
- **Offline**: `RuleBasedEngine` **không có network I/O nào** (`isAvailable()` → `true` `:78-81`;
  `listModels()` trả một `rule-engine-1.0` synthetic `:83-91`). Hoạt động bằng regex + keyword dispatch trên service thật
  (`:37-43` patterns, dispatch chain `:106-162`) và **từ chối giả làm LLM**: input không khớp trả lời trung thực
  "nằm ngoài khả năng", chỉ tới Ollama/Kilo (`:182-185`, class doc `:29-34`).
- Rule engine extras: match không dấu qua `TextSearch.normalize` (`:124`); **"tạo task: X hạn mai"** tách date
  hỗ trợ `hôm nay / mai / 2026-01-31 / 31/12` (`DUE_SUFFIX` `:43`, `parseDue/parseDate` `:205-228`) rồi ghi qua `TaskService` (`:200`);
  **summarizer rút trích** dán nhãn rõ "RÚT TRÍCH (không dùng LLM)" dùng heading + word-frequency sentence scoring (`:360-422`);
  gợi ý nối overdue + today + cards due + habit missed hôm qua + budget deficit (`:424-456`).
- Async: `AiManager.chatAsync(executor, …)` chạy trên pool caller với `onDone/onError` (`:57-71`);
  UI truyền `ctx.executors().ai()` và chống re-entrancy bằng `volatile busy` (`AiChatView.java:33`, `:125-149`).
  Provider error render thành bubble gợi ý hành động (chuyển sang offline engine) (`:143-148`).
- Probe availability cố ý rẻ: GET 3s (Ollama) / 8s (Kilo), exception nuốt thành `false` (`:43-49`, `:51-57`).

**Hiện trạng: HOÀN CHỈNH cho phạm vi tuyên bố; 2 absence hữu ý nhưng đáng nêu.**
- **Không streaming chat** (mục trên) — chỉ request/response.
- **Không tool-calling / function-calling / plan generation**: AI không bao giờ thấy Tool Registry và không bao giờ
  sinh `AgentPlan`; `AiProvider.chat` trả text thuần (`AiProvider.java:22`). Cộng với field `origin="ai:<provider>"`
  không ai ghi ⇒ **cây cầu AI↔agent đã được thiết kế trên giấy nhưng chưa dựng**.
- Minor: `AiManager.setEnabled` (`:52-54`) mutate config nhưng **không gọi `persistAiSnapshot()`**; `SettingsView`
  dựa vào đường save riêng (`:97-99`) ⇒ snapshot tươi hay không phụ thuộc việc user bấm Save.
- **Vấn đề responsiveness thật**: probe availability gọi **trên JavaFX thread** trong `refreshStatus()`
  (`AiChatView.java:103`, `p.isAvailable()`) ⇒ có thể **block UI tới bằng timeout probe** (8s cho Kilo) khi user mở AI view.
- Không có marker TODO/FIXME/stub/mock trong `ai/`; không có reply LLM giả — mọi câu trả lời engine tính từ service thật,
  provider fail thì ném `AiException` chứ không trả text dựng sẵn (`:104-111`, `:113-127`).

**Test coverage: thấp nhất dự án — package `src/test/java/com/lifedesk/ai/` KHÔNG TỒN TẠI.**
Chỉ được phủ gián tiếp: `app/AppIntegrationTest.java:74-80` `ruleEngineAnswersFromRealData` (một assert:
`chat([user("việc hôm nay")])` chứa title task vừa tạo), và `SmokeMain.java:135-143` (engine non-null/available,
"việc hôm nay" chứa task thật, "chi tiêu tháng này" chứa "Chi tiêu", **kiểm tra tính trung thực**:
`handle("thời tiết ngày mai thế nào nhỉ")` chứa "ngoài khả năng").
**Chưa test**: hai provider Ollama/Kilo (request body, error mapping, 401, model-list shape tolerance), `pullModel`
progress parsing, `AiHttp`, `AiManager.buildMessages` (gate system prompt, `includeUserData`, history trim),
`activeModel()`, `contextSnapshot()`, regex tạo task + date parser, summarizer, mọi command branch khác.

---

### III.10 Dashboard — `ui/views/DashboardView.java`

467 dòng, **10 widget bật/tắt và sắp xếp được**: `w_today, w_overdue, w_upcoming, w_finance, w_budget, w_habits,
w_goals, w_learning, w_automation, w_health` (`:156-165`) + bộ lọc khoảng ngày (ngày/tuần/tháng/custom).
Cấu hình widget lưu trong `AppConfig.dashboardWidgets` + `dashboardWidgetOrder`, **khởi tạo rỗng** —
preference mặc định được resolve lúc render nên không cần migrate. Đây là view tổng hợp, gọi thẳng service của
tasks/finance/habits/learning/health/automation, và là nơi `AUTO_TASKS` progress của goal được tính (`:365+`).

### III.11 Search — `src/main/java/com/lifedesk/search`

**Tính năng**: tìm kiếm toàn cục từ ô trên topbar (Enter) (`ui/MainWindow.java:128-133`, `:174`) → view SEARCH;
kết quả **group theo module**, có chip filter type, sort theo score, cap 30/nhóm, snippet 120 ký tự,
double-click mở bản ghi (`ui/views/SearchResultsView.java:105-115`). Cũng là năng lực "search" của rule engine AI
(`RuleBasedEngine.java:50-56`).

**Cài đặt**: `SearchService` + record `SearchDoc`, `SearchGroup`, `SearchResult`; wire `AppContext.java:88-89` với 6 service.
`rebuildAll()` (`:63-77`) pull dữ liệu sống qua `indexTasks/indexNotes/indexFinance/indexHabits/indexHealth/indexLearning`
(`:95-154`), dựng `docs` map + `termIndex`, persist cache, log số doc. Nội dung note cắt tại **20 000 ký tự** (`:107-109`).
`query()` (`:169-224`) theo **AND-semantics** trên term đã normalize, score **title×3 / tags×2 / content×1**, top-200 hit,
group theo type, sort theo kích thước nhóm. Refresh: `AppContext.markSearchDirty()` debounce rebuild **2s** sau mọi
thay đổi module (`:141-155`); startup `loadPersisted()` rồi `rebuildAll()` trên IO executor (`:175-181`).
Cache `index/search-index.json` schema v1, write `:232-240`, read `:243-260`, fallback mềm nếu persist lỗi (`:237-239`).

**Hiện trạng: HOÀN CHỨC, kèm 2 đoạn code chết/aspirational.**
- **`termIndex` unused**: build bởi `rebuildTermIndex()` (`:79-86`), load trong `loadPersisted()` (`:258`),
  nhưng `query()` (`:169-224`) **không đọc** — search hiện là linear scan qua `docs`. Chỉ là overhead vô ích.
- **Type `SearchDoc.WORKFLOW` aspirational**: hằng số (`SearchDoc.java:13`), label "Automation" (`:25`),
  nhánh điều hướng `case "WORKFLOW" -> showView(ViewId.AUTOMATION)` (`MainWindow.java:298`) — nhưng `rebuildAll()`
  **không index workflow bao giờ** ⇒ kết quả WORKFLOW không thể xuất hiện. Dead branch.
- **Không có unit test `SearchService`** (không tồn tại `src/test/java/com/lifedesk/search/`); chỉ có
  `AppIntegrationTest.java:29` `crossModuleSearchIndexWorksOffline` (group TASK+NOTE với filter type) và rebuild trong smoke.
  Scoring, AND-semantics, cache persistence đều **chưa test đơn vị**.

---

<div style="page-break-before: always;"></div>

## IV. Hệ thống phụ trợ

### IV.1 Notifications — `src/main/java/com/lifedesk/notifications`

**Tính năng**: bell icon + unread badge trên topbar, dropdown notification in-app, đánh dấu tất cả đã đọc, xóa hết,
click để điều hướng tới bản ghi được tham chiếu (`MainWindow.java:167` badge listener, `:323` unread count,
`:349`/`:354`/`:389` markAllRead, `:354` clear, `:366` render list).
Nhà sản xuất cụ thể: message chào mừng + cảnh báo lỗi storage (`MainWindow.java:436`, `:444`) và reminder task hằng ngày
(`app/ReminderScheduler.java:159`). **Không có OS toast theo thiết kế** — comment tại `NotificationService.java:14`.

**Cài đặt**: `NotificationItem` POJO mutable — `id` (auto `Ids.newId()` `:19`), `title`, `message`,
`severity` INFO/WARNING/ERROR, `createdAt`, `read`, `refView`/`refId` (`:7-17`).
`NotificationService`: deque in-memory **cap `MAX_ITEMS = 200`** (`:16`, trim `:53-55`); `push` thêm đầu + persist +
fire `ChangeNotifier` (`:50-59`); `all/unreadCount/markAllRead/clear` (`:61-87`); load khi construct (`:31-42`);
persist `data/notifications/notifications.json` key `items` schema v1 (`:34`, `:47`).

**Hiện trạng: HOÀN CHỨC** (tối giản nhưng đúng chức năng đã tuyên bố). Không TODO/stub.
Ghi nhận: không có mark-read từng item (chỉ mark-all) và không xóa từng item (chỉ `clear()`) — nhất quán với UI cung cấp;
`severity` là String tự do thay vì enum (`NotificationItem.java:11`); `unreadCount` duyệt cả deque mỗi lần gọi (OK ở cap 200).
**Reminder dedupe**: key theo ngày persist tại `notifications/reminders-sent.json` (`ReminderScheduler.java:34`, `:76-81`)
nên restart không spam lại; logic chạy mỗi 60s (initial delay 20s) trên shared scheduler (`:84`), xử lý overdue,
due-window theo `reminderMinutesBefore` (**có xử lý wrap qua nửa đêm bằng `ChronoUnit.MINUTES`** `:121-123`),
"today sau 09:00" cho task không giờ, và habit-time+30phút. Javadoc tại `:28-30` nói rõ "no OS toasts, fully offline".
**Test**: không có `NotificationServiceTest`; chỉ gián tiếp qua `AppIntegrationTest.java:83`
`reminderSchedulerFiresOncePerTaskPerDay`.

### IV.2 Security — `src/main/java/com/lifedesk/security`

**Tính năng**: checkbox deny từng tool ở Settings (`SettingsView.java:488-490`), marker DENIED trong Automation view
(`AutomationView.java:155`). **Audit log JSONL không có UI viewer** — user chỉ thấy step record trong lịch sử chạy.

**Cài đặt**: `SecurityPolicy` là hạt nhân quyết định (`Decision.checkPlanStep` `:86-98`): tool lạ → DENIED;
tool bị deny → DENIED; READ → ALLOWED; WRITE → ALLOWED nếu plan đã duyệt trước, else NEEDS_APPROVAL;
DESTRUCTIVE → luôn NEEDS_APPROVAL (`:96`). **Chỉ tồn tại DENY override theo thiết kế** (comment `:37-38`, `setDenied` `:67-75`)
để không đường nào cho tool tự nâng quyền. Persist `security/policy.json` (`:32`, load `:45-56`, persist `:58-64`).
`PathGuard` static confinement: `isInside` normalize+absolutize candidate và từng granted root (`:16-28`),
`requireInside` ném message VN (`:31-36`). Consumer: `FileManagerService.java:77`, `:81` và tool `file.search`
(`InfoTools.java:61`).
`AuditLogger`: append-only `data/automation/audit-YYYYMM.jsonl` (constructor `:33`, filename pattern `:53`);
mỗi entry ts/runId/stepId/tool/params sanitized/outcome/message/durationMs (`:41-52`); `sanitize()` mask
`api_key|token|secret|password|authorization` → `***` và cắt 200 ký tự (`:27-28`, `:66-94`).

**Hiện trạng: HOÀN CHỨC, 2 caveats đáng chú ý.**
- `SecurityPolicy.checkPath()` (`:101-103`) là delegation **thừa, dường như không dùng** — call sites thật gọi `PathGuard` trực tiếp.
- `PathGuard` dùng `normalize()`, **không dùng `toRealPath()`** (`:20-23`) ⇒ chống được `..` traversal nhưng
  **không chống symlink escape**. Comment đầu lớp (`:6-9`) tuyên bố "…and symlink tricks" — **overstate so với implement**.
- Tuyên bố "không code path nào tắt audit" đứng vững: `AuditLogger` không có flag enable/disable, và `registerTools`
  (`AppContext.java:157-169`) không mở tool ghi vào security dir.
**Test**: không có class test riêng; phủ qua `AgentPolicyTest` (9 test, gồm `auditParamsAreSanitized`) và
`JsonStoreTest` (chặn traversal). Chưa có test PathGuard normalization/symlink hay `AuditLogger` file output riêng.

### IV.3 Backup — `src/main/java/com/lifedesk/backup`

**Tính năng** (Settings → "Dữ liệu & Backup"): "Tạo bản sao lưu ngay" (`SettingsView.java:361`),
"Kiểm tra toàn vẹn" (`:371`), Restore từ zip chọn tay sau khi checksum pass (`:408`).

**Cài đặt**: **một lớp duy nhất, stateless** `BackupService.java` (instantiate thẳng ở `AppContext.java:62`, dùng ở `SmokeMain.java:214`).
- `backup()` (`:38-79`): walk data dir, **loại trừ** `.lifedesk.lock`, `*.tmp`, và mọi thứ dưới `backups/`
  (`isExcluded` `:81-88`); zip thành `lifedesk-backup-yyyyMMdd-HHmmss.zip` qua tmp+move (`:44-72`);
  mỗi entry ghi vào `manifest.json` kèm size + **SHA-256 nội dung UTF-8** (`:64-66`); rồi `pruneOldBackups(..., 20)`
  giữ 20 bản mới nhất (`:74`, `:90-102`).
- `verify()` (`:131-156`): đọc zip một lần (`readZip` `:111-129`, ném nếu thiếu manifest `:125-127`),
  check presence + hash mọi entry trong manifest, và **mismatch số entry vs manifest** (`:148-150`).
- `restore()` (`:166-208`): **từ chối nếu verify fail**, ném `AppException` trước khi chạm đĩa (`:167-171`);
  file trên đĩa **không có trong backup** được **chuyển sang** `data/backups/orphaned-<ts>/` thay vì xóa (`:179-193`);
  extract từng entry atomic với `.restore-tmp` + REPLACE_EXISTING move (`:195-202`);
  trả `RestoreReport(restored, orphaned, problems)`.
- `integrityCheck()` (`:210-227`): parse mọi `.json` dưới data dir, trả danh sách path hỏng (rỗng = khỏe).

**Hiện trạng: HOÀN CHỨC.** Không TODO/empty catch.
Ghi chú kỹ thuật: hashing chạy trên `new String(bytes, UTF_8)` (comment `:64-65` thừa nhận đây là content hash hướng
tới text file) — payload **binary** trong data dir sẽ round-trip lossily nhưng deterministic; chấp nhận được vì toàn bộ
dữ liệu là JSON/MD. List `problems` trong `restore()` chỉ được thêm ở `:203-205` ⇒ restore **abort giữa đường**
chứ không gom lỗi từng file.
**Test**: `backup/BackupServiceTest.java` **4 test** — full backup→verify→corrupt→restore→integrity round trip (`:28`),
phát hiện giả mạo qua checksum (`:56`), restore từ zip rác ném lỗi và **data không bị chạm** (`:84`),
file non-JSON được backup nhưng bỏ qua integrity check (`:99`). Backup là module phụ trợ được test tốt nhất.

### IV.4 Theme manager & Settings

`ThemeManager` đã trình bày ở II.6. `ui/views/SettingsView.java` (562 dòng) 6 tab:
**Giao diện** (theme + sidebar) · **AI** (provider, fetch/search model, Ollama pull) · **Dữ liệu & Backup** ·
**Quyền truy cập tệp** (allowed roots) · **Agent & Nhắc việc** (giới hạn agent, tool toggles, reminder) · **Giới thiệu**.
Ghi nhận một smell: adapter `AiProvider` ẩn danh inline cho nút "Kiểm tra kết nối Ollama" trả `id()="x"`,
`displayName()=""` (`SettingsView.java:129-133+`) — chạy được nhưng là throwaway, không refactor-clean.

### IV.5 UI components & shell

`ui/components/`: `Card` (container có title, theo theme), `Chips` (chip màu cho task status, priority,
agent run status, overdue), `EmptyState` (placeholder rỗng/loading/error kèm nút hành động optional),
`NoteLinkPicker` (gắn/tách note vào bản ghi module khác, persist vào `NoteMeta.links`).
`ui/Dialogs` — dialog có theme, nhận `globalStylesheets` gắn lại lên mọi dialog (nên dialog không lệch theme).
`ui/MainWindow` — shell: topbar + sidebar + ScrollPane content + statusbar, default view DASHBOARD,
điều hướng, popup notification, agent approvals. **Agent approval** bridge `ApprovalService` sang modal
`confirmWithDetails` với **timeout 5 phút, mặc định từ chối** (`MainWindow.java:415-432`).
**Storage health đưa lên UI**: listener `StorageIssueLog` trong `onReady()` (`:438-447`) ghi cảnh báo status bar
và push notification ERROR.
Geometry cửa sổ lưu khi đóng (`LifeDeskApp.java:82-93`).

### IV.6 Log & scripts đóng gói

`src/main/resources/logback.xml` (resource **duy nhất** ngoài CSS — **không có image/icon/font nào trong repo**,
mọi icon là emoji text): console appender + rolling file `${lifedesk.log.dir:-~/.lifedesk/logs}/lifedesk.log`,
10 MB/file, history 14 ngày, cap 200 MB, `com.lifedesk` ở DEBUG.

`scripts/run.ps1` — dev launch: tự định vị root, hardcode JDK `C:\Program Files\Java\jdk-25.0.4.1\bin\javaw.exe`
kèm fallback PATH (`:6-12`), `Start-Process javaw -cp target\classes;target\lib\* com.lifedesk.app.Launcher` với UTF-8 (`:14-18`).
**Yêu cầu `mvn compile` trước** — script không build.
`scripts/package.ps1` — pipeline đầy đủ: tự định vị (`:32-33`), resolve JDK qua param/JAVA_HOME/PATH (`:37-43`),
gate test tùy chọn `-RunTests` (`:46-50`), `mvn -q package -DskipTests` (`:54`), ráp `target\dist` = jar + `target\lib` (`:59-62`),
xóa app-image cũ (`:66-71`), `jpackage --type app-image|msi --main-class com.lifedesk.app.Launcher`
kèm UTF-8 + native-access java-options (`:73-85`). Output `target\jpackage\LifeDesk\LifeDesk.exe` hoặc `LifeDesk-1.0.0.msi`.
`scripts/screenshot.ps1` — capture cửa sổ đang chạy: C# P/Invoke inline (`EnumWindows`/`GetClassName`/`PrintWindow`)
tìm window "LifeDesk" visible lớn nhất không phải console (`:20-38`), dùng flag **`PW_RENDERFULLCONTENT`**
(comment `:56` giải thích: **cần cho JavaFX DirectComposition window**, chạy được kể cả khi window bị che),
lưu `target\live-dashboard.png`, guard `NO_LIFEDESK_WINDOW` / `BAD_SIZE` (width < 600).
`scripts/patch_batch3.py` — **không thuộc pipeline run/package**: dev source-patcher một lần (`patch()` assert literal rồi thay một lần).
Đã áp dụng edits cho MainWindow (dialog theme sync), HealthView layout fill, LearningView course-materials table +
per-deck due stats, LearningService (`addMaterial/removeMaterial/materials/dueCountForDeck`), Course (`materialPaths`).
**Đã kiểm cả hai signature edits đều có sẵn trong tree** (`MainWindow.java:306`, `Course.java:21`) ⇒ script đã xài xong
và **bây giờ sẽ fail assert nếu chạy lại**; nó tồn tại như lịch sử của một batch patch đã hoàn thành, không phải công cụ đang dùng.

---

<div style="page-break-before: always;"></div>

## V. Test & độ phủ

### V.1 Kiểm kê thực đo (`@Test` đếm trực tiếp trong `src/test/java`)

| Suite | Số test | Phạm vi chính |
|---|---:|---|
| `finance/FinanceServiceTest.java` | **11** | validate amount/category, **tổng BigDecimal chính xác từng digit**, same-day dedupe vs cross-day + force flag, recurring budget + override, CSV export/import round-trip dedupe, CSV lỗi dòng + báo cáo, budgetStatus ratio, rename/delete guards, debt lifecycle |
| `automation/AgentPolicyTest.java` | **9** | tool lạ bị denial (`shell.exec`/`process.run`), WRITE cần duyệt plan, deny-list chặn cả plan, destructive không handler ⇒ CANCELLED **+ note còn tồn tại**, destructive có approval ⇒ COMPLETED + note mất, step limit, E2E READ+WRITE tạo task thật, ma trận 6 nhánh policy, audit sanitize |
| `notes/NotesServiceTest.java` | **8** | CRUD + file `.md` trên đĩa, conflict copy, rename giữ id, move folder, search content+tags, wiki/tag parser, cross-module link, index persist qua reload |
| `tasks/TaskServiceTest.java` | **7** | validate title, CRUD, **daily recurrence sinh phiên kế**, weekly chọn đúng thứ, due-today/overdue/upcoming, completedAt, search không dấu |
| `storage/JsonStoreTest.java` | **7** | atomic write, envelope `schemaVersion`, **`.bak` recovery**, corrupt quarantine + error log, text read/write/delete, **chặn path traversal**, concurrency 8 thread |
| `habits/HabitServiceTest.java` | **7** | streak ngày theo lịch, quy tắc gap, retroactive bắt buộc reason + changelog, completion-rate chỉ ngày lịch, weekly summary, goal auto-progress, goal history |
| `markdown/MarkdownServiceTest.java` | **7** | escape HTML/script XSS, strip `javascript:`, tables/headings/lists/code, task-list checkbox, wiki link → `lifedesk://note/<id>` + fallback, plain-text extraction, wrap page có màu theme và **không script** |
| `app/AppIntegrationTest.java` | **5** | cross-module search TASK+NOTE + filter, **data sống qua AppContext restart**, single-instance lock chặn writer thứ hai, rule engine trả lời từ dữ liệu thật, reminder fire 1 lần/task/ngày + key persist |
| `health/HealthServiceTest.java` | **4** | metric rename+target, chặn trùng tên, **delete metric scrub khỏi mọi entry**, upsert range validation |
| `learning/LearningServiceTest.java` | **4** | deck rename + chặn blank, card edit guards, session validate + delete twice, **Leitner đi hết 6 interval + cap box + reset khi sai** |
| `backup/BackupServiceTest.java` | **4** | full round-trip, tamper detection, invalid-zip restore không chạm data, non-JSON backed up nhưng bỏ qua integrity |
| **Tổng** | **73** | |

**Package test tồn tại**: `app, automation, backup, finance, habits, health, learning, markdown, notes, storage, tasks` (11 class).

### V.2 Khoảng trống phủ test

| Chưa có class test trực tiếp | Ghi chú |
|---|---|
| **`ai/`** | **package không tồn tại** — ít coverage nhất; 2 provider, `AiHttp`, `buildMessages`, date parser, summarizer, mọi command branch đều chưa test |
| **`files/`** | **package không tồn tại** — PathGuard rejection, favorites, search filter, cancel/allowedRoots chưa được kiểm |
| **`search/`** | scoring, AND-semantics, cache persistence chỉ có 1 integration test |
| **`notifications/`** | chỉ gián tiếp qua `AppIntegrationTest:83` |
| **`security/`** | PathGuard normalization/symlink và `AuditLogger` file output không có test riêng |
| **`theme/`, `config/`, `ui/`** | không unit test (đúng, vì cần JavaFX runtime) — bù bằng UI smoke screenshot |

Các rail time-based của agent (step timeout, wall-clock deadline, loop guard, retry, workflow `checkDue`)
**về cấu trúc không test được** vì `docs/TESTING.md:11` tuyên bố **không mock thời gian**.

### V.3 Smoke & UI smoke — `app/SmokeMain.java`

`app/SmokeMain.java` có hai entry point độc lập (wire qua `exec-maven-plugin`, `pom.xml:165-171`):

- **`services`** — `runServicesSmoke()` (`:49`): dựng `AppContext` trên thư mục tạm rồi chạy **40 check**:
  CRUD từng module, **khôi phục file hỏng** (cố tình ghi rác vào `tasks/tasks.json`), backup/restore,
  agent policy, BigDecimal tổng + CSV dedupe, Leitner, AI honesty check, search rebuild, audit file tồn tại,
  persist qua reload, `ai-config.json` trên đĩa.
- **`ui`** — `runUiSmoke()` (`:264`): `Application.launch(LifeDeskApp.class, …)` thật, chờ cửa sổ hiện,
  rồi `Scene.snapshot()` từng view (snapshot scene nên không bị cửa sổ khác che).

Ba bổ sung phục vụ đợt kiểm chứng 2026-10-27:

| Bổ sung | Tác dụng | Vị trí |
|---|---|---|
| `-Dlifedesk.seed=<dir>` | copy một bộ dữ liệu mẫu vào thư mục tạm trước khi chạy, nên ảnh là view **có dữ liệu** thay vì empty state | `copyTree` `:470` |
| Guard layout | in `LAYOUT <view> viewport=… content=…` và gắn cờ `OVERFLOW` khi nội dung rộng hơn viewport | `:418` |
| Assert phím tắt | `Ctrl+1..Ctrl+0` phải có trong `Scene.getAccelerators()` (kiểm tra đăng ký, không phải mô phỏng gõ phím) | `:356` |

Kết quả đã chạy: `services` **40/40 PASS**; `ui` render 10 view + dashboard, **0 OVERFLOW**, accelerator **10/10**.
`LifeDeskApp.primaryStageRef/mainWindowRef` (`LifeDeskApp.java:27-29`) tồn tại **chỉ phục vụ mode `ui`**.

### V.4 Kết quả kiểm chứng đã ghi nhận (`docs/TESTING.md`, lập 2026-10-25)

Tự định vị là báo cáo bằng chứng trung thực (dòng 4: "Không có kết quả nào dưới đây là bịa"). Môi trường §1:
JDK 25.0.4.1, Maven 3.9.16, Windows 10.0.26300. §2: `mvn test`/`package` **BUILD SUCCESS**; JUnit theo tài liệu
**72/72 PASS**; services smoke **`TESTING.md:43` header ghi "39/39" nhưng transcript `:47` lại "SMOKE RESULT: PASS (40 passed, 0 failed)"**
⇒ **README:98 ("40 check") mới là bản đúng, và TESTING.md tự mâu thuẫn nội bộ.** §2.4 UI smoke còn mâu thuẫn ba số
ngay trong cùng một mục: header dòng 53 ghi "**6 ảnh chụp**", thân mục liệt kê **10 file**
(`ui-smoke.png`, `-tasks`, `-notes`, `-finance`, `-habits`, `-health`, `-learning`, `-ai`, `-automation`, `-settings`),
rồi chốt "**tổng cộng 9 view**". Ảnh chụp bằng `Scene.snapshot()` nên không bị window-che ảnh hưởng.
§2.5/§3: **jpackage app-image 165 MB build thành công** nhưng `LifeDesk.exe` **bị Windows Smart App Control chặn
trên máy build vì exe chưa ký số** — tài liệu ghi rõ đây là giới hạn môi trường; **MSI chưa từng build** (máy thiếu WiX).
§3 liệt kê "chỉ kiểm tra tĩnh": gọi API Kilo/Ollama thật, MSI, scheduler soak 24/7, performance dữ liệu lớn,
multi-monitor/DPI. §4 map **18 tiêu chí nghiệm thu** tới bằng chứng.
Nhận định: TESTING.md là **báo cáo bằng chứng của run tự động/smoke**, không phải tài liệu test script thủ công —
không tồn tại scenario test tay từng bước nào trong repo.

---

<div style="page-break-before: always;"></div>

## VI. Đánh giá hiện trạng & khoảng trống tài liệu ↔ code

### VI.1 Bảng mức độ hoàn thiện

| Module | Trạng thái | Test trực tiếp | Defect/gap đã xác minh |
|---|---|---:|---|
| storage (`JsonStore`) | Hoàn chỉnh, chất lượng cao nhất | 7 | migration mọi store là no-op v1 (hạ tầng chờ) |
| tasks | Hoàn chỉnh | 7 (+integration) | — |
| notes + markdown | Hoàn chỉnh | 8 + 7 | `MarkdownPreviewPane` không test (FX runtime) |
| habits/goals | Hoàn chỉnh | 7 | goal auto-progress nằm ở view/dashboard (chủ ý) |
| health | Hoàn chỉnh | 4 | coverage mỏng; quirk format `:88-93` |
| learning | Hoàn chỉnh chức năng | 4 | comment "Box 7" contradict cap 6; `interval` tính rồi bỏ; không persist review history |
| finance | Hoàn chỉnh + **3 defect thật** | 11 | **year-boundary edit ⇒ NotFoundException**; fingerprints không prune; delete chỉ scan năm đã load |
| files | Code hoàn chỉnh | **0** | module ít được kiểm chứng nhất; không xóa/rename là chủ ý |
| automation | Hoàn chỉnh cho phạm vi | 9 | `StepRecord.paramsSanitized` field chết; **không có cầu AI→plan**; không tool cho finance/health; rail time-based chưa test |
| ai | Hoàn chỉnh cho phạm vi | **0** | không streaming chat, không tool-calling; **probe availability trên FX thread (block UI tới 8s)**; `setEnabled` không refresh snapshot |
| search | Hoàn chỉnh | 0 (1 integration) | `termIndex` chết; `WORKFLOW` dead branch |
| security | Hoàn chỉnh | 0 (qua AgentPolicy) | `checkPath()` thừa; **PathGuard không chống symlink dù comment tuyên bố** |
| backup | Hoàn chỉnh, test tốt | 4 | content hash theo UTF-8 string; restore abort thay vì gom lỗi |
| notifications | Hoàn chỉnh tối giản | 0 (1 integration) | không mark-read per-item; `severity` String tự do |
| dashboard/theme/ui | Hoàn chỉnh | 0 (smoke screenshot) | `View.onHide()` là hook chết — không view nào override |

**Kết luận xếp hạng theo bằng chứng**: `storage` ≈ `tasks`/`notes`/`habits` > `finance`/`automation` > `learning`/`backup` >
`files`/`search`/`security`/`notifications`/`ai` (không phải vì code hỏng, mà vì **chưa được test**).

**Không có marker TODO/FIXME/XXX nào trong toàn bộ `src/`** (đã grep). Hai hit `TODO` duy nhất là
`TaskStatus.TODO` trong text mô tả tool (`TaskTools.java:103`, `:153`) — không phải debt marker.

### VI.2 Khoảng trống README ↔ code

**README overstate / sai số liệu**
| Tuyên bố | Thực tế |
|---|---|
| `README.md:224` "61 test JUnit 5" · `README.md:37`, `:65` "72 test" · `docs/TESTING.md:22,25` "72/72" | **73 `@Test`** (đã đếm); `TESTING.md:33` còn ghi `FinanceServiceTest` có 10 trong khi thực tế **11**. **Ba con số khác nhau cho cùng một suite, và cả ba đều lệch so với tree.** |
| `README.md:98` "40 check" smoke vs `docs/TESTING.md:43` header "39/39" (dòng transcript `:47` lại nói "40 passed") | **README mới là bản đúng** — nội bộ TESTING.md tự mâu thuẫn |
| `README.md:116`, `:183` PathGuard chống traversal + (ẩn ý) symlink | `PathGuard.java:20-23` chỉ `normalize()`, không `toRealPath()` ⇒ **không chống symlink** |
| `README.md:88` và `:92` hiển thị `scriptsrun.ps1` | **Lỗi thật, nhưng không phải do người viết**: byte gốc là `scripts` + CR + `un.ps1` — kiểm bằng `cat -A` trên dòng 88 ra `scripts^Mun.ps1`. Chuỗi `\r` trong `scripts\run.ps1` đã bị một công cụ xử lý escape ăn mất, biến `\run` thành ký tự xuống-carriage + `un`. File thật là `scripts\run.ps1`; lỗi lặp ở cả dòng 88 và 92 |

**Code có nhưng tài liệu thiếu**
- Search index cả **HEALTH, GOAL, COURSE, FLASHCARD, TRANSACTION** (`SearchService.java:115-154`, `SearchDoc.java:5-13`)
  — blurb tìm kiếm `README.md:206` không nhắc tới.
- Notification center persist + welcome/alert storage-error (`MainWindow.java:436-444`) — **không tài liệu nào mô tả**.
- **Backup tự prune giữ 20 bản mới nhất** (`BackupService.java:74`, `:90-102`) — README mục 7 không nêu.
- `SmokeMain` với 40 check + UI screenshot, `scripts/screenshot.ps1` P/Invoke capture — không có trong README mục nào.
- 12 view, deep-link `openRecord`, dashboard widget reorder — tài liệu chỉ lướt.
- `docs/TESTING.md` không tồn tại mục "how to run a manual test scenario", nên user/qa không có đường reproduce thủ công.

**Ngược lại, README mô tả đúng code một cách đáng chú ý** (đã đối chiếu từng dòng):
`README.md:173-174` **11 tool registry, không có shell tool** — khớp chính xác `AppContext.java:159-171` + `SecurityPolicy.java:86-88`;
`README.md:205` Leitner box 1–6 với interval 1/2/4/7/15/30 + phiên ôn Đúng/Sai — khớp `LearningService.java:25`, `:316-330`;
`README.md:133-147` bố cục data dir — khớp mọi hằng đường dẫn trong code;
`README.md:149-154` quy tắc an toàn dữ liệu — khớp `JsonStore.java` từng điều khoản;
`README.md:188-194` backup/restore/orphaned/integrity — khớp `BackupService.java:38-227`;
`README.md:176-177` phân quyền 3 mức + deny-only + unknown denied — khớp `SecurityPolicy.java:86-98`.

### VI.3 Rủi ro kỹ thuật cần ưu tiên xử lý

1. **[High · user-facing bug] Finance year-boundary edit** — `FinanceService.java:319` → `:328`.
   Người dùng sửa ngày giao dịch qua ranh giới năm gặp `NotFoundException`. Cần logic di chuyển bản ghi
   giữa partition `transactions-YYYY.json` (remove from old year, add to new year, persist cả hai).
2. **[High · data integrity] Fingerprints append-only, không prune/recompute** — `FinanceService.java:331-340`, `:314-329`.
   State dedupe phân kỳ vĩnh viễn khỏi sổ kế toán; xóa rồi thêm lại cùng bản bị chặn oan.
3. **[Medium · UX/UI thread] AI availability probe trên JavaFX thread** — `AiChatView.java:103` + `p.isAvailable()`.
   Mở AI view khi Kilo không đáp có thể **đóng băng UI tới 8 giây**. Chuyển sang `ctx.executors().ai()`.
4. **[Medium · correctness] `deleteTransaction` chỉ quét năm đã lazy-load** — `FinanceService.java:332`.
   An toàn hôm nay chỉ vì UI gọi `all()` trước; là bẫy cho mọi caller mới (agent tool, import, test).
5. **[Medium · security doc/impl gap] PathGuard không chống symlink** — `PathGuard.java:6-9` vs `:20-23`.
   Hoặc dùng `toRealPath()`, hoặc sửa comment/tài liệu để không tuyên bố quá khả năng.
6. **[Low · dead code] `StepRecord.paramsSanitized`** (`:9`) never assigned nhưng UI in ra (`AutomationView.java:305`)
   ⇒ panel params luôn trống. **`SearchService.termIndex`** built+loaded rồi không dùng.
   **`SearchDoc.WORKFLOW`** branch không bao giờ khớp. **`SecurityPolicy.checkPath()`** không caller.
   **`View.onHide()`** hook không view nào override.
7. **[Low · missing bridge] `AgentPlan.origin = "ai:<provider>"` không đường ghi nào** (`AgentPlan.java:13-14` vs
   `WorkflowScheduler.java:135`, `:153`) — tính năng "AI đề xuất plan" chưa tồn tại dù chỗ đã chừa sẵn.
8. **[Low · consistency] Ba con số test khác nhau** (`README.md:65`, `:224`; `docs/TESTING.md:22`, `:33`) —
   một lệnh `grep -c "@Test"` là đủ sửa.
9. **[Low · doc rot] `scripts/patch_batch3.py`** giờ sẽ fail assert nếu chạy; nên chuyển vào `scripts/archive/` hoặc xóa.

### VI.4 Điểm mạnh thiết kế đáng giữ

- **Tầng service hoàn toàn JavaFX-free** → test headless thật sự, không mock FX; đây là lý do 73 test chạy được trong CI.
- **`JsonStore` là trung tâm durability**: fsync + `ATOMIC_MOVE` + `.bak` + quarantine + báo lỗi cho user
  (không im lặng), per-path lock, chặn traversal, envelope có `schemaVersion`. Đây là chuẩn mực đáng giữ cho mọi store mới.
- **Markdown pipeline có whitelist URL thật sự** thay vì thư viện chung chung ⇒ `javascript:`/`data:`/`file:` bị chặn ở tầng render.
- **10 đường ray an toàn agent**, nhất là hai điều: *deny-only policy* để tool không tự nâng quyền, và
  *auto-approve chỉ khả dụng cho plan toàn READ* (tính lại lúc chạy nên flag không stale).
- **Test của automation assert cả tác dụng phụ** (note còn/tồn tại sau khi từ chối, mất sau khi duyệt)
  thay vì chỉ assert status string — đây là kiểu test phát hiện được regression thật.
- **Tách theme structure/palette** ⇒ thêm theme bằng một file CSS, không đụng code, và dialog kế thừa theme đúng.
- **`BigDecimal` xuyên suốt finance** với test assert chính xác từng chữ số; fingerprint canonical hóa amount
  bằng `stripTrailingZeros().toPlainString()` để "1000.0" và "1000" không lọt khỏi dedupe.
- **Triết lý trung thực**: rule engine nói "ngoài khả năng" thay vì bịa; health từ tư tư vấn y tế một cách tường minh;
  app không tự tải model LLM nào mà không có confirm.

---

<div style="page-break-before: always;"></div>

## VII. Kết luận & khuyến nghị

### VII.1 Kết luận

LifeDesk **là một ứng dụng đã hoàn thiện, không phải khung skeleton hay prototype**. Toàn bộ 23 package trong
`src/main/java` đều có implementation thật, được nối trong `AppContext`, có UI view riêng và persist dữ liệu thật.
Không tồn tại TODO/FIXME/empty-body nào trong source; các "stub" duy nhất là no-op migration (hạ tầng chờ cho version
tương lai) và một throwaway adapter trong Settings phục vụ nút test kết nối.

Phạm vi thực sự đã build rộng hơn cách README mô tả: 12 view, 11 agent tool có phân quyền 3 tầng, 3 AI provider,
Leitner SRS, budget + debt + CSV dedupe, habit streak với audit log, notification center, single-instance lock,
backup/checksum/restore, theme mở rộng từ file CSS ngoài.

Hồ sơ rủi ro của dự án **không nằm ở code hỏng, mà ở bất đối xứng coverage và một lớp bug nhỏ**:
phần giá trị cao (agent policy, storage durability, finance arithmetic, markdown security) có test khá chắc;
trong khi ba vùng lại chưa có test trực tiếp nào — **`ai/`, `files/`, `search/`** — và chính `files`/`security`
là biên giới quyền-truy-cập, còn `ai` là đường ra ngoài máy (cloud provider). Song song, `finance/` chứa hai defect
thật đã xác minh bằng đọc code (year-boundary edit, fingerprint divergence) dù nó là module **được test nhiều nhất** —
minh chứng rằng test hiện hữu đang phủ happy path chứ chưa phủ các biên dữ liệu.

Số liệu test lệch giữa ba nguồn (61 / 72 / 73) và tài liệu PathGuard tuyên bố quá khả năng chống symlink là hai
vấn đề doc-rot cần sửa trước khi dùng tài liệu để nghiệm thu.

### VII.2 Khuyến nghị, theo thứ tự giá trị/rủi ro

**Nhóm 1 — Sửa bug user-facing (làm trước)**
1. `FinanceService.updateTransaction`: chọn partition theo **ngày cũ**, xóa khỏi năm cũ, thêm vào năm mới,
   persist **cả hai** file; kèm test regression "sửa giao dịch 31/12 → 01/01".
2. Prune/recompute fingerprint khi delete/update, hoặc chuyển sang suy dẫn fingerprint từ chính tập transaction
   hiện có thay vì giữ một file append-only. Kèm test "xóa rồi thêm lại cùng bản ⇒ được cho phép".
3. `deleteTransaction`: gọi `all()`/discover mọi năm trước khi scan để không phụ thuộc lazy-load state.
4. `AiChatView.refreshStatus()`: chuyển probe `isAvailable()` xuống `ctx.executors().ai()`, cập nhật UI qua `Fx.runLater`.

**Nhóm 2 — Dựng coverage cho ba vùng trắng**
5. `RuleBasedEngineTest` cho từng command branch (date parser `hôm nay/mai/YYYY-MM-DD/31-12`, summarizer rút trích,
   gợi ý, honesty fallback) — engine offline là AI mặc định mà user gặp, đáng được test nhất.
6. `OllamaProviderTest` / `OpenAiCompatibleProviderTest` trên HTTP **stubb được** (`com.sun.net.httpserver` trong JDK,
   không thêm dependency): request body, mapping 401/403, model-list shape `data[]` vs `models[]`, parse progress của `pull`.
7. `FileManagerServiceTest` + `PathGuardTest`: chặn root ngoài, favorites persist qua reload, từng bộ filter của
   `matches()`, `maxDepth`/`maxResults`, và **kể cả symlink escape** nếu quyết định chống nó.
8. `SearchServiceTest`: scoring title×3/tags×2/content×1, AND-semantics, độ không-dấu, cache persist + rebuild.

**Nhóm 3 — Dọn dead code & siết tài liệu**
9. Gán `StepRecord.paramsSanitized` khi tạo step record (audit đã có params sanitized — reuse), **hoặc** bỏ field
   và cho UI đọc từ JSONL audit.
10. Chọn một: hoặc dùng `termIndex` trong `query()` (nó tồn tại để làm gì đó), hoặc bỏ; hoặc index workflow cho khớp
    `SearchDoc.WORKFLOW` + nhánh `MainWindow:298`, hoặc xóa cả hai; xóa `SecurityPolicy.checkPath()`;
    hoặc dùng `View.onHide()` hoặc bỏ hook.
11. `PathGuard`: dùng `toRealPath()` để khớp comment, hoặc sửa comment + `README.md:116,183` bỏ tuyên bố symlink.
12. Sửa một lần các số test (`README.md:65,224`, `docs/TESTING.md:22,33`); sửa escape hỏng tại `README.md:88` và `:92`
    (`scripts<CR>un.ps1` → `scripts\run.ps1`); cân nhắc đổi tên một trong hai chỗ `config` lồng nhau
    (`ConfigService.java:45` vs `defaultConfigDir()`) để hết gây nhầm; chuyển `scripts/patch_batch3.py` sang `scripts/archive/`.
13. Sửa comment `LearningService.java:24` ("Box 7") cho khớp cap 6, và dùng biến `interval` ở nhánh sai đáp án (`:326-327`).

**Nhóm 4 — Việc nên cân nhắc trước khi mở rộng**
14. **Cây cầu AI → Agent plan** là tính năng rõ ràng đã được chừa chỗ (field `origin`, `AgentPlan`, `ToolDescriptor`
    có `inputSchema`) nhưng chưa build. Nếu làm, `AiProvider.chat` cần mở rộng sang tool-calling và **phải giữ nguyên**
    toàn bộ rail an toàn hiện có — plan do AI sinh ra bắt buộc đi qua `SecurityPolicy` + approval như plan do user dựng.
15. Mở rộng Tool Registry sang **finance/health** nếu muốn agent thực sự hữu ích — hiện agent mù hoàn toàn với hai module này.
16. Cân nhắc **keyboard shortcuts**: repo không có `KeyCombination`/`setAccelerator` nào; một app năng suất mà không có
    shortcut là khoảng trống trải nghiệm lớn hơn nó trông có vẻ.
17. **Performance**: mô hình "load all into memory, rewrite whole file per mutation" hoàn toàn ổn cho dữ liệu cá nhân,
    nhưng hãy đo trước khi user có ~50k transaction — ghi lại `transactions-YYYY.json` mỗi lần gõ một dòng sẽ thành độ trễ cảm nhận được.

### VII.3 Tiêu chí nghiệm thu của báo cáo này

| Tiêu chí | Kết quả |
|---|---|
| BAOCAO.md tồn tại, tiếng Việt | ✅ file này |
| Phủ đủ **100%** package trong `src/main/java` | ✅ 23/23: `ai, app, automation, automation/tools, backup, common, config, files, finance, habits, health, learning, markdown, notes, notifications, search, security, storage, tasks, theme, ui, ui/components, ui/views` (gộp trong các mục II–IV, gộp nhóm `tools` với `automation`, `components`/`views` với `ui`) |
| Mỗi nhận định hiện trạng có tham chiếu file | ✅ đường dẫn + số dòng cho mọi claim về defect, stub, config, persistence |
| Không tuyên bố đã chạy app/test | ✅ Mục V chỉ tường thuật bằng chứng do `docs/TESTING.md` ghi nhận; những gì tôi tự kiểm chứng (số `@Test` = 73, `paramsSanitized` không nơi nào gán, logic year-boundary tại `FinanceService.java:319`) đều là **kiểm tra tĩnh bằng grep/đọc source**, đã đánh dấu rõ ở đầu báo cáo |
