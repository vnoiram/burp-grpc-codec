package com.github.burpgrpccodec;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
 * Sortable, periodically-refreshed table of gRPC service/method paths
 * observed by {@link GrpcMethodDiscoveryHandler}, registered as a Burp suite
 * tab for passive endpoint recon.
 */
final class GrpcMethodDiscoveryPanel {
    private final GrpcMethodDiscoveryLog log;
    private final EntriesTableModel tableModel = new EntriesTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField filterField = new JTextField(24);
    private final JLabel summaryLabel = new JLabel(" ");
    private final JPanel panel = new JPanel(new BorderLayout());

    GrpcMethodDiscoveryPanel(GrpcMethodDiscoveryLog log) {
        this.log = log;
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);

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
        JButton exportButton = new JButton("Export CSV...");
        exportButton.addActionListener(event -> exportCsv());
        JButton exportJsonButton = new JButton("Export JSON...");
        exportJsonButton.addActionListener(event -> exportJson());
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(event -> {
            log.clear();
            refresh();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(new JLabel("Filter:"));
        buttons.add(filterField);
        buttons.add(refreshButton);
        buttons.add(copyButton);
        buttons.add(exportButton);
        buttons.add(exportJsonButton);
        buttons.add(clearButton);

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(summaryLabel, BorderLayout.SOUTH);

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
        tableModel.setRows(entries);
        summaryLabel.setText(entries.size() + " of " + log.size() + " method(s) shown");
    }

    private void copyToClipboard() {
        StringBuilder text = new StringBuilder();
        for (GrpcMethodDiscoveryLog.Entry entry : visibleRows()) {
            text.append(entry.host()).append('\t').append(entry.path()).append('\t').append(entry.count()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }

    /** Rows in the table's current on-screen order, i.e. after the user's column sort (if any). */
    private List<GrpcMethodDiscoveryLog.Entry> visibleRows() {
        List<GrpcMethodDiscoveryLog.Entry> rows = tableModel.rows();
        List<GrpcMethodDiscoveryLog.Entry> visible = new java.util.ArrayList<>(rows.size());
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            visible.add(rows.get(table.convertRowIndexToModel(viewRow)));
        }
        return visible;
    }

    private void exportCsv() {
        exportTo("grpc-methods.csv", GrpcMethodDiscoveryLog.toCsv(filteredEntries()), "CSV");
    }

    private void exportJson() {
        exportTo("grpc-methods.json", GrpcMethodDiscoveryLog.toJson(filteredEntries()), "JSON");
    }

    private void exportTo(String defaultFileName, String content, String formatLabel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultFileName));
        if (chooser.showSaveDialog(panel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(panel, "Failed to write " + formatLabel + ": " + ex.getMessage(),
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class EntriesTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Host", "Path", "Count"};
        private List<GrpcMethodDiscoveryLog.Entry> rows = List.of();

        void setRows(List<GrpcMethodDiscoveryLog.Entry> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        List<GrpcMethodDiscoveryLog.Entry> rows() {
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
            return columnIndex == 2 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            GrpcMethodDiscoveryLog.Entry entry = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> entry.host();
                case 1 -> entry.path();
                case 2 -> entry.count();
                default -> "";
            };
        }
    }
}
