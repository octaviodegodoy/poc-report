package br.com.vivo.ingestao.service;

/**
 * Sinaliza que o header do arquivo não contém todas as colunas obrigatórias.
 * A carga deve ser abortada quando esta exceção for lançada (PARC-379 / PARC-680).
 */
public class HeaderValidationException extends RuntimeException {
    public HeaderValidationException(String message) {
        super(message);
    }
}
