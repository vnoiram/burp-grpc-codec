package com.github.burpgrpccodec;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * Read-only, periodically-refreshed listing of gRPC service/method paths
 * observed by {@link GrpcMethodDiscoveryHandler}, registered as a Burp suite
 * tab for passive endpoint recon.
 */
final class GrpcMethodDiscoveryPanel {
    private final GrpcMethodDiscoveryLog log;
    private final JTextArea textArea = new JTextArea();
    private final JPanel panel = new JPanel(new BorderLayout());

    GrpcMethodDiscoveryPanel(GrpcMethodDiscoveryLog log) {
        this.log = log;
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refresh());
        JButton copyButton = new JButton("Copy to clipboard");
        copyButton.addActionListener(event -> copyToClipboard());
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(event -> {
            log.clear();
            refresh();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(refreshButton);
        buttons.add(copyButton);
        buttons.add(clearButton);

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        new Timer(3000, event -> refresh()).start();
        refresh();
    }

    Component uiComponent() {
        return panel;
    }

    private void refresh() {
        List<GrpcMethodDiscoveryLog.Entry> entries = log.entries();
        StringBuilder text = new StringBuilder();
        text.append(entries.size()).append(" method(s) observed\n\n");
        for (GrpcMethodDiscoveryLog.Entry entry : entries) {
            text.append(entry.host()).append("  ").append(entry.path())
                    .append("  (x").append(entry.count()).append(")\n");
        }
        textArea.setText(text.toString());
    }

    private void copyToClipboard() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(textArea.getText()), null);
    }
}
