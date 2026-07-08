package br.com.vivo.ingestao.service.converter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonToExcelConverter {

    private static final Logger log = LoggerFactory.getLogger(JsonToExcelConverter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // Excel 2007+ max rows per sheet = 1,048,576 (including header)
    private static final int MAX_ROWS_PER_SHEET     = SpreadsheetVersion.EXCEL2007.getMaxRows();
    private static final int MAX_DATA_ROWS_PER_SHEET = MAX_ROWS_PER_SHEET - 1; // row 0 = header
    private static final int SXSSF_WINDOW_SIZE       = 500;
    private static final String[] HEADERS = {
            "Report_Month", "Partner_Name", "Cancel_Flag", "Billing_Partner_Handle",
            "Member_Cohort", "PI_Switcher", "Rejoin_Timeframe_Months", "Account_ID",
            "Country", "Payment_Program_Type", "Bundle_ID", "Bundle_Name",
            "Base_Plan", "Subscribed_Plan", "Event_Date", "Cancel_Date",
            "Billing_Cycle_Days", "Subscrn_Billing_Cycle_Days",
            "Base_Plan_Discount_Rate", "Base_Plan_Subscrn_Fee",
            "Discounted_Base_Plan_Subscrn_Fee", "Upgrade_Discount_Rate",
            "Upgrade_Fee", "Discounted_Upgrade_Fee", "Effective_Discount_Rate",
            "Subscrn_Fee", "Discounted_Subscrn_Fee", "Retail_Amt", "Billing_Amt",
            "Discount_Amt", "Discounted_Billing_Amt",
            "Valor_Bruto_Prorata", "Valor_Liquido", "Valor_Liquido_Arredondado",
            "Valor_Repasse"
    };

    /**
     * Streaming conversion following mais_um_export.xlsx model:
     *  - Sheet "Resumo": header block (parceiro, contrato, repasse_total, periodo)
     *  - Sheet "Detalhe" (+ "Detalhe_2", etc.): event rows, auto-split at Excel row limit
     */
    public void convertJsonToExcel(String inputJsonPath, String outputExcelPath) throws IOException {
        Path in  = Path.of(inputJsonPath);
        Path out = Path.of(outputExcelPath);

        try (InputStream is     = Files.newInputStream(in);
             JsonParser parser  = mapper.getFactory().createParser(is);
             SXSSFWorkbook wb   = new SXSSFWorkbook(SXSSF_WINDOW_SIZE);
             OutputStream os    = Files.newOutputStream(out)) {

            wb.setCompressTempFiles(true);

            // Styles (created once, reused for all rows)
            CellStyle titleStyle  = createTitleStyle(wb);
            CellStyle labelStyle  = createLabelStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle dataStyle   = createDataStyle(wb);

            // Header data to populate Resumo sheet later
            String   parceiro     = "";
            String   contrato     = "";
            double   repasseTotal = 0.0;
            String   periodo      = "";

            SheetState state = new SheetState();
            long totalRows   = 0;

            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("JSON inválido: esperado objeto raiz.");
            }

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                parser.nextToken();

                switch (fieldName) {
                    case "header" -> {
                        // Read header object fully (small, safe to tree-read)
                        JsonNode headerNode = mapper.readTree(parser);
                        parceiro     = text(headerNode, "parceiro");
                        contrato     = text(headerNode, "contrato");
                        repasseTotal = headerNode.path("repasse_total").asDouble(0.0);
                    }
                    case "events" -> {
                        // Create Detalhe sheet before processing events
                        createDetalheSheet(wb, headerStyle, state);

                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            JsonNode event    = mapper.readTree(parser);
                            JsonNode raw      = event.path("raw_fields");
                            JsonNode processed = event.path("processed_data");

                            // Capture period from first event
                            if (periodo.isEmpty()) {
                                periodo = text(raw, "report_month");
                            }

                            // Roll over to new sheet if current is full
                            if (state.dataRowsInSheet >= MAX_DATA_ROWS_PER_SHEET) {
                                createDetalheSheet(wb, headerStyle, state);
                            }

                            Row row = state.sheet.createRow(state.currentRowIndex++);
                            state.dataRowsInSheet++;
                            writeRow(row, raw, processed, dataStyle);
                            totalRows++;
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            // Add Resumo sheet as FIRST sheet (move it to index 0)
            createResumoSheet(wb, parceiro, contrato, repasseTotal, periodo,
                    titleStyle, labelStyle);
            wb.setSheetOrder("Resumo", 0);

            wb.write(os);
            wb.dispose();

            log.info("Excel gerado: {} | linhas={} | sheets={}",
                    outputExcelPath, totalRows, state.sheetNumber + 1);
        }
    }

    // -------------------------------------------------------------------------
    // Resumo sheet — mirrors mais_um_export.xlsx layout
    // -------------------------------------------------------------------------
    private void createResumoSheet(SXSSFWorkbook wb,
                                   String parceiro, String contrato,
                                   double repasseTotal, String periodo,
                                   CellStyle titleStyle, CellStyle labelStyle) {
        SXSSFSheet sheet = wb.createSheet("Resumo");

        // Row 1: title "DEMONSTRATIVO DE REPASSE" merged across C1:E1
        Row r1 = sheet.createRow(0);
        Cell title = r1.createCell(2);
        title.setCellValue("DEMONSTRATIVO DE REPASSE");
        title.setCellStyle(titleStyle);

        // Row 2: parceiro + periodo
        Row r2 = sheet.createRow(1);
        setLabelCell(r2, 1, "parceiro:", labelStyle);
        r2.createCell(2).setCellValue(parceiro);
        setLabelCell(r2, 3, "Periodo", labelStyle);
        r2.createCell(4).setCellValue(periodo);

        // Row 3: contrato + total repasse
        Row r3 = sheet.createRow(2);
        setLabelCell(r3, 1, "contrato:", labelStyle);
        r3.createCell(2).setCellValue(contrato);
        setLabelCell(r3, 3, "Total Repasse:", labelStyle);
        r3.createCell(4).setCellValue(repasseTotal);
    }

    private static void setLabelCell(Row row, int col, String label, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(label);
        c.setCellStyle(style);
    }

    // -------------------------------------------------------------------------
    // Detalhe sheet
    // -------------------------------------------------------------------------
    private void createDetalheSheet(SXSSFWorkbook wb, CellStyle headerStyle, SheetState state) {
        state.sheetNumber++;
        String name = state.sheetNumber == 0 ? "Detalhe" : "Detalhe_" + (state.sheetNumber + 1);
        state.sheet            = wb.createSheet(name);
        state.currentRowIndex  = 0;
        state.dataRowsInSheet  = 0;

        Row headerRow = state.sheet.createRow(state.currentRowIndex++);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(HEADERS[i]);
            c.setCellStyle(headerStyle);
        }
    }

    // -------------------------------------------------------------------------
    // Data row writer
    // -------------------------------------------------------------------------
    private void writeRow(Row row, JsonNode raw, JsonNode processed, CellStyle style) {
        setCell(row, 0,  text(raw, "report_month"),                              style);
        setCell(row, 1,  text(raw, "partner_name"),                              style);
        setCell(row, 2,  bool(raw, "cancel_flag"),                               style);
        setCell(row, 3,  text(raw, "billing_partner_handle"),                    style);
        setCell(row, 4,  text(raw, "member_cohort"),                             style);
        setCell(row, 5,  text(raw, "pi_switcher"),                               style);
        setCell(row, 6,  num(raw,  "rejoin_timeframe_months"),                   style);
        setCell(row, 7,  text(raw, "account_id"),                                style);
        setCell(row, 8,  text(raw, "country"),                                   style);
        setCell(row, 9,  text(raw, "payment_program_type"),                      style);
        setCell(row, 10, text(raw, "bundle_id"),                                 style);
        setCell(row, 11, text(raw, "bundle_name"),                               style);
        setCell(row, 12, text(raw, "base_plan"),                                 style);
        setCell(row, 13, text(raw, "subscribed_plan"),                           style);
        setCell(row, 14, text(raw, "event_date"),                                style);
        setCell(row, 15, text(raw, "cancel_date"),                               style);
        setCell(row, 16, num(raw,  "billing_cycle_days"),                        style);
        setCell(row, 17, num(raw,  "subscrn_billing_cycle_days"),                style);
        setCell(row, 18, num(raw,  "base_plan_discount_rate"),                   style);
        setCell(row, 19, num(raw,  "base_plan_subscrn_fee"),                     style);
        setCell(row, 20, num(raw,  "discounted_base_plan_subscrn_fee"),          style);
        setCell(row, 21, num(raw,  "upgrade_discount_rate"),                     style);
        setCell(row, 22, num(raw,  "upgrade_fee"),                               style);
        setCell(row, 23, num(raw,  "discounted_upgrade_fee"),                    style);
        setCell(row, 24, num(raw,  "effective_discount_rate"),                   style);
        setCell(row, 25, num(raw,  "subscrn_fee"),                               style);
        setCell(row, 26, num(raw,  "discounted_subscrn_fee"),                    style);
        setCell(row, 27, num(raw,  "retail_amt"),                                style);
        setCell(row, 28, num(raw,  "billing_amt"),                               style);
        setCell(row, 29, num(raw,  "discount_amt"),                              style);
        setCell(row, 30, num(raw,  "discounted_billing_amt"),                    style);
        setCell(row, 31, num(processed, "valor_bruto_prorata"),                  style);
        setCell(row, 32, num(processed, "valor_liquido"),                        style);
        setCell(row, 33, num(processed, "valor_liquido_arred"),                  style);
        setCell(row, 34, num(processed, "valor_repasse"),                        style);
    }

    // -------------------------------------------------------------------------
    // Style factories
    // -------------------------------------------------------------------------
    private CellStyle createTitleStyle(SXSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private CellStyle createLabelStyle(SXSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private CellStyle createHeaderStyle(SXSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();

        // Bold white font
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);

        // Solid blue fill — matches Excel theme color in mais_um_export.xlsx
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Thin border
        BorderStyle thin = BorderStyle.THIN;
        s.setBorderBottom(thin);
        s.setBorderTop(thin);
        s.setBorderLeft(thin);
        s.setBorderRight(thin);

        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private CellStyle createDataStyle(SXSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        BorderStyle thin = BorderStyle.THIN;
        s.setBorderBottom(thin);
        s.setBorderTop(thin);
        s.setBorderLeft(thin);
        s.setBorderRight(thin);
        return s;
    }

    // -------------------------------------------------------------------------
    // Cell helpers
    // -------------------------------------------------------------------------
    private static final class SheetState {
        org.apache.poi.ss.usermodel.Sheet sheet;
        int sheetNumber     = -1;
        int currentRowIndex = 0;
        int dataRowsInSheet = 0;
    }

    private void setCell(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellStyle(style);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof String s) {
            cell.setCellValue(s);
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    private Double num(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asDouble();
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asBoolean();
    }
}
