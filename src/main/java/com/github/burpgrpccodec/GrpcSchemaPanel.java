package com.github.burpgrpccodec;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
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
 * Sortable, periodically-refreshed browser of the message types and service
 * methods currently loaded into the {@link SchemaRegistry} (from .proto
 * files, a FileDescriptorSet, or Server Reflection), registered as a Burp
 * suite tab so the effective schema is visible without leaving Burp.
 */
final class GrpcSchemaPanel {
    private final SchemaRegistry schemas;
    private final MessagesTableModel messagesModel = new MessagesTableModel();
    private final MethodsTableModel methodsModel = new MethodsTableModel();
    private final JTable messagesTable = new JTable(messagesModel);
    private final JTable methodsTable = new JTable(methodsModel);
    private final JTextField filterField = new JTextField(24);
    private final JLabel summaryLabel = new JLabel(" ");
    private final JPanel panel = new JPanel(new BorderLayout());

    GrpcSchemaPanel(SchemaRegistry schemas) {
        this.schemas = schemas;
        messagesTable.setAutoCreateRowSorter(true);
        messagesTable.setFillsViewportHeight(true);
        methodsTable.setAutoCreateRowSorter(true);
        methodsTable.setFillsViewportHeight(true);

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

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("Messages", messagesTable), titled("Methods", methodsTable));
        split.setResizeWeight(0.5);

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(summaryLabel, BorderLayout.SOUTH);

        new Timer(3000, event -> refresh()).start();
        refresh();
    }

    Component uiComponent() {
        return panel;
    }

    private static JPanel titled(String title, JTable table) {
        JPanel section = new JPanel(new BorderLayout());
        section.add(new JLabel(title), BorderLayout.NORTH);
        section.add(new JScrollPane(table), BorderLayout.CENTER);
        return section;
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
        messagesModel.setRows(messages);
        methodsModel.setRows(methods);
        summaryLabel.setText(messages.size() + " of " + schemas.messageCount() + " message type(s), "
                + methods.size() + " of " + schemas.methodCount() + " method(s) shown");
    }

    private void copyToClipboard() {
        StringBuilder text = new StringBuilder("Messages:\n");
        for (SchemaMessage message : visibleRows(messagesTable, messagesModel.rows())) {
            text.append("  ").append(message.typeName())
                    .append("  (").append(message.fieldsByNumber().size()).append(" field(s))\n");
        }
        text.append("\nMethods:\n");
        for (SchemaMethod method : visibleRows(methodsTable, methodsModel.rows())) {
            text.append("  ").append(method.path()).append("  ")
                    .append(method.requestType()).append(" -> ").append(method.responseType()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }

    /** Rows in the given table's current on-screen order, i.e. after the user's column sort (if any). */
    private static <T> List<T> visibleRows(JTable table, List<T> rows) {
        List<T> visible = new java.util.ArrayList<>(rows.size());
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            visible.add(rows.get(table.convertRowIndexToModel(viewRow)));
        }
        return visible;
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

    private static final class MessagesTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Type", "Fields"};
        private List<SchemaMessage> rows = List.of();

        void setRows(List<SchemaMessage> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        List<SchemaMessage> rows() {
            return rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 1 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SchemaMessage message = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> message.typeName();
                case 1 -> message.fieldsByNumber().size();
                default -> "";
            };
        }
    }

    private static final class MethodsTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Path", "Request Type", "Response Type"};
        private List<SchemaMethod> rows = List.of();

        void setRows(List<SchemaMethod> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        List<SchemaMethod> rows() {
            return rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SchemaMethod method = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> method.path();
                case 1 -> method.requestType();
                case 2 -> method.responseType();
                default -> "";
            };
        }
    }
}
