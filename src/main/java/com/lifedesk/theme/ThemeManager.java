package com.lifedesk.theme;

import com.lifedesk.common.AppException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Theme registry. Built-in themes ship as classpath stylesheets; additional
 * themes can be added by dropping a .css file into {@code <data>/config/themes/}.
 * Only stylesheets — no code changes needed.
 */
public class ThemeManager {
    public static final String BASE_CSS = "/css/base.css";
    /** Palette values exposed to WebView (markdown preview) so HTML matches the active theme. */
    public static final String[] BUILT_IN = {"Light", "Dark", "Mate", "Monokai", "Deep Blue"};

    private final Path externalThemesDir;

    public ThemeManager(Path dataDir) {
        this.externalThemesDir = dataDir.resolve("config").resolve("themes");
        try {
            Files.createDirectories(externalThemesDir);
        } catch (IOException e) {
            throw new AppException("Không thể tạo thư mục theme: " + externalThemesDir, e);
        }
    }

    public List<String> availableThemes() {
        List<String> themes = new ArrayList<>(List.of(BUILT_IN));
        try (Stream<Path> files = Files.list(externalThemesDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".css"))
                    .map(p -> stripExt(p.getFileName().toString()))
                    .sorted()
                    .forEach(themes::add);
        } catch (IOException e) {
            // external themes are optional
        }
        return themes;
    }

    /** Stylesheet URLs for the given theme name (base always first). */
    public List<String> stylesheetsFor(String theme) {
        List<String> css = new ArrayList<>();
        css.add(BASE_CSS);
        css.add(themeCssUrl(theme));
        return css;
    }

    private String themeCssUrl(String theme) {
        if (List.of(BUILT_IN).contains(theme)) {
            String file = switch (theme) {
                case "Light" -> "/css/light.css";
                case "Dark" -> "/css/dark.css";
                case "Mate" -> "/css/mate.css";
                case "Monokai" -> "/css/monokai.css";
                case "Deep Blue" -> "/css/deepblue.css";
                default -> "/css/dark.css";
            };
            return file;
        }
        Path external = externalThemesDir.resolve(safeFileName(theme) + ".css");
        if (Files.exists(external)) {
            return external.toUri().toString();
        }
        return "/css/dark.css";
    }

    /** HTML body colors for the markdown preview, extracted from the theme by name. */
    public PreviewPalette previewPalette(String theme) {
        return switch (theme) {
            case "Light" -> new PreviewPalette("#ffffff", "#1c2430", "#67707d", "#2563eb", "#d5d9e0", "#f3f4f6");
            case "Mate" -> new PreviewPalette("#4f5357", "#e8e6e3", "#a9b2b8", "#6d9c3f", "#6b7175", "#45494c");
            case "Monokai" -> new PreviewPalette("#33342c", "#f8f8f2", "#a6a693", "#a6e22e", "#4a4b41", "#2d2e27");
            case "Deep Blue" -> new PreviewPalette("#172c47", "#e4edf8", "#8fa8c6", "#3b82f6", "#2a4a75", "#12233a");
            default -> new PreviewPalette("#232830", "#e8eaed", "#9aa3af", "#4f8ef7", "#3a414d", "#1b1f26");
        };
    }

    private String safeFileName(String theme) {
        return theme.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public record PreviewPalette(String background, String text, String textDim,
                                 String accent, String border, String codeBg) {
    }
}
