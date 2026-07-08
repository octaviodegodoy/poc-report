package br.com.vivo.ingestao.api.dto;

import java.util.List;

/** Resultado consolidado de uma importacao com medicoes de desempenho (TMS). */
public record ImportacaoResult(
        String periodoReferencia,
        long lidas,
        long importadas,
        long duplicadas,
        long comErro,
        List<String> erros,
        /** URL relativa para download do CSV de registros com erro. Null quando não há erros. */
        String csvErrosUrl,
        long tempoTotalMs,
        long tempoLeituraMs,
        long tempoMapeamentoMs,
        long tempoInsercaoMs) {
}
