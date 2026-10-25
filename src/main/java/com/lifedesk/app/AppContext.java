package com.lifedesk.app;

import com.lifedesk.ai.AiManager;
import com.lifedesk.ai.RuleBasedEngine;
import com.lifedesk.automation.AgentService;
import com.lifedesk.automation.ToolRegistry;
import com.lifedesk.automation.WorkflowScheduler;
import com.lifedesk.automation.tools.InfoTools;
import com.lifedesk.automation.tools.NoteTools;
import com.lifedesk.automation.tools.TaskTools;
import com.lifedesk.backup.BackupService;
import com.lifedesk.common.AppExecutors;
import com.lifedesk.config.AppConfig;
import com.lifedesk.finance.FinanceService;
import com.lifedesk.files.FileManagerService;
import com.lifedesk.habits.HabitService;
import com.lifedesk.health.HealthService;
import com.lifedesk.learning.LearningService;
import com.lifedesk.markdown.MarkdownService;
import com.lifedesk.notes.NotesService;
import com.lifedesk.notifications.NotificationService;
import com.lifedesk.search.SearchService;
import com.lifedesk.security.AuditLogger;
import com.lifedesk.security.SecurityPolicy;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import com.lifedesk.tasks.JsonTaskRepository;
import com.lifedesk.tasks.TaskService;
import com.lifedesk.theme.ThemeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Composition root: builds every service with explicit constructor
 * injection, owns the executors' lifecycle and provides shutdown().
 * No global mutable state — everything flows through this context.
 */
public class AppContext {
    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final Path dataDir;
    private final AppConfig config;
    private final AppExecutors executors = new AppExecutors();
    private final StorageIssueLog issueLog = new StorageIssueLog();
    private final JsonStore store;
    private final ThemeManager themeManager;
    private final MarkdownService markdownService = new MarkdownService();

    private final TaskService taskService;
    private final NotesService notesService;
    private final FinanceService financeService;
    private final HabitService habitService;
    private final HealthService healthService;
    private final LearningService learningService;
    private final FileManagerService fileService;
    private final NotificationService notificationService;
    private final SearchService searchService;
    private final BackupService backupService = new BackupService();
    private final SecurityPolicy securityPolicy;
    private final AuditLogger auditLogger;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final AgentService agentService;
    private final WorkflowScheduler workflowScheduler;
    private final AiManager aiManager;
    private final ReminderScheduler reminderScheduler;
    private final java.util.concurrent.ScheduledExecutorService debounceScheduler;

