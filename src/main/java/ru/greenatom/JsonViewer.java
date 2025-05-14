package ru.greenatom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;

public class JsonViewer {
    private final JFrame frame;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final Map<String, JPanel> filterPanels = new LinkedHashMap<>();
    private final Map<String, List<JTextField>> filterFields = new HashMap<>();
    private final JLabel totalLabel;
    private final List<Map<String, String>> allData = new ArrayList<>();
    private Set<String> initialColumns = new LinkedHashSet<>();
    private final NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
    private Path sourceFolder;

    private final DefaultTableCellRenderer samountRenderer = new DefaultTableCellRenderer() {
        @Override
        public void setValue(Object value) {
            String s = Objects.toString(value, "");
            if (s.matches("-?\\d+(\\.\\d+)?")) {
                try {
                    double d = Double.parseDouble(s);
                    setHorizontalAlignment(SwingConstants.RIGHT);
                    super.setValue(nf.format(d));
                    return;
                } catch (NumberFormatException ignored) {}
            }
            setHorizontalAlignment(SwingConstants.LEFT);
            super.setValue(s);
        }
    };

    public JsonViewer() {
        frame = new JFrame("JSON Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLayout(new BorderLayout());

        tableModel = new DefaultTableModel();
        table = new JTable(tableModel);

        totalLabel = new JLabel("Total: 0");
        totalLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JButton openButton = new JButton("Open Folder");
        openButton.addActionListener(e -> openFolder());

        JButton saveButton = new JButton("Save Filtered");
        saveButton.addActionListener(e -> saveFiltered());

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(openButton, BorderLayout.WEST);
        bottomPanel.add(totalLabel, BorderLayout.CENTER);
        bottomPanel.add(saveButton, BorderLayout.EAST);

        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    private void openFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            sourceFolder = chooser.getSelectedFile().toPath();
            loadJsonFiles(sourceFolder);
            buildFilterPanel();
        }
    }

    private void loadJsonFiles(Path folder) {
        allData.clear();
        initialColumns.clear();
        ObjectMapper mapper = new ObjectMapper();

        try {
            List<Path> files = Files.list(folder)
                    .filter(path -> path.toString().endsWith(".json"))
                    .toList();
            for (Path file : files) {
                JsonNode root = mapper.readTree(file.toFile());
                if (!root.has("structure") || !root.has("data")) continue;
                String structure = root.get("structure").asText();
                if (structure.startsWith("ga01") || structure.startsWith("ga20")) continue;
                String gaentity = root.has("gaentity") ? root.get("gaentity").asText() : "";
                String gaperiod = root.has("gaperiod") ? root.get("gaperiod").asText() : "";
                for (JsonNode item : root.get("data")) {
                    Map<String, String> row = new HashMap<>();
                    row.put("structure", structure);
                    row.put("gaentity", gaentity);
                    row.put("gaperiod", gaperiod);
                    item.fields().forEachRemaining(f -> row.put(f.getKey(), f.getValue().asText()));
                    allData.add(row);
                    initialColumns.addAll(row.keySet());
                }
            }
            updateTable(allData);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error reading files: " + e.getMessage());
        }
    }

