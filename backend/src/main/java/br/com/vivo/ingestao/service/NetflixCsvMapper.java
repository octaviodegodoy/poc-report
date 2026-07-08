package br.com.vivo.ingestao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Converte uma linha do CSV de eventos Netflix no envelope canonico REQUEST,
 * conforme contrato TB_JSON_TRANSACAO.
 */
@Component
public class NetflixCsvMapper {

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("M/d/yyyy");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param row                linha do CSV (cabecalho -> valor)
     * @param periodoReferencia  periodo informado na tela (YYYY-MM), autoritativo para a carga
     * @param produtosPorChave   mapa chave_identificacao_oferta (Bundle Name) -> nome do produto (eco_product)
     * @param valoresPorProduto  mapa id_produto (Subscribed Plan) -> valor_referencia (valor bruto)
     */
    public LinhaTransacao mapear(Map<String, String> row, String periodoReferencia,
                                 Map<String, String> produtosPorChave,
                                 Map<String, BigDecimal> valoresPorProduto) {

        String bundleName = txt(row, "Bundle Name");
        String subscribedPlan = txt(row, "Subscribed Plan");
        String eventDate = txt(row, "Event Date");

        // chave_identificacao_oferta = Bundle_Name (sem concatenar com Subscribed_Plan)
        String chaveOferta = bundleName;
        String produtoNome = produtosPorChave.get(chaveOferta);
        if (produtoNome == null) {
            throw new MapeamentoException("Oferta nao cadastrada em eco_product para Bundle Name: " + chaveOferta);
        }

        // id_produto = Subscribed_Plan direciona a lookup do valor bruto (valor_referencia)
        BigDecimal valorReferencia = valoresPorProduto.get(subscribedPlan);
        if (valorReferencia == null) {
            throw new MapeamentoException("Valor de referencia nao cadastrado para Subscribed Plan: " + subscribedPlan);
        }

        // Hash determinístico da linha completa do arquivo do parceiro:
        // a unicidade do hash é a base da deduplicação de eventos.
        String orderId = HashUtil.sha256Row(row);

        ObjectNode request = objectMapper.createObjectNode();

        ObjectNode salesTransaction = request.putObject("salesTransaction");
        salesTransaction.putObject("salesOrder").put("id", orderId);
        salesTransaction.putObject("lineNumber").put("value", 1);
        salesTransaction.putObject("subLineNumber").put("value", 1);
        salesTransaction.putObject("eventType").put("id", "COBRANDED_NETFLIX");
        salesTransaction.putObject("value").put("value", valorReferencia);
        salesTransaction.put("genericNumber1", num(row, "Base Plan Discount Rate"));
        salesTransaction.put("genericNumber2", intVal(row, "Billing Cycle Days"));
        salesTransaction.put("genericNumber3", intVal(row, "Subscrn Billing Cycle Days"));
        salesTransaction.put("compensationDate", toIsoDate(eventDate));

        ObjectNode product = salesTransaction.putObject("product");
        product.put("id", produtoNome);
        product.put("name", produtoNome);
        product.put("type", "FIXA");
        product.put("genericBoolean6", true);

        ObjectNode position = request.putObject("position");
        position.put("code", "PDV-001");
        position.put("planName", "PLANO_A");
        position.put("startDate", "2024-01-01");
        position.put("endDate", "2024-12-31");

        ObjectNode participant = request.putObject("participant");
        participant.put("code", "GER-001");
        participant.put("classification", "A");
        participant.put("quota", "100");
        participant.put("status", "ATIVO");

        request.put("genericAttribute1", txt(row, "Member Cohort"));
        request.put("genericAttribute2", txt(row, "Payment Program Type"));
        request.put("genericAttribute3", subscribedPlan);
        request.put("businessUnit", "RESIDENCIAL");
        request.put("calendar", periodoReferencia);
        request.put("periodoCompensacao", periodoReferencia);

        return new LinhaTransacao(orderId, 1, 1, periodoReferencia, request.toString());
    }

    private static String txt(Map<String, String> row, String coluna) {
        String v = row.get(coluna);
        return v == null ? "" : v.trim();
    }

    private static BigDecimal num(Map<String, String> row, String coluna) {
        String v = txt(row, coluna).replace(",", "");
        if (v.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new MapeamentoException("Valor numerico invalido na coluna '" + coluna + "': " + v);
        }
    }

    private static int intVal(Map<String, String> row, String coluna) {
        String v = txt(row, coluna);
        if (v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v.split("\\.")[0]);
        } catch (NumberFormatException e) {
            throw new MapeamentoException("Valor inteiro invalido na coluna '" + coluna + "': " + v);
        }
    }

    private static String toIsoDate(String eventDate) {
        if (eventDate == null || eventDate.isBlank()) {
            throw new MapeamentoException("Event Date ausente");
        }
        try {
            return LocalDate.parse(eventDate.trim(), CSV_DATE).format(ISO_DATE);
        } catch (Exception e) {
            throw new MapeamentoException("Event Date invalido: " + eventDate);
        }
    }
}
