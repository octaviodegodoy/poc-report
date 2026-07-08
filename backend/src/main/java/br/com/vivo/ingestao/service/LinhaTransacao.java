package br.com.vivo.ingestao.service;

/** Linha ja mapeada e pronta para insercao em tb_json_transacao. */
public record LinhaTransacao(
        String orderid,
        int lineNumber,
        int subLineNumber,
        String periodo,
        String requestJson) {
}
