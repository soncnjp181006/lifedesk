package com.lifedesk.markdown;

import com.lifedesk.theme.ThemeManager;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import org.w3c.dom.Document;
import org.w3c.dom.events.Event;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.events.EventTarget;

import java.util.function.Consumer;

/**
 * Markdown preview inside a sandboxed JavaFX WebView.
 *
 * Content is fully sanitized upstream (MarkdownService escapes all raw HTML),
 * and this pane never executes scripts: an EventListener on the DOM catches
 * link clicks in Java, so nothing inside the page can navigate by itself.
 * Internal links use the lifedesk://note/<id> scheme; external links are
 * handed to the callback (which asks the user before opening a browser).
 */
public class MarkdownPreviewPane extends VBox {
    private final MarkdownService markdown = new MarkdownService();
    private final WebView webView = new WebView();
    private Consumer<String> noteLinkHandler = id -> {
    };
    private Consumer<String> externalLinkHandler = url -> {
    };

    public MarkdownPreviewPane() {
        getChildren().add(webView);
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                Document doc = webView.getEngine().getDocument();
                if (doc != null) {
                    hookLinks(doc, "a");
                }
            }
        });
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
        String body = markdown.toHtml(markdownText, target -> null);
        webView.getEngine().loadContent(markdown.wrapPage(body, palette), "text/html");
    }

    /** Render with [[wiki links]] resolved to note ids by the resolver. */
    public void render(String markdownText, ThemeManager.PreviewPalette palette,
                       java.util.function.UnaryOperator<String> linkResolver) {
        String body = markdown.toHtml(markdownText, linkResolver);
        webView.getEngine().loadContent(markdown.wrapPage(body, palette), "text/html");
    }
}
