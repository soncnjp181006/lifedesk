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

    /** LaTeX typing assistant in the notes editor (shorthand, auto-fraction, auto-$). */
    public boolean latexAssist = true;
    /** Shorthand table, one {@code trigger:::replacement} per line (#cursor marks the caret). */
    public String latexShorthands = "";

    /** File manager + agent file tools: user-granted root folders. */
    public List<String> allowedRoots = new ArrayList<>();


    public AiConfig ai = new AiConfig();

    /** Dashboard widget enable flags, ordered by dashboardWidgetOrder. */
    public Map<String, Boolean> dashboardWidgets = new LinkedHashMap<>();
    public List<String> dashboardWidgetOrder = new ArrayList<>();

    /** Iconic-style icon overrides: ViewId name (or widget id) → /icons/<name>.svg. */
    public Map<String, String> iconOverrides = new LinkedHashMap<>();

    public int windowWidth = 1360;
    public int windowHeight = 860;
    public boolean windowMaximized = true;

}
