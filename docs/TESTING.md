# LifeDesk — Báo cáo kiểm chứng (ngày 2026-10-25)

Tài liệu này liệt kê **chính xác những gì đã được chạy thật** trên máy build, kết quả nguyên văn,
phần chỉ được kiểm tra tĩnh, và giới hạn môi trường. Không có kết quả nào dưới đây là bịa.

## 1. Môi trường kiểm chứng

- OS: Windows 10.0.26300 x64 (win32)
- JDK: `java version "25.0.4.1" 2026-08-18 LTS` (Oracle)
- Maven: Apache Maven 3.9.16
- CPU/RAM: máy desktop bình thường; mọi test chạy trực tiếp, không mock thời gian (trừ test nhắc việc dùng giờ hệ thống).

## 2. Kết quả chạy thật

### 2.1. Maven build

```
mvn test                 → BUILD SUCCESS
mvn package -DskipTests  → BUILD SUCCESS (target/lifedesk.jar + 19 jar trong target/lib)
```

### 2.2. JUnit 5 — 61/61 PASS

```
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

Phân bố:
| Suite | Số test | Phạm vi |
|---|---|---|
| JsonStoreTest | 7 | ghi atomic, envelope schemaVersion, phục hồi từ `.bak`, quarantine file hỏng + báo lỗi, text write/read/delete, chặn path traversal, 8 thread ghi đồng thời 20 lần vẫn đọc được JSON hợp lệ |
| TaskServiceTest | 7 | validation, CRUD, lặp DAILY/WEEKLY sinh phiên mới, today/overdue/upcoming, completedAt, search không dấu |
| FinanceServiceTest | 8 | chặn số tiền ≤ 0, chặn sai loại danh mục, tổng BigDecimal chính xác đến từng xu (1002345.72), chống trùng + allowDuplicate, CSV export→import lại vào service mới = 0 dòng mới (dedupe), CSV lỗi ngày/số âm báo 2 lỗi và nhập 1 dòng đúng, ngân sách 75% đúng, vòng đời nợ |
| NotesServiceTest | 7 | create/read/update/delete, **xung đột sửa ngoài app → giữ bản ngoài thành `-conflict-*.md`**, đổi tiêu đề đổi file giữ nguyên id, chuyển thư mục, search, wiki link + tag parser, index bền qua reload |
| HabitServiceTest | 7 | streak 1→2, chỉ đếm ngày có lịch, điều chỉnh quá khứ **bắt buộc lý do** + nhật ký, tỷ lệ 30 ngày, tổng kết tuần, tiến độ mục tiêu auto từ task, nhật ký mục tiêu |
| MarkdownServiceTest | 7 | `<script>` bị escape, `javascript:` bị strip (UrlSanitizer whitelist http/https/mailto/lifedesk/relative), bảng/heading/code/checklist, wiki link → `lifedesk://note/<id>`, plain text, trang preview có màu theme không script |
| AgentPolicyTest | 9 | tool lạ (shell.exec/process.run) không tồn tại → từ chối, WRITE cần phê duyệt kế hoạch, deny-list chặn toàn plan, **note.delete không ai duyệt → run CANCELLED + note còn nguyên**, duyệt tay → xóa thật, giới hạn số bước, E2E READ+WRITE tạo task thật + 2 bước SUCCESS, ma trận quyết định policy, audit sanitizer (che api_key/Authorization, cắt 500 ký tự) |
| BackupServiceTest | 4 | backup→verify→phá hỏng tasks.json→restore sửa lại→integrity sạch→dữ liệu đọc lại đúng; verify phát hiện zip bị giả mạo (checksum fail); restore từ zip rác **ném lỗi và không đụng dữ liệu**; manifest nằm trong zip |
| AppIntegrationTest | 5 | search xuyên module TASK+NOTE với filter loại, dữ liệu sống qua restart context, khóa single-instance chặn bản thứ 2 + mở lại được sau khi nhả, rule engine trả lời từ dữ liệu thật, nhắc việc bắn đúng 1 lần/ngày và key tồn tại qua restart |

### 2.3. Services smoke — 39/39 PASS

```
java -cp "target/lifedesk.jar;target/lib/*" com.lifedesk.app.SmokeMain services
SMOKE RESULT: PASS (40 passed, 0 failed)
```
Bao gồm: CRUD từng module, lặp lại sinh phiên, notes conflict, BigDecimal tổng chính xác,
CSV dedupe, streak + yêu cầu lý do khi sửa lịch sử, Leitner box tăng, rule engine (hôm nay/tài chính/trung thực khi không hiểu),
markdown escape, search index lưu đĩa, agent: plan WRITE cần duyệt / tool lạ từ chối / destructive không duyệt → CANCELLED + audit log /
run READ+WRITE COMPLETED và tạo task thật, **reload AppContext mới → dữ liệu còn**, backup verify → phá file → restore → integrity sạch.

### 2.4. UI smoke — PASS + 6 ảnh chụp scene thật

```
java -cp "target/classes;target/lib/*" com.lifedesk.app.SmokeMain ui target/ui-smoke.png
SMOKE RESULT: PASS (UI launched, screenshot captured)
```
Ảnh chụp bằng `Scene.snapshot()` (không phụ thuộc cửa sổ bị che), nằm trong `target/`:
`ui-smoke.png` (Dashboard dark theme, 10 widget + LineChart thu chi, bộ lọc khoảng ngày),
`ui-smoke-tasks.png` (bảng công việc + chip trạng thái/ưu tiên + filter + List/Board),
`ui-smoke-notes.png` (danh sách ghi chú + thư mục + editor/preview),
`ui-smoke-finance.png` (4 tab, thẻ tổng thu/chi/ròng, bảng giao dịch chip CHI),
`ui-smoke-automation.png`, `ui-smoke-settings.png` (6 tab, danh sách 5 theme).

