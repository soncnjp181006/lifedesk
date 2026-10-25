package com.lifedesk.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application configuration persisted at data/config/app-config.json inside the config directory. */
public class AppConfig {
    public int schemaVersion = 1;
    /** Absolute path of the data directory. Never inside the installation folder. */
    public String dataDir = "";
    public String theme = "Dark";
    public boolean sidebarCollapsed = false;

    public boolean remindersEnabled = true;
    public int reminderMinutesBefore = 15;

    /** File manager + agent file tools: user-granted root folders. */
    public List<String> allowedRoots = new ArrayList<>();

    /** Automation agent limits (anti-infinite-loop guards). */
    public AgentLimits agent = new AgentLimits();

    public AiConfig ai = new AiConfig();

    /** Dashboard widget enable flags, ordered by dashboardWidgetOrder. */
    public Map<String, Boolean> dashboardWidgets = new LinkedHashMap<>();
    public List<String> dashboardWidgetOrder = new ArrayList<>();

    public int windowWidth = 1360;
    public int windowHeight = 860;
    public boolean windowMaximized = true;

    public static class AgentLimits {
        public int maxSteps = 25;
        public int maxRunSeconds = 300;
        public int perStepSeconds = 60;
        public int maxRetries = 2;
    }
}
