package ru.greenatom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class JsonViewer {
    private final JFrame frame;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final Map<String, JTextField> filterFields = new HashMap<>();
    private final JLabel totalLabel;
    private final List<Map<String, String>> allData = new ArrayList<>();
    private final Set<String> columns = new LinkedHashSet<>();
    private final JPanel filterPanel;

    public JsonViewer() {
        frame = new JFrame("JSON Viewer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLayout(new BorderLayout());

        tableModel = new DefaultTableModel();
        table = new JTable(tableModel);

        filterPanel = new JPanel();
        filterPanel.setLayout(new BoxLayout(filterPanel, BoxLayout.Y_AXIS));
        JScrollPane filterScroll = new JScrollPane(filterPanel);
        filterScroll.setPreferredSize(new Dimension(250, frame.getHeight()));

        JButton filterButton = new JButton("Filter");
        filterButton.addActionListener(e -> applyFilter());
        filterPanel.add(filterButton);

        totalLabel = new JLabel("Total: 0");

        JButton openButton = new JButton("Open Folder");
        openButton.addActionListener(e -> openFolder());

        JPanel bottomPanel = new JPanel();
        bottomPanel.add(openButton);
        bottomPanel.add(totalLabel);

        frame.add(filterScroll, BorderLayout.WEST);
        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    private void openFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            loadJsonFiles(folder.toPath());
        }
    }

    private void loadJsonFiles(Path folder) {
        allData.clear();
        columns.clear();

        ObjectMapper mapper = new ObjectMapper();
        try {
            List<Path> files = Files.list(folder)
                    .filter(path -> path.toString().endsWith(".json") || !path.toString().contains("."))
                    .collect(Collectors.toList());

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

                    item.fields().forEachRemaining(field -> row.put(field.getKey(), field.getValue().asText()));
                    allData.add(row);
                    columns.addAll(row.keySet());
                }
            }
            applyFilter(); // Автоматически обновляем таблицу после загрузки данных
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error reading files: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyFilter() {
        tableModel.setRowCount(0);
        List<Map<String, String>> filteredData = new ArrayList<>();
        double total = 0;

        for (Map<String, String> row : allData) {
            boolean matches = filterFields.entrySet().stream()
                    .allMatch(entry -> entry.getValue().getText().trim().isEmpty() || entry.getValue().getText().trim().equals(row.getOrDefault(entry.getKey(), "")));

            if (matches) {
                filteredData.add(row);
                if (row.containsKey("samount")) {
                    try {
                        total += Double.parseDouble(row.get("samount"));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        totalLabel.setText("Total: " + total);
        updateTable(filteredData);
    }

    private void updateTable(List<Map<String, String>> visibleData) {
        tableModel.setRowCount(0);

        // Определяем непустые колонки на основе отфильтрованных данных
        Set<String> nonEmptyColumns = new LinkedHashSet<>(columns);
        for (String col : columns) {
            if (visibleData.stream().noneMatch(row -> row.containsKey(col) && !row.get(col).isEmpty())) {
                nonEmptyColumns.remove(col);
            }
        }

        tableModel.setColumnIdentifiers(nonEmptyColumns.toArray());

        for (Map<String, String> row : visibleData) {
            Object[] rowData = nonEmptyColumns.stream().map(col -> row.getOrDefault(col, "")).toArray();
            tableModel.addRow(rowData);
        }

        adjustColumnWidths();
        updateFilterFields(nonEmptyColumns);
    }

    private void adjustColumnWidths() {
        for (int col = 0; col < table.getColumnCount(); col++) {
            TableColumn column = table.getColumnModel().getColumn(col);
            int width = 75;
            for (int row = 0; row < table.getRowCount(); row++) {
                Object value = table.getValueAt(row, col);
                if (value != null) {
                    width = Math.max(width, value.toString().length() * 7);
                }
            }
            column.setPreferredWidth(width);
        }
    }

    private void updateFilterFields(Set<String> nonEmptyColumns) {
        // Сохраняем текущие значения фильтров перед обновлением
        Map<String, String> previousFilters = new HashMap<>();
        for (Map.Entry<String, JTextField> entry : filterFields.entrySet()) {
            previousFilters.put(entry.getKey(), entry.getValue().getText());
        }

        filterFields.clear();
        filterPanel.removeAll();

        List<String> sortedColumns = new ArrayList<>(nonEmptyColumns);
        Collections.sort(sortedColumns);

        for (String column : sortedColumns) {
            JTextField field = new JTextField(10);
            field.setText(previousFilters.getOrDefault(column, "")); // Восстанавливаем значение фильтра
            filterFields.put(column, field);
            JPanel rowPanel = new JPanel();
            rowPanel.add(new JLabel(column + ":"));
            rowPanel.add(field);
            filterPanel.add(rowPanel);
        }

        JButton filterButton = new JButton("Filter");
        filterButton.addActionListener(e -> applyFilter());
        filterPanel.add(filterButton);
        filterPanel.revalidate();
        filterPanel.repaint();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(JsonViewer::new);
    }
}
