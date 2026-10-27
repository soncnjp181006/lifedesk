package com.lifedesk.theme;

import com.lifedesk.common.AppException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern TOKEN = Pattern.compile("(-ld-[a-z0-9-]+)\\s*:\\s*(#[0-9a-fA-F]{6})");

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

    /**
     * HTML body colors for the markdown preview, parsed from the active palette file
     * so the WebView can never drift from what JavaFX renders — including external
     * themes, which have no entry in a hardcoded switch.
     */
    public PreviewPalette previewPalette(String theme) {
        Map<String, String> tokens = parseTokens(readThemeCss(theme));
        return new PreviewPalette(
                token(tokens, "-ld-card", "#1a2436"),
                token(tokens, "-ld-text", "#e2e8f0"),
                token(tokens, "-ld-text-dim", "#9ba9bd"),
                token(tokens, "-ld-accent", "#60a5fa"),
                token(tokens, "-ld-border", "#3b4a63"),
                token(tokens, "-ld-surface", "#141e31"));
    }

    private String readThemeCss(String theme) {
        String url = themeCssUrl(theme);
        try {
            if (url.startsWith("file:")) {
                return Files.readString(Path.of(URI.create(url)), StandardCharsets.UTF_8);
            }
            try (InputStream in = ThemeManager.class.getResourceAsStream(url)) {
                return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }

    private static Map<String, String> parseTokens(String css) {
        Map<String, String> tokens = new HashMap<>();
        Matcher m = TOKEN.matcher(css);
        while (m.find()) {
            tokens.put(m.group(1), m.group(2));
        }
        return tokens;
    }

    private static String token(Map<String, String> tokens, String name, String fallback) {
        return tokens.getOrDefault(name, fallback);
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