    private void buildFilterPanel() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(mainPanel);
        scroll.setPreferredSize(new Dimension(250, frame.getHeight()));
        filterPanels.clear();
        filterFields.clear();
        List<String> cols = new ArrayList<>(initialColumns);
        Collections.sort(cols);
        for (String col : cols) {
            JPanel colPanel = new JPanel();
            colPanel.setLayout(new BoxLayout(colPanel, BoxLayout.Y_AXIS));
            colPanel.setBorder(BorderFactory.createTitledBorder(col));
            filterPanels.put(col, colPanel);
            filterFields.put(col, new ArrayList<>());
            addFilterField(col);
            mainPanel.add(colPanel);
        }
        JButton apply = new JButton("Apply Filters");
        apply.addActionListener(e -> applyFilter());
        mainPanel.add(apply);
        frame.add(scroll, BorderLayout.WEST);
        frame.revalidate();
    }

    private void addFilterField(String column) {
        JPanel colPanel = filterPanels.get(column);
        List<JTextField> fields = filterFields.get(column);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField field = new JTextField(10);
        field.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (fields.get(fields.size() - 1) == field && !field.getText().trim().isEmpty()) {
                addFilterField(column);
            }
        }));
        fields.add(field);
        row.add(field);
        colPanel.add(row);
        frame.revalidate();
    }

    private void applyFilter() {
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> row : allData) {
            if (rowMatchesFilter(row)) filtered.add(row);
        }
        updateTable(filtered);
    }

    private void updateTable(List<Map<String, String>> data) {
        boolean filterActive = filterFields.values().stream()
                .flatMap(List::stream)
                .anyMatch(f -> !f.getText().trim().isEmpty());
        Set<String> cols = new TreeSet<>(initialColumns);
        if (filterActive) {
            cols.removeIf(col -> data.stream().allMatch(r -> r.getOrDefault(col, "").isEmpty()));
        }
        tableModel.setRowCount(0);
        tableModel.setColumnIdentifiers(cols.toArray());
        double total = 0;
        for (Map<String, String> row : data) {
            Object[] rd = cols.stream().map(c -> row.getOrDefault(c, "")).toArray();
            tableModel.addRow(rd);
            String sa = row.get("samount");
            if (sa != null && !sa.isEmpty()) {
                try { total += Double.parseDouble(sa); } catch (Exception ignored) {}
            }
        }
        totalLabel.setText("Total: " + nf.format(total));
        int idx = tableModel.findColumn("samount");
        if (idx != -1) {
            TableColumn col = table.getColumnModel().getColumn(idx);
            col.setCellRenderer(samountRenderer);
        }
        adjustColumnWidths();
    }

    private boolean rowMatchesFilter(Map<String, String> row) {
        for (var e : filterFields.entrySet()) {
            String col = e.getKey();
            List<String> vals = new ArrayList<>();
            for (JTextField f : e.getValue()) {
                String t = f.getText().trim(); if (!t.isEmpty()) vals.add(t);
            }
            if (!vals.isEmpty() && !vals.contains(row.getOrDefault(col, ""))) return false;
        }
        return true;
    }

    private void saveFiltered() {
        if (sourceFolder == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path targetFolder = chooser.getSelectedFile().toPath();
        ObjectMapper mapper = new ObjectMapper();

        List<String> structFilter = new ArrayList<>();
        if (filterFields.containsKey("structure")) {
            for (JTextField f : filterFields.get("structure")) {
                String t = f.getText().trim(); if (!t.isEmpty()) structFilter.add(t);
            }
        }

        try {
            Files.createDirectories(targetFolder);
            List<Path> files = Files.list(sourceFolder)
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList();
            for (Path file : files) {
                JsonNode root = mapper.readTree(file.toFile());
                // Гарантируем, что корнево поле data будет массивом после сохранения
                ArrayNode filtered = mapper.createArrayNode();
                String fileStruct = root.has("structure") ? root.get("structure").asText() : "";
                boolean includeData = structFilter.isEmpty() || structFilter.contains(fileStruct);
                if (includeData && root.has("data") && root.get("data").isArray()) {
                    ArrayNode data = (ArrayNode) root.get("data");
                    for (JsonNode item : data) {
                        if (nodeMatchesFilter(item)) filtered.add(item);
                    }
                }
                // Ставим даже если не было data
                ((ObjectNode) root).set("data", filtered);
                Path out = targetFolder.resolve(file.getFileName());
                mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
            }
            JOptionPane.showMessageDialog(frame, "Saved to: " + targetFolder);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Error saving files: " + ex.getMessage());
        }
    }

    private boolean nodeMatchesFilter(JsonNode item) {
        for (var e : filterFields.entrySet()) {
            String col = e.getKey();
            List<String> allowed = new ArrayList<>();
            for (JTextField f : e.getValue()) {
                String t = f.getText().trim(); if (!t.isEmpty()) allowed.add(t);
            }
            if (!allowed.isEmpty()) {
                String value = item.has(col) ? item.get(col).asText() : "";
                if (!allowed.contains(value)) return false;
            }
        }
        return true;
    }

    private void adjustColumnWidths() {
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            int w = 75;
            for (int r = 0; r < table.getRowCount(); r++) {
                Object v = table.getValueAt(r, i);
                if (v != null) w = Math.max(w, v.toString().length() * 7);
            }
            col.setPreferredWidth(w);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(JsonViewer::new);
    }
}

class SimpleDocumentListener implements javax.swing.event.DocumentListener {
    private final Runnable onChange;
    public SimpleDocumentListener(Runnable onChange) { this.onChange = onChange; }
    public void insertUpdate(javax.swing.event.DocumentEvent e){ onChange.run(); }
    public void removeUpdate(javax.swing.event.DocumentEvent e){ onChange.run(); }
    public void changedUpdate(javax.swing.event.DocumentEvent e){ onChange.run(); }
}
