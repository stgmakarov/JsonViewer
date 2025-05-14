package ru.greenatom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private Set<String> currentColumns = new LinkedHashSet<>();
    private final NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());

    // Рендерер только для samount
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
                } catch (NumberFormatException ignored) { }
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

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(openButton, BorderLayout.WEST);
        bottomPanel.add(totalLabel, BorderLayout.CENTER);

        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    private void openFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path folder = chooser.getSelectedFile().toPath();
            loadJsonFiles(folder);
            buildFilterPanel();
        }
    }

    private void loadJsonFiles(Path folder) {
        allData.clear();
        currentColumns.clear();
        ObjectMapper mapper = new ObjectMapper();

        try {
            List<Path> files = Files.list(folder)
                    .filter(path -> path.toString().endsWith(".json") || !path.toString().contains("."))
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
                    currentColumns.addAll(row.keySet());
                }
            }
            updateTable();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error reading files: " + e.getMessage());
        }
    }

    private void updateTable() {
        // Отбрасываем пустые колонки
        Set<String> nonEmpty = new TreeSet<>(currentColumns);
        nonEmpty.removeIf(col -> allData.stream().allMatch(r -> r.getOrDefault(col, "").isEmpty()));
        currentColumns = nonEmpty;

        // Обновляем модель
        tableModel.setRowCount(0);
        tableModel.setColumnIdentifiers(nonEmpty.toArray());
        double total = 0;
        for (Map<String, String> row : allData) {
            Object[] rowData = nonEmpty.stream().map(c -> row.getOrDefault(c, "")).toArray();
            tableModel.addRow(rowData);
            String sa = row.get("samount");
            if (sa != null && !sa.isEmpty()) {
                try { total += Double.parseDouble(sa); } catch (Exception ignored) {}
            }
        }
        totalLabel.setText("Total: " + nf.format(total));

        // Устанавливаем рендерер только для столбца samount
        int idx = tableModel.findColumn("samount");
        if (idx != -1) {
            TableColumn col = table.getColumnModel().getColumn(idx);
            col.setCellRenderer(samountRenderer);
        }

        adjustColumnWidths();
    }

    private void buildFilterPanel() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(mainPanel);
        scroll.setPreferredSize(new Dimension(250, frame.getHeight()));

        filterPanels.clear();
        filterFields.clear();
        List<String> cols = new ArrayList<>(currentColumns);
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
        tableModel.setRowCount(0);
        double total = 0;
        for (Map<String, String> row : allData) {
            boolean keep = true;
            for (Map.Entry<String, List<JTextField>> e : filterFields.entrySet()) {
                String col = e.getKey();
                List<String> vals = new ArrayList<>();
                for (JTextField f : e.getValue()) {
                    String t = f.getText().trim();
                    if (!t.isEmpty()) vals.add(t);
                }
                if (!vals.isEmpty() && !vals.contains(row.getOrDefault(col, ""))) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                Object[] rd = currentColumns.stream().map(c -> row.getOrDefault(c, "")).toArray();
                tableModel.addRow(rd);
                String sa = row.get("samount");
                if (sa != null && !sa.isEmpty()) {
                    try { total += Double.parseDouble(sa); } catch(Exception ignored){}
                }
            }
        }
        totalLabel.setText("Total: " + nf.format(total));

        // повторно применим рендерер на случай изменения столбцов
        int idx = tableModel.findColumn("samount");
        if (idx != -1) {
            TableColumn col = table.getColumnModel().getColumn(idx);
            col.setCellRenderer(samountRenderer);
        }
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

// Вспомогательный слушатель
class SimpleDocumentListener implements javax.swing.event.DocumentListener {
    private final Runnable onChange;
    public SimpleDocumentListener(Runnable onChange) { this.onChange = onChange; }
    public void insertUpdate(javax.swing.event.DocumentEvent e){ onChange.run(); }
    public void removeUpdate(javax.swing.event.DocumentEvent e){ onChange.run(); }
    public void changedUpdate(javax.swing.event.DocumentEvent e){ onChange.run(); }
}
