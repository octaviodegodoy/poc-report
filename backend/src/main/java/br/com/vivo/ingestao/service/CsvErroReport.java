package br.com.vivo.ingestao.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Acumula registros com erro e gera um arquivo CSV de críticas.
 * Cada linha do CSV contém todos os campos originais do registro
 * seguidos de uma coluna extra "ERRO" com a descrição da falha.
 *
 * Thread-safe: usa lista sincronizada internamente.
 */
public class CsvErroReport {

    /** Nome da coluna extra adicionada ao final de cada linha de erro. */
    public static final String COLUNA_ERRO = "ERRO";

    private final List<String> colunas;
    private final List<String[]> linhas = Collections.synchronizedList(new ArrayList<>());

    /**
     * @param colunasOrigem lista ordenada das colunas do arquivo original (sem a coluna ERRO)
     */
    public CsvErroReport(List<String> colunasOrigem) {
        this.colunas = List.copyOf(colunasOrigem);
    }

    /**
     * Registra uma linha defeituosa com seu motivo de erro.
     *
     * @param row   mapa coluna->valor da linha original
     * @param erro  descrição do erro
     */
    public void adicionar(Map<String, String> row, String erro) {
        String[] campos = new String[colunas.size() + 1];
        for (int i = 0; i < colunas.size(); i++) {
            String v = row.get(colunas.get(i));
            campos[i] = v == null ? "" : v;
        }
        campos[colunas.size()] = erro;
        linhas.add(campos);
    }

    /** Retorna true se não houver nenhum erro registrado. */
    public boolean vazio() {
        return linhas.isEmpty();
    }

    /** Número de registros com erro. */
    public int tamanho() {
        return linhas.size();
    }

    /**
     * Grava o CSV (UTF-8 com BOM) no stream fornecido.
     * O cabeçalho inclui todas as colunas originais + "ERRO".
     *
     * @param out stream de destino (não fechado por este método)
     */
    public void escrever(OutputStream out) throws IOException {
        // BOM para compatibilidade com Excel
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        // cabeçalho
        w.write(montarLinha(colunas.toArray(new String[0]), COLUNA_ERRO));

        List<String[]> snapshot = List.copyOf(linhas);
        for (String[] campos : snapshot) {
            w.write(montarLinhaArray(campos));
        }
        w.flush();
    }

    // -----------------------------------------------------------------------

    private static String montarLinha(String[] colunasCabecalho, String colunaExtra) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colunasCabecalho.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escapar(colunasCabecalho[i]));
        }
        sb.append(',').append(escapar(colunaExtra)).append('\n');
        return sb.toString();
    }

    private static String montarLinhaArray(String[] campos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escapar(campos[i]));
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Envolve o valor em aspas duplas se contiver vírgula, aspas ou quebra de linha.
     * Aspas internas são duplicadas (RFC 4180).
     */
    static String escapar(String valor) {
        if (valor == null) return "";
        if (valor.contains(",") || valor.contains("\"") || valor.contains("\n") || valor.contains("\r")) {
            return '"' + valor.replace("\"", "\"\"") + '"';
        }
        return valor;
    }
}
