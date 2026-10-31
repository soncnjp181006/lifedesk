package com.lifedesk.markdown;

import com.lifedesk.theme.ThemeManager;
import com.lifedesk.ui.components.MarkdownFlow;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import org.w3c.dom.Document;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

import java.util.function.Consumer;

/**
 * Markdown preview pane.
 *
 * <p>Primary renderer is a sandboxed JavaFX WebView: content is fully sanitized
 * upstream (MarkdownService escapes all raw HTML), and this pane never executes
 * scripts — a DOM EventListener catches link clicks in Java, so nothing inside
 * the page can navigate by itself. Internal links use the lifedesk://note/<id>
 * scheme; external links are handed to the callback (which asks the user before
 * opening a browser).
 *
 * <p>JavaFX ships WebKit as a separate native library (jfxwebkit). When that
 * library is missing or cannot load — a JDK without the web module, a stripped
 * runtime image, a locked/AV-blocked DLL — the pane degrades to the native
 * {@link MarkdownFlow} renderer instead of killing the view with an
 * UnsatisfiedLinkError. The fallback has no link interception, so wiki and
 * external links are inert; everything else renders.
 */
public class MarkdownPreviewPane extends VBox {
    private final MarkdownService markdown = new MarkdownService();
    /** WebView renderer, or null when this JVM has no WebKit. */
    private final WebView webView;
    /** Native fallback renderer, or null when WebKit is available. */
    private final MarkdownFlow fallback;
    /**
     * Last page handed to the engine. A WebView reload is one of the most
     * expensive things this app does (whole document + layout), so identical
     * renders — every autosave tick, every refresh of a filtered list — are skipped.
     */
    private String lastPage = "";
    private String lastFallback = "";
    private Consumer<String> noteLinkHandler = id -> {
    };
    private Consumer<String> externalLinkHandler = url -> {
    };

    public MarkdownPreviewPane() {
        Warn w = new Warn();
        WebView view = tryCreateWebView(w);
        this.webView = view;
        if (view != null) {
            this.fallback = null;
            getChildren().add(view);
            // The WebView's own pref is 800x600 — left unbound it either sat in a
            // corner of a bigger pane or overflowed a narrower one (text clipped
            // mid-word). Bind it to fill this pane exactly.
            view.prefWidthProperty().bind(widthProperty());
            view.prefHeightProperty().bind(heightProperty());
            VBox.setVgrow(view, javafx.scene.layout.Priority.ALWAYS);
            view.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                    Document doc = view.getEngine().getDocument();
                    if (doc != null) {
                        hookLinks(doc, "a");
                    }
                }
            });
        } else {
            warnOnce(w);
            MarkdownFlow flow = new MarkdownFlow();
            flow.setLinkHandler(href -> {
                if (href != null && href.startsWith("lifedesk://note/")) {
                    noteLinkHandler.accept(href.substring("lifedesk://note/".length()));
                } else if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
                    externalLinkHandler.accept(href);
                }
            });
            this.fallback = flow;
            ScrollPane scroll = new ScrollPane(flow);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("md-preview-scroll");
            VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
            getChildren().add(scroll);
        }
    }

    /** Carries the failure out of the construction attempt so it can be logged once. */
    private static final class Warn {
        Throwable error;
    }

    private static WebView tryCreateWebView(Warn warn) {
        try {
            return new WebView();
        } catch (Throwable t) {
            // LinkageError (no jfxwebkit), ExceptionInInitializerError, headless toolkits…
            warn.error = t;
            return null;
        }
    }

    private static void warnOnce(Warn w) {
        org.slf4j.LoggerFactory.getLogger(MarkdownPreviewPane.class).warn(
                "JavaFX WebView không khả dụng — dùng bộ dựng markdown gốc: {}",
                w.error == null ? "unknown" : w.error.toString());
    }

    public void setHandlers(Consumer<String> noteLinkHandler, Consumer<String> externalLinkHandler) {
        this.noteLinkHandler = noteLinkHandler;
        this.externalLinkHandler = externalLinkHandler;
    }

    private void hookLinks(Document doc, String tagName) {
        var nodes = doc.getElementsByTagName(tagName);
        EventListener listener = (Event ev) -> {
            EventTarget target = ev.getCurrentTarget();
            if (target instanceof org.w3c.dom.html.HTMLAnchorElement anchor) {
                String href = anchor.getHref();
                ev.preventDefault();
                ev.stopPropagation();
                if (href != null && href.startsWith("lifedesk://note/")) {
                    noteLinkHandler.accept(href.substring("lifedesk://note/".length()));
                } else if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
                    externalLinkHandler.accept(href);
                }
            }
        };
        for (int i = 0; i < nodes.getLength(); i++) {
            ((EventTarget) nodes.item(i)).addEventListener("click", listener, false);
        }
    }

    /** Render markdown into the preview with the given theme palette. */
    public void render(String markdownText, ThemeManager.PreviewPalette palette) {
        load(markdownText, palette, target -> null, false);
    }

    /** Render with [[wiki links]] resolved to note ids by the resolver. */
    public void render(String markdownText, ThemeManager.PreviewPalette palette,
                       java.util.function.UnaryOperator<String> linkResolver) {
        load(markdownText, palette, linkResolver, false);
    }

    /**
     * Presentation mode: large, centered content (flashcard board) instead of
     * a document flowing from the top-left of a big empty card.
     */
    public void renderPresentation(String markdownText, ThemeManager.PreviewPalette palette) {
        load(markdownText, palette, target -> null, true);
    }

    /**
     * The rendered page's own numbers: {@code [scrollHeight, clientHeight,
     * window.innerHeight]} in CSS pixels, or null when there is no WebKit engine or no
     * page yet. The third value is what makes the first two trustworthy: WebKit keeps
     * laying out the document on its own clock, so right after a window resize it can
     * still report the viewport it had before. Comparing the engine's inner height with
     * the node's height says whether the page has caught up — and a check that ignores
     * that reports a scroll that no user ever saw.
     */
    public double[] pageMetrics() {
        if (webView == null) {
            return null;
        }
        Object r = webView.getEngine().executeScript(
                "[document.documentElement.scrollHeight, document.documentElement.clientHeight,"
                        + " window.innerHeight].join(',')");
        if (!(r instanceof String s)) {
            return null;
        }
        String[] parts = s.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The WebView node itself, or null when this JVM fell back to the native renderer. */
    public javafx.scene.web.WebView webViewNode() {
        return webView;
    }

    private void load(String markdownText, ThemeManager.PreviewPalette palette,
                      java.util.function.UnaryOperator<String> linkResolver) {
        load(markdownText, palette, linkResolver, false);
    }

    private void load(String markdownText, ThemeManager.PreviewPalette palette,
                      java.util.function.UnaryOperator<String> linkResolver, boolean presentation) {
        if (webView == null) {
            String text = markdownText == null ? "" : markdownText;
            if (text.equals(lastFallback)) {
                return;
            }
            lastFallback = text;
            fallback.render(text, false);
            return;
        }
        String body = markdown.toHtml(markdownText, linkResolver);
        String page = presentation
                ? markdown.wrapPresentationPage(body, palette)
                : markdown.wrapPage(body, palette);
        if (page.equals(lastPage)) {
            return;
        }
        lastPage = page;
        webView.getEngine().loadContent(page, "text/html");
    }
}
