package br.com.vivo.ingestao.service;

/** Sinaliza erro de mapeamento de uma linha (ex.: oferta nao cadastrada). */
public class MapeamentoException extends RuntimeException {
    public MapeamentoException(String message) {
        super(message);
    }
}
