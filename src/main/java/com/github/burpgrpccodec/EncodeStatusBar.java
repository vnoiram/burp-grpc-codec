package com.github.burpgrpccodec;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

/**
 * Wraps a Burp-provided raw editor component with a status line below it,
 * so an encode failure is visible where the user is looking instead of only
 * in the extension's log output. Burp calls getRequest()/getResponse() when
 * sending a message or navigating away from it; on an encode error the
 * editors fall back to the previously decoded body, and without this the
 * user would have no visual sign that their edit was silently dropped.
 */
final class EncodeStatusBar {
    private static final Color ERROR_COLOR = new Color(0xC0, 0x30, 0x30);
    private final JLabel label = new JLabel(" ");
    private final JPanel panel = new JPanel(new BorderLayout());

    EncodeStatusBar(Component editorComponent) {
        label.setForeground(ERROR_COLOR);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        panel.add(editorComponent, BorderLayout.CENTER);
        panel.add(label, BorderLayout.SOUTH);
    }

    Component uiComponent() {
        return panel;
    }

    void clear() {
        label.setText(" ");
    }

    void showError(String message) {
        label.setText("⚠ Encode failed, sent the previously decoded body instead: " + message);
    }
}
