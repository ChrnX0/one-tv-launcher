// Cartão "Instalar como app (https)" da aba Extras (13/11/2026). O certificado é feito pela própria TV (PainelTls.kt);
// o passo a passo fica na página /certificado, montada no servidor.

function dataCurta(ms) {
  return new Date(ms).toLocaleDateString('pt-BR');
}

function desenharCertificado(c) {
  const link = $('linkHttps');
  // Não sobrescreve o que a pessoa está digitando.
  if (c.nome && document.activeElement !== $('nomeCertificado')) $('nomeCertificado').value = c.nome;
  if (!c.pronto || !c.ligado) {
    mostrar($('textoCertificado'), 'A TV ainda está gerando o certificado. Volte em 1 minuto.');
    link.hidden = true;
    return;
  }
  let texto = `Endereço com cadeado: ${c.endereco}. Válido até ${dataCurta(c.validoAte)}. ` +
    `Impressão digital: ${c.impressao.slice(0, 23)}…`;
  if (c.diasRestantes < 60) texto += ` Vence em ${c.diasRestantes} dias: gere um novo e instale de novo nos aparelhos.`;
  if (c.nomeNoCertificado === false) texto += ` O nome "${c.nome}" ainda não está no certificado: toque em Gerar certificado novo.`;
  if (!c.ipNoCertificado) texto += ' O IP da TV mudou desde o certificado: pelo nome continua funcionando; pelo IP, gere um novo.';
  mostrar($('textoCertificado'), texto);
  // Já está no https? Então o link não tem para onde levar.
  link.hidden = location.protocol === 'https:' && location.port === String(c.porta);
  link.href = c.endereco;
}

async function carregarCertificado() {
  try { desenharCertificado(await chamar('GET', '/api/certificado')); }
  catch (e) { mostrar($('textoCertificado'), textoErro(e)); }
}

document.addEventListener('aba', (ev) => { if (ev.detail === 'extras') carregarCertificado(); });

$('btnCertificadoNovo').addEventListener('click', async () => {
  const nome = $('nomeCertificado').value.trim().toLowerCase();
  if (nome && !/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(nome)) {
    mostrar($('statusCertificado'), 'Nome inválido: uma palavra só, com letras, números e hífen (ex.: tv-sala).');
    return;
  }
  const ok = await confirmar('Gerar certificado novo?',
    (nome ? `Endereço com cadeado: https://${nome}:8444/ (e ${nome}.lan, ${nome}.local e o IP). ` : '') +
    'Os aparelhos que já instalaram o certificado atual vão parar de abrir o painel com cadeado até instalarem o novo ' +
    '(o painel sem cadeado continua funcionando). Use isto se o certificado venceu, se o IP da TV mudou ou se um aparelho se perdeu.',
    'Gerar novo', () => chamar('POST', '/api/certificado/renovar', nome ? { nome } : {}));
  if (!ok) return;
  mostrar($('statusCertificado'), 'Certificado novo pronto. Instale de novo nos aparelhos (Passo a passo).');
  carregarCertificado();
});