    public AppContext(Path dataDir, AppConfig config) {
        this.dataDir = dataDir;
        this.config = config;
        this.store = new JsonStore(dataDir, issueLog);
        this.themeManager = new ThemeManager(dataDir);

        // Core productivity services
        this.taskService = new TaskService(new JsonTaskRepository(store));
        this.notesService = new NotesService(store);
        this.financeService = new FinanceService(store);
        this.habitService = new HabitService(store);
        this.healthService = new HealthService(store);
        this.learningService = new LearningService(store);
        this.fileService = new FileManagerService(store, config.allowedRoots);
        this.notificationService = new NotificationService(store);

        // Search over everything
        this.searchService = new SearchService(store, taskService, notesService,
                financeService, habitService, healthService, learningService);

        // AI
        this.ruleBasedEngine = new RuleBasedEngine(taskService, notesService, financeService,
                habitService, learningService, searchService, markdownService, healthService);
        this.aiManager = new AiManager(config.ai, ruleBasedEngine);

        // Automation
        this.securityPolicy = new SecurityPolicy(store);
        this.auditLogger = new AuditLogger(dataDir);
        registerTools();
        this.agentService = new AgentService(toolRegistry, securityPolicy, auditLogger,
                config.agent, store, executors.io());
        this.workflowScheduler = new WorkflowScheduler(store, agentService);

        // Reminders
        this.reminderScheduler = new ReminderScheduler(store, taskService, habitService,
                notificationService, config);
        this.debounceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lifedesk-index");
            t.setDaemon(true);
            return t;
        });

        // Wire change listeners to debounced search reindex
        taskService.changes().addListener(this::markSearchDirty);
        notesService.changes().addListener(this::markSearchDirty);
        financeService.changes().addListener(this::markSearchDirty);
        habitService.changes().addListener(this::markSearchDirty);
        healthService.changes().addListener(this::markSearchDirty);
        learningService.changes().addListener(this::markSearchDirty);

        // Mirror AI provider settings + metadata into data/ai/ (schema-versioned store).
        persistAiSnapshot();
    }

    /** Write a copy of the AI provider configuration to data/ai/ai-config.json. */
    public void persistAiSnapshot() {
        try {
            com.google.gson.JsonObject data = com.lifedesk.storage.GsonFactory.create()
                    .toJsonTree(config.ai).getAsJsonObject();
            com.google.gson.JsonObject envelope = new com.google.gson.JsonObject();
            envelope.add("ai", data);
            envelope.addProperty("updatedAt", java.time.Instant.now().toString());
            envelope.addProperty("providers", "rule-based,ollama,kilo");
            store.write("ai/ai-config.json", 1, envelope);
        } catch (RuntimeException e) {
            LOG.warn("Persisting AI snapshot failed (AI still works from app config): {}", e.toString());
        }
    }

    private final RuleBasedEngine ruleBasedEngine;
    private volatile boolean searchDirty = false;

    private void markSearchDirty() {
        searchDirty = true;
        debounceScheduler.schedule(() -> {
            if (searchDirty) {
                searchDirty = false;
                try {
                    searchService.rebuildAll();
                } catch (RuntimeException e) {
                    LOG.warn("Search reindex failed: {}", e.toString());
                }
            }
        }, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void registerTools() {
        toolRegistry.register(TaskTools.create(taskService));
        toolRegistry.register(TaskTools.list(taskService));
        toolRegistry.register(TaskTools.updateStatus(taskService));
        toolRegistry.register(TaskTools.search(taskService));
        toolRegistry.register(NoteTools.create(notesService));
        toolRegistry.register(NoteTools.search(notesService));
        toolRegistry.register(NoteTools.delete(notesService));
        toolRegistry.register(NoteTools.saveContent(notesService));
        toolRegistry.register(InfoTools.fileSearch(fileService));
        toolRegistry.register(InfoTools.habitLog(habitService));
        toolRegistry.register(InfoTools.dashboardSummary(new InfoTools.DashboardSources(
                taskService, financeService, habitService, learningService)));
    }

    /** Background warmup: search index + schedulers. Call once after UI shows. */
    public void startBackgroundServices() {
        executors.io().execute(() -> {
            try {
                searchService.loadPersisted();
                searchService.rebuildAll();
            } catch (RuntimeException e) {
                LOG.warn("Initial search index failed: {}", e.toString());
            }
        });
        reminderScheduler.start(executors.scheduler());
        workflowScheduler.start(executors.scheduler());
        executors.addIoErrorListener(() -> LOG.error("Background IO task died unexpectedly"));
    }

    public void shutdown() {
        LOG.info("Shutting down services...");
        agentService.flush();
        debounceScheduler.shutdownNow();
        agentService.shutdown();
        executors.shutdown();
        LOG.info("Shutdown complete");
    }

    public AgentService agentService() {
        return agentService;
    }

    // ---------------------------------------------------------------- accessors

    public Path dataDir() {
        return dataDir;
    }

    public AppConfig config() {
        return config;
    }

    public AppExecutors executors() {
        return executors;
    }

    public StorageIssueLog issueLog() {
        return issueLog;
    }

    public JsonStore store() {
        return store;
    }

    public ThemeManager themeManager() {
        return themeManager;
    }

    public MarkdownService markdownService() {
        return markdownService;
    }

    public TaskService tasks() {
        return taskService;
    }

    public NotesService notes() {
        return notesService;
    }

    public FinanceService finance() {
        return financeService;
    }

    public HabitService habits() {
        return habitService;
    }

    public HealthService health() {
        return healthService;
    }

    public LearningService learning() {
        return learningService;
    }

    public FileManagerService files() {
        return fileService;
    }

    public NotificationService notifications() {
        return notificationService;
    }

    public SearchService search() {
        return searchService;
    }

    public BackupService backup() {
        return backupService;
    }

    public SecurityPolicy security() {
        return securityPolicy;
    }

    public AuditLogger audit() {
        return auditLogger;
    }

    public WorkflowScheduler workflows() {
        return workflowScheduler;
    }

    public AiManager ai() {
        return aiManager;
    }

    public ReminderScheduler reminders() {
        return reminderScheduler;
    }

    /** Allowed roots helper shared by settings UI and file tools. */
    public List<String> allowedRoots() {
        return List.copyOf(config.allowedRoots);
    }
}
