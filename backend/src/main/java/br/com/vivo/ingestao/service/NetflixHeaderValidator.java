package br.com.vivo.ingestao.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Valida o header do arquivo de eventos Netflix conforme layout TO BE (PARC-680).
 * Verifica presença e nome exato de todas as colunas obrigatórias.
 */
public final class NetflixHeaderValidator {

    /**
     * Colunas obrigatórias do layout TO BE do arquivo de eventos Netflix.
     * Fonte: PARC-680 / PARC-681 – Melhorias MVP1 – Atualização – Validação do header.
     */
    public static final List<String> COLUNAS_OBRIGATORIAS = List.of(
            "Report Month",
            "Partner Name",
            "Cancel Flag",
            "Billing Partner Handle",
            "Member Cohort",
            "PI Switcher",
            "Rejoin Timeframe (Months)",
            "Account ID",
            "Country",
            "Payment Program Type",
            "Bundle ID",
            "Bundle Name",
            "Base Plan",
            "Subscribed Plan",
            "Event Date",
            "Cancel Date",
            "Billing Cycle Days",
            "Subscrn Billing Cycle Days",
            "Base Plan Discount Rate",
            "Base Plan Subscrn Fee",
            "Discounted Base Plan Subscrn Fee",
            "Upgrade Discount Rate",
            "Upgrade Fee",
            "Discounted Upgrade Fee",
            "Effective Discount Rate",
            "Subscrn Fee",
            "Discounted Subscrn Fee",
            "Retail Amt",
            "Billing Amt",
            "Discount Amt",
            "Discounted Billing Amt"
    );

    private NetflixHeaderValidator() {
    }

    /**
     * Verifica se o conjunto de cabeçalhos do arquivo contém todas as colunas obrigatórias.
     *
     * @param headersDoArquivo colunas presentes no arquivo (primeira linha lida)
     * @throws HeaderValidationException caso haja colunas ausentes
     */
    public static void validar(Set<String> headersDoArquivo) {
        List<String> ausentes = new ArrayList<>();
        for (String coluna : COLUNAS_OBRIGATORIAS) {
            if (!headersDoArquivo.contains(coluna)) {
                ausentes.add(coluna);
            }
        }
        if (!ausentes.isEmpty()) {
            throw new HeaderValidationException(
                    "Formato do arquivo inválido: o header não corresponde ao layout esperado da Netflix. "
                            + ausentes.size() + " coluna(s) obrigatória(s) ausente(s): " + ausentes);
        }
    }
}
