package com.github.burpgrpccodec;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Read-only, periodically-refreshed listing of gRPC service/method paths
 * observed by {@link GrpcMethodDiscoveryHandler}, registered as a Burp suite
 * tab for passive endpoint recon.
 */
final class GrpcMethodDiscoveryPanel {
    private final GrpcMethodDiscoveryLog log;
    private final JTextArea textArea = new JTextArea();
    private final JTextField filterField = new JTextField(24);
    private final JPanel panel = new JPanel(new BorderLayout());

    GrpcMethodDiscoveryPanel(GrpcMethodDiscoveryLog log) {
        this.log = log;
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                refresh();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                refresh();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                refresh();
            }
        });

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refresh());
        JButton copyButton = new JButton("Copy to clipboard");
        copyButton.addActionListener(event -> copyToClipboard());
        JButton exportButton = new JButton("Export CSV...");
        exportButton.addActionListener(event -> exportCsv());
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(event -> {
            log.clear();
            refresh();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(new javax.swing.JLabel("Filter:"));
        buttons.add(filterField);
        buttons.add(refreshButton);
        buttons.add(copyButton);
        buttons.add(exportButton);
        buttons.add(clearButton);

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        new Timer(3000, event -> refresh()).start();
        refresh();
    }

    Component uiComponent() {
        return panel;
    }

    private List<GrpcMethodDiscoveryLog.Entry> filteredEntries() {
        String filter = filterField.getText().trim().toLowerCase(Locale.ROOT);
        List<GrpcMethodDiscoveryLog.Entry> entries = log.entries();
        if (filter.isEmpty()) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> entry.host().toLowerCase(Locale.ROOT).contains(filter)
                        || entry.path().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
    }

    private void refresh() {
        List<GrpcMethodDiscoveryLog.Entry> entries = filteredEntries();
        StringBuilder text = new StringBuilder();
        text.append(entries.size()).append(" of ").append(log.size()).append(" method(s) shown\n\n");
        for (GrpcMethodDiscoveryLog.Entry entry : entries) {
            text.append(entry.host()).append("  ").append(entry.path())
                    .append("  (x").append(entry.count()).append(")\n");
        }
        textArea.setText(text.toString());
    }

    private void copyToClipboard() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(textArea.getText()), null);
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("grpc-methods.csv"));
        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        String csv = GrpcMethodDiscoveryLog.toCsv(filteredEntries());
        try {
            Files.writeString(target, csv, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(panel, "Failed to write CSV: " + ex.getMessage(),
                    "Export failed", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }
}
