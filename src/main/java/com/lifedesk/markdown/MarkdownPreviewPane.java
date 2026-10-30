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

    /** True when this pane renders with WebKit (false means the native fallback is active). */
    public boolean isWebViewAvailable() {
        return webView != null;
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
        load(markdownText, palette, target -> null);
    }

    /** Render with [[wiki links]] resolved to note ids by the resolver. */
    public void render(String markdownText, ThemeManager.PreviewPalette palette,
                       java.util.function.UnaryOperator<String> linkResolver) {
        load(markdownText, palette, linkResolver);
    }

    private void load(String markdownText, ThemeManager.PreviewPalette palette,
                      java.util.function.UnaryOperator<String> linkResolver) {
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
        String page = markdown.wrapPage(body, palette);
        if (page.equals(lastPage)) {
            return;
        }
        lastPage = page;
        webView.getEngine().loadContent(page, "text/html");
    }
}
