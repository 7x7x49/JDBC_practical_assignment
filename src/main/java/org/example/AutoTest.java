/*
Составить тест-кейс для проверки работы БД, при добавлении нового товара. Составить SQL-запрос для получения таблицы со
списком, удалить созданный товар из таблицы.

Подключение к стенду:
Для запуска стенда создайте папку "Working Project" на диске C
Скопировать в папку файл qualit-sandbox.jar: https://drive.google.com/file/d/18bI8rR9uPjVUNbSPIXBs84qViW0_VFpg/view
Запустить файл
После запуска стенда перейти по ссылке:
http://localhost:8080/h2-console/

Параметры БД:
БД: h2 Embedded
URL: jdbc:h2:tcp://localhost:9092/mem:testdb
Login: user
Password: pass
*/

package org.example;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class H2TestRunner {
    private static final String JAR_FILE = "qualit-sandbox.jar"; //Файл, который необходимо установить перед обращением к БД
    private static final String EXCEL_FILE = "SQL2.xlsx"; //Файл с тест-кейсом
    private static final String SHEET_NAME = "Лист1"; //Текущий лист в файле с тест-кейсом

    private static final String JDBC_URL = "jdbc:h2:tcp://localhost:9092/mem:testdb";
    private static final String JDBC_USER = "user";
    private static final String JDBC_PASS = "pass";

    private static Process jarProcess; //Запуск файла qualit-sandbox.jar

    public static void main(String[] args) throws InterruptedException {
        //Запуск Excel-файла
        try {
            startJarIfPresent();
            runTestsFromExcel(EXCEL_FILE, SHEET_NAME);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stopJarIfStarted();
        }
    }

    //Запуск jar файла
    private static void startJarIfPresent() throws IOException {
        File jar = Path.of(JAR_FILE).toFile();
        if (!jar.exists()) { //На случай, если файл уже запущен
            System.out.println("Jar-файл '" + JAR_FILE + "'не найден. Вероятно, Вы запустили его вручную.");
            return;
        }
        System.out.println("Запуск файла " + JAR_FILE + "...");
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", JAR_FILE);
        pb.redirectErrorStream(true);
        jarProcess = pb.start();
    }

    //Выключение jar-файла
    private static void stopJarIfStarted() {
        if (jarProcess != null && jarProcess.isAlive()) {
            jarProcess.destroy();
        }
    }

    //Если Excel-файл отсутствует:
    private static void runTestsFromExcel(String excelPath, String sheetName) throws Exception {
        File f = new File(excelPath);
        if (!f.exists()) {
            throw new FileNotFoundException("Excel-файл не найден: " + excelPath);
        }

        List<TestCase> tests = parseExcel(excelPath, sheetName);
        System.out.println("\u001B[35m" + "Найдено " + tests.size() + " тестов..." + "\u001B[0m");

        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
            for (TestCase tc : tests) {
                //Получение множества ID записей о еде из базы данных перед выполнением операций
                Set<Integer> beforeIds = fetchFoodIds(conn);

                boolean expectedError = tc.expected != null && tc.expected.toLowerCase().contains("появление ошибки"); //Если ожидаемый результат не пустой и содержит фразу "появление ошибки"
                boolean hadSqlException = false; //Флаг для отслеживания факта возникновения ошибки
                String executionResult;

                //Выполнение и получение результата
                try {
                    try (Statement stmt = conn.createStatement()) {
                        boolean hasResultSet = stmt.execute(tc.sql);
                        if (hasResultSet) {
                            try (ResultSet rs = stmt.getResultSet()) {
                                executionResult = resultSetToString(rs);
                            }
                        } else {
                            int updated = stmt.getUpdateCount();
                            executionResult = ("Затронута " + updated + " запись.");
                        }
                    }
                } catch (SQLException sqle) {
                    hadSqlException = true;
                    executionResult = "SQLException: " + sqle.getMessage();
                }

                boolean mismatch = expectedError != hadSqlException;

                //Печать теста со считыванием из Excel файла (красный при несовпадении результата реального и ожидаемого)
                if (mismatch) {
                    System.out.println(red("\n========================"));
                    System.out.printf(boldRed("ID: ") + red("%s%n") + boldRed("Название: ") + red("%s%n"), tc.id, tc.name);
                    System.out.println(boldRed("Запрос в БД: ") + red((tc.sql == null ? "<empty>" : tc.sql)));
                    System.out.println(boldRed("Шаги: ") + red(optional(tc.steps)));
                    System.out.println(boldRed("Тестовые данные: ") + red(optional(tc.testData)));
                    System.out.println(boldRed("Ожидаемый результат: ") + red(optional(tc.expected)));
                    System.out.println(red("-----------------------------"));
                } else {
                    System.out.println("\n========================");
                    System.out.printf(bold("ID: %s%nНазвание: %s%n"), tc.id, tc.name);
                    System.out.println(bold("Запрос в БД: ") + (tc.sql == null ? "NULL" : tc.sql));
                    System.out.println(bold("Шаги: ") + optional(tc.steps));
                    System.out.println(bold("Тестовые данные: ") + optional(tc.testData));
                    System.out.println(bold("Ожидаемый результат: ") + optional(tc.expected));
                    System.out.println("-----------------------------");
                }

                System.out.println(bold("\nФактический результат:"));
                System.out.println(executionResult);

                //Сохранение текущего FOOD_ID
                Set<Integer> afterIds = fetchFoodIds(conn);
                Set<Integer> created = new HashSet<>(afterIds);
                created.removeAll(beforeIds);

                //Результаты выполнения запроса
                if (!created.isEmpty()) {
                    System.out.println(bold("\nОписание SQL:"));
                    List<Map<String, Object>> rows = fetchRowsByIds(conn, created);
                    System.out.println(rowsToPrettyString(rows));
                } else {
                    System.out.println(bold("\nОписание SQL:"));
                    System.out.println("Не затронута ни одна строка.");
                }

                //Удаление созданных записей и вывод результата удаления (так как было задание удалить продукты при успешном создании записи)
                System.out.println("\nУдаляем запись...");
                if (!created.isEmpty()) {
                    int deleted = cleanupCreatedAndReturnCount(conn, created);
                    System.out.println(bold("Удалена строка со значением FOOD_ID " + created));

                } else {
                    System.out.println(bold("Удаление не было совершено."));
                }
                System.out.println("========================");
            }
        }
    }

    private static String optional(String s) { //Проверка на заполненность Excel ячейки
        return s == null ? "NULL" : s;
    }
    /*
    Метод получает все ID еды из таблицы FOOD и возвращает их как множество. Если возникает ошибка
    (например, таблица не существует), метод возвращает пустое множество вместо исключения
     */
    private static Set<Integer> fetchFoodIds(Connection conn) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        String q = "SELECT FOOD_ID FROM FOOD";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(q)) {
            while (rs.next()) {
                ids.add(rs.getInt("FOOD_ID"));
            }
        } catch (SQLException ex) {
            return ids;
        }
        return ids;
    }

    //Получение строк из таблицы FOOD по указанным ID и возвращение их в виде списка
    private static List<Map<String, Object>> fetchRowsByIds(Connection conn, Set<Integer> ids) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return result;
        StringBuilder sb = new StringBuilder("SELECT * FROM FOOD WHERE FOOD_ID IN (");
        Iterator<Integer> it = ids.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append(",");
        }
        sb.append(")");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sb.toString())) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                result.add(row);
            }
        }
        return result;
    }

    //Изменение списка строк из таблицы в текстовую строку для вывода в консоль
    private static String rowsToPrettyString(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "NULL";
        StringBuilder sb = new StringBuilder();
        int r = 0;
        for (Map<String, Object> row : rows) {
            r++;
            boolean first = true;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append("=").append(String.valueOf(e.getValue()));
                first = false;
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    //Удаление созданных записей из таблицы FOOD
    private static int cleanupCreatedAndReturnCount(Connection conn, Set<Integer> createdIds) throws SQLException {
        if (createdIds == null || createdIds.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM FOOD WHERE FOOD_ID IN (");
        Iterator<Integer> it = createdIds.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append(",");
        }
        sb.append(")");
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(sb.toString());
        }
    }

    //Форматирование каждой строки после выполнения команд для отслеживания создания количества записей
    private static String resultSetToString(ResultSet rs) throws SQLException {
        StringBuilder sb = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        int rows = 0;
        while (rs.next()) {
            rows++;
            sb.append("[row ").append(rows).append("] ");
            for (int i = 1; i <= cols; i++) {
                sb.append(md.getColumnLabel(i)).append("=").append(rs.getObject(i));
                if (i < cols) sb.append(", ");
            }
            sb.append(System.lineSeparator());
        }
        if (rows == 0) return "NULL";
        return sb.toString();
    }

    //Чтение Excel файла и извлечение данных
    private static List<TestCase> parseExcel(String excelPath, String sheetName) throws IOException {
        List<TestCase> result = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(excelPath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null) {
                sheet = wb.getSheetAt(0);
            }

            Row header = sheet.getRow(0);
            Map<String, Integer> colIndex = new HashMap<>();
            for (Cell c : header) {
                String h = c.getStringCellValue().trim();
                colIndex.put(h, c.getColumnIndex());
            }

            int lastRow = sheet.getLastRowNum();
            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String id = getCellString(row, colIndex.getOrDefault("ID", -1));
                String name = getCellString(row, colIndex.getOrDefault("Название", -1));
                String steps = getCellString(row, colIndex.getOrDefault("Шаги", -1));
                String expected = getCellString(row, colIndex.getOrDefault("Ожидаемый результат", -1));
                String testData = getCellString(row, colIndex.getOrDefault("Тестовые данные", -1));

                String sql = extractSqlFromSteps(steps);
                if (sql == null || sql.isBlank()) {
                    System.out.printf("Не удалось извлечь SQL-запрос.");
                    sql = "";
                }

                TestCase tc = new TestCase(id, name, steps, testData, expected, sql);
                result.add(tc);
            }
        }
        return result;
    }

    //Извлечение значения ячейки Excel в виде строки
    private static String getCellString(Row row, int idx) {
        if (idx < 0) return null;
        Cell c = row.getCell(idx);
        if (c == null) return null;
        if (c.getCellType() == CellType.STRING) return c.getStringCellValue();
        if (c.getCellType() == CellType.NUMERIC) {
            double d = c.getNumericCellValue();
            if (Math.floor(d) == d) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        if (c.getCellType() == CellType.BOOLEAN) return String.valueOf(c.getBooleanCellValue());
        return c.toString();
    }

    //Поиск SQL-запроса
    private static String extractSqlFromSteps(String steps) {
        if (steps == null) return null;
        Pattern p = Pattern.compile("(?i)(INSERT|UPDATE|DELETE|SELECT|CREATE|ALTER).*?;", Pattern.DOTALL);
        Matcher m = p.matcher(steps);
        if (m.find()) {
            return m.group().trim();
        }
        return null;
    }

    //Методы для оформления консоли
    private static String bold(String text) { //Делает строку Bold
        return "\u001B[1m" + text + "\u001B[0m";
    }
    private static String red(String text) { //Делает строку красной
        return "\u001B[31m" + text + "\u001B[0m";
    }
    private static String boldRed(String text) { //Делает строку и Bold, и красной
        return "\u001B[1;31m" + text + "\u001B[0m";
    }

    //Внутренний класс TestCase (для хранения информации, извлеченной из Excel-файла)
    private static class TestCase {
        String id;
        String name;
        String steps;
        String testData;
        String expected;
        String sql;
        TestCase(String id, String name, String steps, String testData, String expected, String sql) {
            this.id = id;
            this.name = name;
            this.steps = steps;
            this.testData = testData;
            this.expected = expected;
            this.sql = sql;
        }
    }
}
