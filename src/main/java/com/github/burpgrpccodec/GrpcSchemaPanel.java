package com.github.burpgrpccodec;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Read-only, periodically-refreshed browser of the message types and service
 * methods currently loaded into the {@link SchemaRegistry} (from .proto
 * files, a FileDescriptorSet, or Server Reflection), registered as a Burp
 * suite tab so the effective schema is visible without leaving Burp.
 */
final class GrpcSchemaPanel {
    private final SchemaRegistry schemas;
    private final JTextArea textArea = new JTextArea();
    private final JTextField filterField = new JTextField(24);
    private final JPanel panel = new JPanel(new BorderLayout());

    GrpcSchemaPanel(SchemaRegistry schemas) {
        this.schemas = schemas;
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refresh();
            }
        });

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refresh());
        JButton copyButton = new JButton("Copy to clipboard");
        copyButton.addActionListener(event -> copyToClipboard());
        JButton exportButton = new JButton("Export .proto...");
        exportButton.addActionListener(event -> exportProto());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(new JLabel("Filter:"));
        buttons.add(filterField);
        buttons.add(refreshButton);
        buttons.add(copyButton);
        buttons.add(exportButton);

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        new Timer(3000, event -> refresh()).start();
        refresh();
    }

    Component uiComponent() {
        return panel;
    }

    private void refresh() {
        String filter = filterField.getText().trim().toLowerCase(Locale.ROOT);
        List<SchemaMessage> messages = schemas.allMessages().stream()
                .filter(message -> filter.isEmpty() || message.typeName().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        List<SchemaMethod> methods = schemas.allMethods().stream()
                .filter(method -> filter.isEmpty()
                        || method.path().toLowerCase(Locale.ROOT).contains(filter)
                        || method.requestType().toLowerCase(Locale.ROOT).contains(filter)
                        || method.responseType().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
        StringBuilder text = new StringBuilder();
        text.append(messages.size()).append(" of ").append(schemas.messageCount()).append(" message type(s), ")
                .append(methods.size()).append(" of ").append(schemas.methodCount()).append(" method(s) shown\n\n");
        text.append("Messages:\n");
        for (SchemaMessage message : messages) {
            text.append("  ").append(message.typeName())
                    .append("  (").append(message.fieldsByNumber().size()).append(" field(s))\n");
        }
        text.append("\nMethods:\n");
        for (SchemaMethod method : methods) {
            text.append("  ").append(method.path()).append("  ")
                    .append(method.requestType()).append(" -> ").append(method.responseType()).append('\n');
        }
        textArea.setText(text.toString());
    }

    private void copyToClipboard() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(textArea.getText()), null);
    }

    private void exportProto() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("schema.proto"));
        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        // Exports the whole schema regardless of the on-screen filter, since a
        // .proto file with only some referenced types would not be coherent.
        String proto = SchemaProtoExporter.export(schemas.allMessages(), schemas.allMethods());
        try {
            Files.writeString(target, proto, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(panel, "Failed to write .proto: " + ex.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }
}
