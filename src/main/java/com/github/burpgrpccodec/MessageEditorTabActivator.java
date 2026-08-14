package com.github.burpgrpccodec;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;

/**
 * Best-effort, unofficial workaround for the Montoya API not exposing a way to
 * programmatically select a specific message-viewer tab: walks up the Swing
 * parent chain from an extension-provided tab's component looking for the
 * innermost {@link JTabbedPane} ancestor and selects it there. This depends on
 * Burp's internal UI being built from JTabbedPane (true as of the API version
 * this was written against) and may silently stop working in a future Burp
 * release; failures are swallowed rather than surfaced, since this is a
 * cosmetic convenience, not core functionality.
 */
final class MessageEditorTabActivator {
    private MessageEditorTabActivator() {
    }

    static void selectTabContaining(Component component) {
        SwingUtilities.invokeLater(() -> {
            try {
                Component current = component;
                Container parent = current.getParent();
                while (parent != null) {
                    if (parent instanceof JTabbedPane tabbedPane) {
                        tabbedPane.setSelectedComponent(current);
                        return;
                    }
                    current = parent;
                    parent = parent.getParent();
                }
            } catch (RuntimeException ignored) {
                // Best-effort only: never let this break message decoding.
            }
        });
    }
}