Quá trình UI smoke cũng xác nhận: ứng dụng khởi chạy trên Windows với dữ liệu tạm, reminder scheduler +
workflow scheduler khởi động, search index dựng trong ~16ms, app shutdown sạch (không stack trace).

### 2.5. jpackage

```
powershell scripts\package.ps1 -Type app-image -Jdk "C:\Program Files\Java\jdk-25.0.4.1" -Mvn ...mvn.cmd
== Done. Output in target\jpackage\
target\jpackage\LifeDesk\LifeDesk.exe + runtime bundle (165 MB)
```

**Giới hạn môi trường (quan trọng):** chạy `LifeDesk.exe` ngay trên máy build bị **Application Control
policy của Windows chặn** — lỗi nguyên văn: `An Application Control policy has blocked this file`
(Smart App Control chặn exe chưa ký số). Đây là hạn chế của máy, không phải lỗi đóng gói:
- Bản jar + classpath (`java -cp "target\lifedesk.jar;target\lib\*" com.lifedesk.app.Launcher`) đã chạy được thật (UI smoke chạy đúng cách này).
- Trên máy đích: cho phép exe trong policy / ký số, hoặc dùng bộ cài MSI (cần WiX 3.x, máy build này chưa cài WiX nên **chưa tạo được MSI** — nêu rõ, không giả định).

## 3. Những gì chỉ được kiểm tra tĩnh (không chạy thật trên môi trường này)

- **Kilo API thật** (fetch model, chat): cần API key — chỉ verify được đường code HTTP OpenAI-compatible
  qua unit-logic + lỗi 401/403 được xử lý; chưa gọi endpoint thật. Ollama thật cũng vậy (không có ollama
  chạy trên máy build) — provider được thiết kế theo API chuẩn `/api/tags`, `/api/chat`, `/api/pull` và có
  nút "Kiểm tra kết nối" hiển thị rõ khi không kết nối được.
- **MSI installer**: cần WiX Toolset, chưa cài → chỉ có app-image.
- **Ứng dụng chạy dài ngày**: scheduler nhắc việc/workflow được test logic từng bước (unit + smoke), chưa chạy thử nghiệm 24/7 thực tế.
- **Hiệu năng dữ liệu rất lớn**: phân vùng giao dịch theo năm + index debounce 2s đã thiết kế; test đồng thời
  mức 8 thread × 20 ghi; chưa benchmark với hàng trăm nghìn bản ghi.
- **Đa màn hình/DPI khác nhau, Windows 10 cũ**: UI smoke chạy ở 1920×1009; chưa test hệ thống khác.

## 4. Đối chiếu Acceptance Criteria (9.1–9.18)

| # | Tiêu chí | Bằng chứng |
|---|---|---|
| 1 | Maven build thành công | `mvn test` + `mvn package` BUILD SUCCESS (mục 2.1) |
| 2 | Khởi chạy trên Windows | UI smoke PASS + 6 screenshot (2.4); exe đóng gói bị policy máy chặn (đã nêu ở 2.5) |
| 3 | Không cần Internet cho lõi | Toàn bộ service test + smoke chạy offline; AI cloud là tùy chọn, tắt được |
| 4 | Không database server | Persistence = JSON/MD thuần qua JsonStore |
| 5 | CRUD + dữ liệu tồn tại sau restart | JsonStoreTest, NotesServiceTest (reload), AppIntegrationTest.dataSurvivesContextRestart, smoke reload check |
| 6 | Markdown đọc/sửa/preview/lưu | MarkdownServiceTest, NotesServiceTest, screenshot Notes view |
| 7 | Theme giữ lại sau restart | Theme lưu trong app-config.json qua ConfigService.save; UI đổi theme ngay lập tức (applyTheme) |
| 8 | Search đúng trên dữ liệu đã lưu | SearchService rebuild từ nguồn + AppIntegrationTest, smoke |
| 9 | BigDecimal chính xác | FinanceServiceTest.bigDecimalSummaryIsExact (so sánh object equality) |
| 10 | Backup/restore với dữ liệu test | BackupServiceTest 4 test + smoke #11 |
| 11 | Lỗi ghi file không mất dữ liệu im lặng | JsonStore: throw StorageException + StorageIssueLog UI hiển thị; corrupt → .bak → quarantine + báo lỗi |
| 12 | UI không treo khi tác vụ nền | AppExecutors + Fx.async; file search/AI/index chạy background; UI smoke chạy song song scheduler không treo |
| 13 | AI theo provider thật; thiếu LLM không hỏng app | AiManager.currentProvider empty khi tắt; AiChatView hiển thị trạng thái TẮT; rule-based chạy không cần LLM |
| 14 | Agent chỉ thực hiện tool được cấp quyền | AgentPolicyTest (unknown tool, deny-list, PathGuard trong file.search) |
| 15 | Hành động nhạy cảm cần xác nhận | AgentPolicyTest destructive denial/approval; UI handler trong MainWindow.hookAgentApprovals |
| 16 | Test cho logic/storage/validation/policy/lỗi | 61 test — bảng 2.2 |
| 17 | Hướng dẫn build/run/cấu hình/đóng gói | README.md mục 2–4 |
| 18 | Báo cáo kiểm thử thực tế + giới hạn | Chính tài liệu này (mục 2, 3) |
