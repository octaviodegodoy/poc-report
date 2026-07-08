package br.com.vivo.ingestao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public final class HashUtil {

    private HashUtil() {
    }

    // Reutiliza a instância de MessageDigest por thread, evitando getInstance() a cada linha
    private static final ThreadLocal<MessageDigest> THREAD_MD = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    });

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** SHA-256 em hexadecimal de partes unidas por pipe. */
    public static String sha256Hex(String... partes) {
        return sha256Hex(String.join("|", partes));
    }

    /**
     * SHA-256 determinístico da linha completa do arquivo do parceiro.
     * Todas as colunas recebidas entram no hash, em ordem estável por nome de coluna,
     * garantindo que a unicidade do hash represente a unicidade da linha.
     */
    public static String sha256Row(Map<String, String> row) {
        // pré-aloca com margem para ~30 colunas de ~25 chars cada
        StringBuilder base = new StringBuilder(1024);
        for (Map.Entry<String, String> campo : new TreeMap<>(row).entrySet()) {
            String valor = campo.getValue() == null ? "" : campo.getValue().trim();
            base.append(campo.getKey()).append('=').append(valor).append('\n');
        }
        return sha256Hex(base.toString());
    }

    private static String sha256Hex(String base) {
        MessageDigest md = THREAD_MD.get();
        md.reset();
        byte[] digest = md.digest(base.getBytes(StandardCharsets.UTF_8));
        char[] sb = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            sb[i * 2]     = HEX[(digest[i] >> 4) & 0xF];
            sb[i * 2 + 1] = HEX[digest[i] & 0xF];
        }
        return new String(sb);
    }
}
