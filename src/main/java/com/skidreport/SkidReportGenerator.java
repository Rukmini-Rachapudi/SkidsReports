package com.skidreport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class SkidReportGenerator {

    // ── ✏️  EDIT THESE TWO PATHS AS NEEDED ──────────────────────────────────
    private static final String INPUT_PATH  = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Input";
    private static final String OUTPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output";
    // ────────────────────────────────────────────────────────────────────────

    // ── ✏️  FILES PER BATCH ──────────────────────────────────────────────────
    private static final int BATCH_SIZE = 10;
    // ────────────────────────────────────────────────────────────────────────

    // ── CSV column headers ───────────────────────────────────────────────────
    private static final String H_DATE  = "Lcl Date";
    private static final String H_TIME  = "Lcl Time";
    private static final String H_PITCH = "Pitch";
    private static final String H_ROLL  = "Roll";
    private static final String H_LATAC = "LatAc";
    private static final String H_IAS   = "IAS";
    private static final String H_ALT   = "AltMSL";

    private static final String[] REQUIRED_COLS = { H_DATE, H_TIME, H_PITCH, H_ROLL, H_LATAC, H_IAS, H_ALT };

    private static final String[] MONTH_NAMES = {
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
    };

    private static final String[] ALL_TAIL_NUMBERS = {
            "41E","3FS","4FS","5FS","46A","47E","48A","49A",
            "13N","31T","41K","61J","83H","2JP",
            "06H","97B","59Y","78K","49K","29E","82P"
    };

    // ────────────────────────────────────────────────────────────────────────
    // MAIN
    // ────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws IOException {
        File rootDir   = new File(INPUT_PATH);
        File outputDir = new File(OUTPUT_PATH);

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found — " + INPUT_PATH);
            System.exit(1);
        }

        outputDir.mkdirs();
        System.out.println("Input      : " + INPUT_PATH);
        System.out.println("Output     : " + OUTPUT_PATH);
        System.out.println("Batch size : " + BATCH_SIZE + " files");

        File[] subdirs = rootDir.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length == 0) {
            System.out.println("No subdirectories found.");
            return;
        }

        boolean anyProcessed = false;
        for (File dir : subdirs) {
            String tail = detectTailNumber(dir.getName());
            if (tail != null) {
                System.out.println("\n--- Processing " + tail + " from " + dir.getAbsolutePath() + " ---");
                processFlightFolder(tail, dir, outputDir);
                anyProcessed = true;
            }
        }

        if (!anyProcessed) {
            System.out.println("No matching flight subfolders found.");
        }

        System.out.println("\nDone. Reports in: " + outputDir.getAbsolutePath());
    }

    // ────────────────────────────────────────────────────────────────────────
    // FLIGHT FOLDER
    // Collects all CSV files then hands off to processingViaBatches.
    // ────────────────────────────────────────────────────────────────────────
    private static void processFlightFolder(String tailNumber, File flightDir, File outputDir) {
        List<File> csvFiles = new ArrayList<>();
        collectCsvFiles(flightDir, csvFiles);
        System.out.println("  CSV files found : " + csvFiles.size());

        if (csvFiles.isEmpty()) {
            System.out.println("  Nothing to process for " + tailNumber);
            return;
        }

        processingViaBatches(tailNumber, csvFiles, outputDir);
    }

    // ────────────────────────────────────────────────────────────────────────
    // BATCH PROCESSOR
    //
    // 1. Split all CSV files into chunks of BATCH_SIZE (10 files at a time)
    // 2. Each batch: parse records → apply skid condition → deduplicate to
    //    minute level (GROUP BY date + HH:MM) → accumulate into byMonth map
    // 3. After ALL batches finish → write one Excel per month
    //    (months come from the data itself — no hardcoded date ranges)
    // ────────────────────────────────────────────────────────────────────────
    private static void processingViaBatches(String tailNumber,
                                             List<File> allFiles,
                                             File outputDir) {

        // Accumulator — key: "YYYY-MM" derived from each record's own date
        // Grows batch by batch; Excel written only after every batch is done
        Map<String, List<SkidEvent>> byMonth = new LinkedHashMap<>();

        int totalBatches = (int) Math.ceil((double) allFiles.size() / BATCH_SIZE);
        int batchNumber  = 0;

        // ── STEP 1 & 2: Read 10 files at a time, accumulate into byMonth ────
        for (int start = 0; start < allFiles.size(); start += BATCH_SIZE) {
            batchNumber++;
            int end = Math.min(start + BATCH_SIZE, allFiles.size());
            List<File> batch = allFiles.subList(start, end);

            System.out.printf("  [Batch %d/%d] Reading %d file(s) ...%n",
                    batchNumber, totalBatches, batch.size());

            // Per-batch intermediate map: "date|HH:MM" → accumulator
            // Deduplicates multiple sensor rows for the same minute (same as SQL GROUP BY date, HH:MM)
            Map<String, SkidMinuteAccumulator> minuteMap = new LinkedHashMap<>();

            for (File csv : batch) {
                try {
                    List<FlightRecord> records = parseCsvFile(csv);

                    FlightRecord prevRec = null;

                    for (FlightRecord rec : records) {
                        boolean isSkid = (rec.roll < -10 && rec.latAc >  0.2)
                                || (rec.roll >  10 && rec.latAc < -0.2);

                        boolean isLowAlt = false;
                        if (isSkid && prevRec != null) {
                            boolean decreasingIas = rec.ias < prevRec.ias;
                            boolean increasingPitch = rec.pitch > prevRec.pitch;
                            if (rec.alt < 1400 && (decreasingIas || increasingPitch)) {
                                isLowAlt = true;
                            }
                        }

                        prevRec = rec;

                        if (!isSkid) continue;


                        // Trim to HH:MM — collapses all seconds in the same minute into one event
                        String hhmm = toHHMM(rec.time);
                        String key  = rec.date + "|" + hhmm;

                        SkidMinuteAccumulator acc = minuteMap.get(key);
                        if (acc == null) {
                            acc = new SkidMinuteAccumulator(rec.date, hhmm);
                            minuteMap.put(key, acc);
                        }
                        acc.add(rec.pitch, rec.roll, rec.latAc, rec.ias, rec.alt, isLowAlt);
                    }

                    // records goes out of scope here — GC reclaims before next file loads

                } catch (Exception e) {
                    System.err.println("    [WARN] Skipping " + csv.getName() + " — " + e.getMessage());
                }
            }

            // Convert accumulators → SkidEvents and merge into byMonth
            // Month key ("YYYY-MM") comes directly from the record's own date — no hardcoding
            int eventsInBatch = 0;
            for (SkidMinuteAccumulator acc : minuteMap.values()) {
                String monthKey = yearMonthKey(acc.date);
                byMonth.computeIfAbsent(monthKey, k -> new ArrayList<>())
                        .add(acc.toSkidEvent());
                eventsInBatch++;
            }

            System.out.printf("  [Batch %d/%d] Complete — %d skid-minute(s) found.%n",
                    batchNumber, totalBatches, eventsInBatch);
        }

        // ── STEP 3: All batches done — write one Excel per month now ─────────
        System.out.println("  All batches processed. Writing output files ...");

        if (byMonth.isEmpty()) {
            System.out.println("  No skid events found for " + tailNumber);
            return;
        }

        int totalEvents = 0;
        for (Map.Entry<String, List<SkidEvent>> entry : new TreeMap<>(byMonth).entrySet()) {
            writeMonthlyExcel(tailNumber, entry.getKey(), entry.getValue(), outputDir);
            totalEvents += entry.getValue().size();
        }

        System.out.printf("  ✓ %d total skid-minute(s) across %d monthly file(s).%n",
                totalEvents, byMonth.size());
    }

    // ────────────────────────────────────────────────────────────────────────
    // CSV FILE COLLECTION
    // ────────────────────────────────────────────────────────────────────────
    private static void collectCsvFiles(File dir, List<File> result) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                collectCsvFiles(f, result);
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".csv")
                        && !name.contains("master")
                        && !name.contains("skid")) {
                    if (f.getName().length() > 7 && f.getName().substring(4, 7).equals("000")) {
                        continue;
                    }
                    result.add(f);
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // CSV PARSING
    // ────────────────────────────────────────────────────────────────────────
    private static List<FlightRecord> parseCsvFile(File file) throws IOException {
        List<String> lines = readLines(file);
        if (lines.size() < 4) return Collections.emptyList();

        // Row 0 = metadata, Row 1 = units, Row 2 = headers, Row 3+ = data
        String[] headers = splitCsv(lines.get(2));
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i].trim(), i);
        }

        for (String req : REQUIRED_COLS) {
            if (!colIndex.containsKey(req)) {
                System.out.println("    Skipping " + file.getName() + " — missing column: " + req);
                return Collections.emptyList();
            }
        }

        List<FlightRecord> records = new ArrayList<>();
        int lastDataLine = lines.size() - 1; // last line is footer, skip it
        for (int i = 3; i < lastDataLine; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] cols = splitCsv(line);
            try {
                String date = getCol(cols, colIndex, H_DATE).trim();
                String time = getCol(cols, colIndex, H_TIME).trim();
                if (date.isEmpty() || time.isEmpty()) continue;

                FlightRecord rec = new FlightRecord();
                rec.date  = date;
                rec.time  = time;
                rec.pitch = parseDouble(getCol(cols, colIndex, H_PITCH));
                rec.roll  = parseDouble(getCol(cols, colIndex, H_ROLL));
                rec.latAc = parseDouble(getCol(cols, colIndex, H_LATAC));
                rec.ias   = parseDouble(getCol(cols, colIndex, H_IAS));
                rec.alt   = parseDouble(getCol(cols, colIndex, H_ALT));

                if (!Double.isNaN(rec.roll) && !Double.isNaN(rec.latAc)) {
                    records.add(rec);
                }
            } catch (Exception ignored) {}
        }
        return records;
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "ISO-8859-1"))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    // ────────────────────────────────────────────────────────────────────────
    // EXCEL OUTPUT — one file per month, written after all batches complete
    // ────────────────────────────────────────────────────────────────────────
    private static void writeMonthlyExcel(String tail, String yearMonth,
                                          List<SkidEvent> events, File outputDir) {

        String[] parts      = yearMonth.split("-");
        String year         = parts[0];
        String monthNum     = parts[1];
        String monthNameStr = events.get(0).month;

        File reportDir = new File(outputDir, "Skid_Reports_" + tail);
        reportDir.mkdirs();

        String filename = String.format("Skids_%s_%s_%s_%s.xlsx", tail, year, monthNum, monthNameStr);
        File outFile    = new File(reportDir, filename);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Skid Events");

            // ── Header row ───────────────────────────────────────────────
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            Font hFont = wb.createFont();
            hFont.setBold(true);
            hFont.setColor(IndexedColors.WHITE.getIndex());
            hFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(hFont);

            String[] headers = {
                    "Local Date", "Local Time (HH:MM:SS)", "Local Month",
                    "Pitch", "Roll", "Lateral Acceleration",
                    "Indicated Air Speed", "Gps Altitude", "Skid Count", "Number of Skid Events",
                    "Low-Altitude-Skid-Events", "total-low-altitude-skid-event-count", "Flight name"
            };

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = hRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ── Cell styles ───────────────────────────────────────────────
            CellStyle numStyle = wb.createCellStyle();
            numStyle.setDataFormat(wb.createDataFormat().getFormat("0.00"));
            numStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle centerStyle = wb.createCellStyle();
            centerStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle altStyle = wb.createCellStyle();
            altStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            altStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            altStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle altNumStyle = wb.createCellStyle();
            altNumStyle.cloneStyleFrom(numStyle);
            altNumStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            altNumStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Calculate total low-altitude events (minutes that had at least one low-alt skid second)
            int totalLowAltEvents = 0;
            for (SkidEvent ev : events) {
                if (ev.lowAltFrequency > 0) totalLowAltEvents++;
            }

            // ── Data rows ─────────────────────────────────────────────────
            int rowNum = 1;
            for (SkidEvent ev : events) {
                Row row = sheet.createRow(rowNum);
                boolean alt  = (rowNum % 2 == 0);
                CellStyle cs  = alt ? altStyle    : centerStyle;
                CellStyle csN = alt ? altNumStyle : numStyle;

                createStrCell(row, 0, ev.date,           cs);
                createStrCell(row, 1, ev.minuteTime,     cs);
                createStrCell(row, 2, ev.month,          cs);
                createNumCell(row, 3, ev.avgPitch,       csN);
                createNumCell(row, 4, ev.avgRoll,        csN);
                createNumCell(row, 5, ev.avgLatAc,       csN);
                createNumCell(row, 6, ev.avgIas,         csN);
                createNumCell(row, 7, ev.avgAlt,         csN);
                createIntCell(row, 8, ev.skidFrequency,  cs);

                // Column J (index 9): Number of Skid Events (Total in Row 2)
                if (rowNum == 1) {
                    createIntCell(row, 9, events.size(), cs);
                }

                // Column K (index 10): Low-altitude skid indicator (1 per row)
                if (ev.lowAltFrequency > 0) {
                    createIntCell(row, 10, 1, cs);
                }

                // Column L (index 11): total-low-altitude-skid-event-count (Total in Row 2)
                if (rowNum == 1) {
                    createIntCell(row, 11, totalLowAltEvents, cs);
                }

                // Column M (index 12): Flight name (for all rows)
                createStrCell(row, 12, tail, cs);

                rowNum++;
            }

            // ── Auto-size columns ─────────────────────────────────────────
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 512);
            }

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }

            System.out.printf("    Written : %s  [%d skid-minute(s)]%n",
                    outFile.getName(), events.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName() + ": " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // DATA MODELS
    // ────────────────────────────────────────────────────────────────────────

    static class FlightRecord {
        String date, time;
        double pitch, roll, latAc, ias, alt;
    }

    /** Accumulates all qualifying seconds within one (date, HH:MM) bucket */
    static class SkidMinuteAccumulator {
        final String date;
        final String minuteTime;
        double sumPitch, sumRoll, sumLatAc, sumIas, sumAlt;
        int count;
        int lowAltCount;

        SkidMinuteAccumulator(String date, String minuteTime) {
            this.date       = date;
            this.minuteTime = minuteTime;
        }

        void add(double pitch, double roll, double latAc, double ias, double alt, boolean isLowAlt) {
            sumPitch += pitch; sumRoll += roll; sumLatAc += latAc;
            sumIas   += ias;   sumAlt  += alt;  count++;
            if (isLowAlt) lowAltCount++;
        }

        SkidEvent toSkidEvent() {
            SkidEvent ev      = new SkidEvent();
            ev.date           = date;
            ev.minuteTime     = minuteTime;
            ev.month          = monthName(date);
            ev.skidFrequency  = count;
            ev.lowAltFrequency = lowAltCount;
            ev.avgPitch       = round2(sumPitch / count);
            ev.avgRoll        = round2(sumRoll  / count);
            ev.avgLatAc       = round2(sumLatAc / count);
            ev.avgIas         = round2(sumIas   / count);
            ev.avgAlt         = round2(sumAlt   / count);
            return ev;
        }
    }

    /** One output row — one unique (date, HH:MM) skid minute */
    static class SkidEvent {
        String date, minuteTime, month;
        double avgPitch, avgRoll, avgLatAc, avgIas, avgAlt;
        int skidFrequency; // qualifying seconds in this minute
        int lowAltFrequency; // qualifying low-altitude seconds in this minute
    }

    // ────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ────────────────────────────────────────────────────────────────────────

    /** "HH:MM:SS" → "HH:MM"  (mirrors SQL SUBSTR(Local_Time, 0, 5)) */
    private static String toHHMM(String time) {
        if (time == null || time.length() < 5) return time;
        return time.substring(0, 5);
    }

    private static String[] splitCsv(String line) {
        return line.split("\\s*,\\s*", -1);
    }

    private static String getCol(String[] cols, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        return (i != null && i < cols.length) ? cols[i] : "";
    }

    private static double parseDouble(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Derives "YYYY-MM" directly from the record's date — handles both YYYY-MM-DD and MM/DD/YYYY */
    private static String yearMonthKey(String date) {
        if (date == null || date.length() < 7) return "0000-00";
        if (date.charAt(4) == '-') return date.substring(0, 7);
        if (date.contains("/")) {
            String[] p = date.split("/");
            if (p.length >= 3) return p[2] + "-" + String.format("%02d", Integer.parseInt(p[0]));
        }
        return date.substring(0, 7);
    }

    private static String monthName(String date) {
        try {
            String ym = yearMonthKey(date);
            int m = Integer.parseInt(ym.substring(5));
            if (m >= 1 && m <= 12) return MONTH_NAMES[m - 1];
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private static String detectTailNumber(String folderName) {
        for (String tail : ALL_TAIL_NUMBERS) {
            if (folderName.contains(tail)) return tail;
        }
        return null;
    }

    private static void createStrCell(Row row, int col, String val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(val != null ? val : "");
        cell.setCellStyle(style);
    }

    private static void createNumCell(Row row, int col, double val, CellStyle style) {
        Cell cell = row.createCell(col);
        if (!Double.isNaN(val)) cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    private static void createIntCell(Row row, int col, int val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }
}