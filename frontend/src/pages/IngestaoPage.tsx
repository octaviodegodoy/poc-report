import React, { useState } from "react";
import "./IngestaoPage.css";

interface ImportacaoResult {
  periodoReferencia: string;
  lidas: number;
  importadas: number;
  duplicadas: number;
  comErro: number;
  erros: string[];
  csvErrosUrl: string | null;
  tempoTotalMs: number;
  tempoLeituraMs: number;
  tempoMapeamentoMs: number;
  tempoInsercaoMs: number;
}

function formatMs(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(2) + " s";
  return ms + " ms";
}

function throughput(importadas: number, ms: number): string {
  if (ms <= 0) return "-";
  return Math.round((importadas / ms) * 1000).toLocaleString("pt-BR") + " trans/s";
}

function periodoAtual(): string {
  const hoje = new Date();
  const mes = String(hoje.getMonth() + 1).padStart(2, "0");
  return `${hoje.getFullYear()}-${mes}`;
}

/** Extrai o filename do header Content-Disposition (ex: attachment; filename="x.xlsx"). */
function filenameFromContentDisposition(headerValue: string | null, fallback: string): string {
  if (!headerValue) return fallback;
  const match = headerValue.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
  return match ? decodeURIComponent(match[1]) : fallback;
}

export default function IngestaoPage() {
  const [periodoReferencia, setPeriodoReferencia] = useState<string>(periodoAtual());
  const [file, setFile] = useState<File | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [result, setResult] = useState<ImportacaoResult | null>(null);

  const [nomeArquivoJson, setNomeArquivoJson] = useState<string>("");
  const [isConverting, setIsConverting] = useState(false);
  const [convertMessage, setConvertMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [isConvertingCsv, setIsConvertingCsv] = useState(false);
  const [convertCsvMessage, setConvertCsvMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  const [resourceFile, setResourceFile] = useState<File | null>(null);
  const [isSavingResource, setIsSavingResource] = useState(false);
  const [saveResourceMessage, setSaveResourceMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFile(e.target.files?.[0] ?? null);
    setResult(null);
    setMessage(null);
  };

  const podeEnviar = !!file && /^\d{4}-\d{2}$/.test(periodoReferencia) && !isLoading;

  const handleSubmit = async () => {
    if (!podeEnviar || !file) return;

    setIsLoading(true);
    setProgress(0);
    setMessage(null);
    setResult(null);

    const progressInterval = setInterval(() => {
      setProgress((p) => Math.min(p + Math.random() * 25, 90));
    }, 400);

    try {
      const formData = new FormData();
      formData.append("file", file);
      formData.append("periodo_referencia", periodoReferencia);

      const response = await fetch("/api/ingestao/importar", {
        method: "POST",
        body: formData,
      });

      clearInterval(progressInterval);
      setProgress(100);

      if (response.ok) {
        const data: ImportacaoResult = await response.json();
        setResult(data);
        setMessage({
          type: "success",
          text: `Importação concluída: ${data.importadas} transações gravadas.`,
        });
      } else if (response.status === 422) {
        // Header inválido: formato do arquivo não corresponde ao layout esperado
        let texto = "Formato do arquivo inválido.";
        try {
          const err = await response.json();
          if (err?.mensagem) texto = err.mensagem;
        } catch {
          /* sem corpo JSON */
        }
        setMessage({ type: "error", text: texto });
      } else {
        let texto = "Erro ao executar a importação.";
        try {
          const err = await response.json();
          if (err?.mensagem) texto = err.mensagem;
        } catch {
          /* sem corpo JSON */
        }
        setMessage({ type: "error", text: texto });
      }
    } catch (error) {
      clearInterval(progressInterval);
      setMessage({ type: "error", text: "Falha de comunicação com o servidor." });
    } finally {
      setIsLoading(false);
    }
  };

  const handleNomeArquivoJson = (e: React.ChangeEvent<HTMLInputElement>) => {
    setNomeArquivoJson(e.target.value);
    setConvertMessage(null);
    setConvertCsvMessage(null);
  };

  const podeConverter = !!nomeArquivoJson.trim() && !isConverting;

  const handleConverterExcel = async () => {
    if (!podeConverter) return;

    const nome = nomeArquivoJson.trim();
    setIsConverting(true);
    setConvertMessage(null);

    try {
      const response = await fetch(`/api/ingestao/converter-excel?arquivo=${encodeURIComponent(nome)}`, {
        method: "GET",
      });

      if (response.ok) {
        const blob = await response.blob();
        const nomeArquivo = filenameFromContentDisposition(
          response.headers.get("Content-Disposition"),
          nome.replace(/\.json$/i, "") + ".xlsx"
        );

        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = nomeArquivo;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);

        setConvertMessage({ type: "success", text: `Arquivo ${nomeArquivo} gerado com sucesso.` });
      } else {
        let texto = "Erro ao converter o arquivo.";
        try {
          const err = await response.json();
          if (err?.mensagem) texto = err.mensagem;
        } catch {
          /* sem corpo JSON */
        }
        setConvertMessage({ type: "error", text: texto });
      }
    } catch (error) {
      setConvertMessage({ type: "error", text: "Falha de comunicação com o servidor." });
    } finally {
      setIsConverting(false);
    }
  };

  const podeConverterCsv = !!nomeArquivoJson.trim() && !isConvertingCsv;

  const handleConverterCsv = async () => {
    if (!podeConverterCsv) return;

    const nome = nomeArquivoJson.trim();
    setIsConvertingCsv(true);
    setConvertCsvMessage(null);

    try {
      const response = await fetch(`/api/ingestao/converter-csv?arquivo=${encodeURIComponent(nome)}`, {
        method: "GET",
      });

      if (response.ok) {
        const blob = await response.blob();
        const nomeArquivo = filenameFromContentDisposition(
          response.headers.get("Content-Disposition"),
          nome.replace(/\.json$/i, "") + ".csv"
        );

        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = nomeArquivo;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);

        setConvertCsvMessage({ type: "success", text: `Arquivo ${nomeArquivo} gerado com sucesso.` });
      } else {
        let texto = "Erro ao converter o arquivo.";
        try {
          const err = await response.json();
          if (err?.mensagem) texto = err.mensagem;
        } catch {
          /* sem corpo JSON */
        }
        setConvertCsvMessage({ type: "error", text: texto });
      }
    } catch (error) {
      setConvertCsvMessage({ type: "error", text: "Falha de comunicação com o servidor." });
    } finally {
      setIsConvertingCsv(false);
    }
  };

  const handleResourceFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    setResourceFile(e.target.files?.[0] ?? null);
    setSaveResourceMessage(null);
  };

  const podeSalvarResource = !!resourceFile && !isSavingResource;

  const handleSalvarResource = async () => {
    if (!podeSalvarResource || !resourceFile) return;

    setIsSavingResource(true);
    setSaveResourceMessage(null);

    try {
      const formData = new FormData();
      formData.append("file", resourceFile);

      const response = await fetch("/api/ingestao/salvar-json", {
        method: "POST",
        body: formData,
      });

      if (response.ok) {
        const data = await response.json();
        setSaveResourceMessage({
          type: "success",
          text: data?.mensagem ?? `Arquivo ${resourceFile.name} salvo em resources.`,
        });
      } else {
        let texto = "Erro ao salvar o arquivo.";
        try {
          const err = await response.json();
          if (err?.mensagem) texto = err.mensagem;
        } catch {
          /* sem corpo JSON */
        }
        setSaveResourceMessage({ type: "error", text: texto });
      }
    } catch (error) {
      setSaveResourceMessage({ type: "error", text: "Falha de comunicação com o servidor." });
    } finally {
      setIsSavingResource(false);
    }
  };

  return (
    <>
      <div className="page-header">
        <h1>Carga da Base</h1>
        <p>Carregue o arquivo do parceiro e gere as transações canônicas.</p>
      </div>

      {message && <div className={`message message-${message.type}`}>{message.text}</div>}

      <div className="ingestao-card">
        <h3>Parâmetros da carga</h3>

        <div className="field">
          <label htmlFor="periodo">Período de referência</label>
          <input
            id="periodo"
            type="month"
            value={periodoReferencia}
            onChange={(e) => setPeriodoReferencia(e.target.value)}
            disabled={isLoading}
          />
          <div className="hint">Formato AAAA-MM. Define o período (calendar/periodoCompensacao) das transações.</div>
        </div>

        <div className="field">
          <label htmlFor="arquivo">Arquivo de eventos (CSV)</label>
          <input id="arquivo" type="file" accept=".csv" onChange={handleFile} disabled={isLoading} />
          {file && <div className="hint">{file.name} · {(file.size / 1024 / 1024).toFixed(2)} MB</div>}
        </div>

        <button className="btn-primary" onClick={handleSubmit} disabled={!podeEnviar}>
          {isLoading ? "Processando…" : "Iniciar ingestão"}
        </button>

        {isLoading && (
          <div className="progress-bar">
            <div style={{ width: `${progress}%` }} />
          </div>
        )}
      </div>

      <div className="ingestao-card">
        <h3>Converter JSON</h3>

        {convertMessage && <div className={`message message-${convertMessage.type}`}>{convertMessage.text}</div>}
        {convertCsvMessage && (
          <div className={`message message-${convertCsvMessage.type}`}>{convertCsvMessage.text}</div>
        )}

        <div className="field">
          <label htmlFor="nomeArquivoJson">Nome do arquivo em resources</label>
          <input
            id="nomeArquivoJson"
            type="text"
            placeholder="teste_large.json"
            value={nomeArquivoJson}
            onChange={handleNomeArquivoJson}
            disabled={isConverting || isConvertingCsv}
          />
          <div className="hint">
            Converte um arquivo que já está em backend/src/main/resources (use "Salvar em
            resources" abaixo para colocar um lá primeiro).
          </div>
        </div>

        <button className="btn-primary" onClick={handleConverterExcel} disabled={!podeConverter}>
          {isConverting ? "Convertendo…" : "Converter para Excel"}
        </button>{" "}
        <button className="btn-primary" onClick={handleConverterCsv} disabled={!podeConverterCsv}>
          {isConvertingCsv ? "Convertendo…" : "Converter para CSV"}
        </button>

        {(isConverting || isConvertingCsv) && (
          <div className="hint">
            Arquivos grandes (GBs) podem levar vários minutos. Não feche esta aba.
          </div>
        )}
      </div>

      <div className="ingestao-card">
        <h3>Salvar JSON em resources</h3>

        {saveResourceMessage && (
          <div className={`message message-${saveResourceMessage.type}`}>{saveResourceMessage.text}</div>
        )}

        <div className="field">
          <label htmlFor="arquivoResource">Arquivo JSON</label>
          <input
            id="arquivoResource"
            type="file"
            accept=".json,application/json"
            onChange={handleResourceFile}
            disabled={isSavingResource}
          />
          {resourceFile && (
            <div className="hint">{resourceFile.name} · {(resourceFile.size / 1024 / 1024).toFixed(2)} MB</div>
          )}
          <div className="hint">
            Apenas copia o arquivo para backend/src/main/resources (nome original preservado,
            sobrescreve se já existir).
          </div>
        </div>

        <button className="btn-primary" onClick={handleSalvarResource} disabled={!podeSalvarResource}>
          {isSavingResource ? "Salvando…" : "Salvar em resources"}
        </button>
      </div>

      {result && (
        <div className="ingestao-card">
          <h3>Resultado · {result.periodoReferencia}</h3>
          <div className="result-grid">
            <div className="result-item">
              <div className="valor">{result.lidas}</div>
              <div className="rotulo">Linhas lidas</div>
            </div>
            <div className="result-item">
              <div className="valor">{result.importadas}</div>
              <div className="rotulo">Importadas</div>
            </div>
            <div className="result-item">
              <div className="valor">{result.duplicadas}</div>
              <div className="rotulo">Duplicadas ignoradas</div>
            </div>
            <div className="result-item erro">
              <div className="valor">{result.comErro}</div>
              <div className="rotulo">Com erro</div>
            </div>
          </div>

          {result.csvErrosUrl && (
            <div className="download-erros">
              <a className="btn-download" href={result.csvErrosUrl} download>
                ⬇ Baixar arquivo de erros ({result.comErro} registro{result.comErro === 1 ? "" : "s"})
              </a>
              <div className="hint">
                O CSV contém cada linha rejeitada com o motivo do erro na última coluna (ERRO).
              </div>
            </div>
          )}

          {result.erros.length > 0 && (
            <div className="erros-list">
              {result.erros.map((erro, i) => (
                <div key={i}>{erro}</div>
              ))}
            </div>
          )}

          <div className="tms-section">
            <h4>Desempenho (TPS)</h4>
            <div className="tms-grid">
              <div className="tms-item tms-total">
                <div className="tms-valor">{formatMs(result.tempoTotalMs)}</div>
                <div className="tms-rotulo">Tempo total</div>
              </div>
              <div className="tms-item">
                <div className="tms-valor">{formatMs(result.tempoLeituraMs)}</div>
                <div className="tms-rotulo">Leitura CSV</div>
              </div>
              <div className="tms-item">
                <div className="tms-valor">{formatMs(result.tempoMapeamentoMs)}</div>
                <div className="tms-rotulo">Mapeamento</div>
              </div>
              <div className="tms-item">
                <div className="tms-valor">{formatMs(result.tempoInsercaoMs)}</div>
                <div className="tms-rotulo">Ins. banco</div>
              </div>
              <div className="tms-item tms-throughput">
                <div className="tms-valor">{throughput(result.importadas, result.tempoTotalMs)}</div>
                <div className="tms-rotulo">TPS</div>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}