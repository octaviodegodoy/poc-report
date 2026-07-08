package br.com.vivo.ingestao.service.converter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonToCsvConverter {

    private static final Logger log = LoggerFactory.getLogger(JsonToCsvConverter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // RFC 4180 line ending
    private static final String CRLF = "\r\n";
    private static final char DELIMITER = ',';

    // UTF-8 BOM so Excel opens the file with correct accents (ã, ç, ...) on double-click
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

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
     * Streaming JSON -> CSV conversion. Mirrors JsonToExcelConverter's "Detalhe" sheet:
     * one CSV row per event, same 35 columns/order. The "header" block (parceiro,
     * contrato, repasse_total) is not representable in a flat CSV and is skipped —
     * use JsonToExcelConverter if that summary is needed.
     */
    public void convertJsonToCsv(String inputJsonPath, String outputCsvPath) throws IOException {
        Path in  = Path.of(inputJsonPath);
        Path out = Path.of(outputCsvPath);

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        long totalRows = 0;

        try (InputStream is    = Files.newInputStream(in);
             JsonParser parser = mapper.getFactory().createParser(is);
             OutputStream os   = Files.newOutputStream(out)) {

            os.write(UTF8_BOM);

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

                writeHeaderRow(writer);

                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("JSON inválido: esperado objeto raiz.");
                }

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldName = parser.currentName();
                    parser.nextToken();

                    if ("events".equals(fieldName)) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            JsonNode event     = mapper.readTree(parser);
                            JsonNode raw       = event.path("raw_fields");
                            JsonNode processed = event.path("processed_data");

                            writeDataRow(writer, raw, processed);
                            totalRows++;
                        }
                    } else {
                        parser.skipChildren();
                    }
                }

                writer.flush();
            }
        }

        log.info("CSV gerado: {} | linhas={}", outputCsvPath, totalRows);
    }

    // -------------------------------------------------------------------------
    // Row writers
    // -------------------------------------------------------------------------
    private void writeHeaderRow(BufferedWriter writer) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HEADERS.length; i++) {
            if (i > 0) sb.append(DELIMITER);
            sb.append(escape(HEADERS[i]));
        }
        sb.append(CRLF);
        writer.write(sb.toString());
    }

    private void writeDataRow(BufferedWriter writer, JsonNode raw, JsonNode processed) throws IOException {
        StringBuilder sb = new StringBuilder();
        int col = 0;

        col = appendField(sb, col, text(raw, "report_month"));
        col = appendField(sb, col, text(raw, "partner_name"));
        col = appendField(sb, col, bool(raw, "cancel_flag"));
        col = appendField(sb, col, text(raw, "billing_partner_handle"));
        col = appendField(sb, col, text(raw, "member_cohort"));
        col = appendField(sb, col, text(raw, "pi_switcher"));
        col = appendField(sb, col, num(raw,  "rejoin_timeframe_months"));
        col = appendField(sb, col, text(raw, "account_id"));
        col = appendField(sb, col, text(raw, "country"));
        col = appendField(sb, col, text(raw, "payment_program_type"));
        col = appendField(sb, col, text(raw, "bundle_id"));
        col = appendField(sb, col, text(raw, "bundle_name"));
        col = appendField(sb, col, text(raw, "base_plan"));
        col = appendField(sb, col, text(raw, "subscribed_plan"));
        col = appendField(sb, col, text(raw, "event_date"));
        col = appendField(sb, col, text(raw, "cancel_date"));
        col = appendField(sb, col, num(raw,  "billing_cycle_days"));
        col = appendField(sb, col, num(raw,  "subscrn_billing_cycle_days"));
        col = appendField(sb, col, num(raw,  "base_plan_discount_rate"));
        col = appendField(sb, col, num(raw,  "base_plan_subscrn_fee"));
        col = appendField(sb, col, num(raw,  "discounted_base_plan_subscrn_fee"));
        col = appendField(sb, col, num(raw,  "upgrade_discount_rate"));
        col = appendField(sb, col, num(raw,  "upgrade_fee"));
        col = appendField(sb, col, num(raw,  "discounted_upgrade_fee"));
        col = appendField(sb, col, num(raw,  "effective_discount_rate"));
        col = appendField(sb, col, num(raw,  "subscrn_fee"));
        col = appendField(sb, col, num(raw,  "discounted_subscrn_fee"));
        col = appendField(sb, col, num(raw,  "retail_amt"));
        col = appendField(sb, col, num(raw,  "billing_amt"));
        col = appendField(sb, col, num(raw,  "discount_amt"));
        col = appendField(sb, col, num(raw,  "discounted_billing_amt"));
        col = appendField(sb, col, num(processed, "valor_bruto_prorata"));
        col = appendField(sb, col, num(processed, "valor_liquido"));
        col = appendField(sb, col, num(processed, "valor_liquido_arred"));
        appendField(sb, col, num(processed, "valor_repasse"));

        sb.append(CRLF);
        writer.write(sb.toString());
    }

    private int appendField(StringBuilder sb, int col, Object value) {
        if (col > 0) sb.append(DELIMITER);
        sb.append(escape(toCsvString(value)));
        return col + 1;
    }

    // -------------------------------------------------------------------------
    // Field/value helpers
    // -------------------------------------------------------------------------
    private String toCsvString(Object value) {
        if (value == null) {
            return "";
        } else if (value instanceof String s) {
            return s;
        } else if (value instanceof Boolean b) {
            return b.toString();
        } else if (value instanceof Double d) {
            // Whole-number doubles print without a trailing ".0", matching how
            // Excel's General format displays integer-valued cells.
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString(d.longValue());
            }
            return d.toString();
        }
        return String.valueOf(value);
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuoting = value.indexOf(DELIMITER) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
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
