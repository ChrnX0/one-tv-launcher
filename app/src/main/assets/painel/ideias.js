// Ideias de 12/11/2026 na aba Extras: avisos no celular, Meu time, mural, presença, semana e modo simples.
// Rotas em /api/extras/ideias/ (PainelIdeias.kt). Todo texto entra por textContent (nomes vêm da TV e do roteador).
'use strict';

document.addEventListener('aba', (ev) => { if (ev.detail === 'extras') carregarIdeias(); });

function el(tag, classe, texto) {
  const e = document.createElement(tag);
  if (classe) e.className = classe;
  if (texto !== undefined) e.textContent = texto;
  return e;
}

function botao(texto, fazer) {
  const b = el('button', 'botao-secundario', texto);
  b.type = 'button';
  b.addEventListener('click', fazer);
  return b;
}

const ideias = (metodo, rota, corpo) => chamar(metodo, '/api/extras/ideias/' + rota, corpo);

async function carregarIdeias() {
  try {
    const e = await ideias('GET', 'estado');
    desenharAvisos(e.avisos);
    desenharLembretes(e.lembretes);
    desenharMural(e.mural);
    desenharAniversarios(e.aniversarios || []);
    if (e.cartoes) desenharCartoesHome(e.cartoes);
    desenharRadios(e.radios || []);
    desenharPodcasts(e.podcasts || []);
    desenharSemana(e.retrospectiva, e.desempenho);
    desenharSimples(e.simples);
    presencaEscolhidos = new Set(e.presenca);
    if (e.presencaOutras) { $('presencaHa').value = e.presencaOutras.entidades || ''; $('presencaIps').value = e.presencaOutras.ips || ''; }
  } catch (err) { mostrar($('statusAvisos'), textoErro(err)); }
}

// ---------- avisos no celular (Web Push) ----------

let chaveVapid = null;

function desenharAvisos(a) {
  chaveVapid = a.chave;
  const lista = $('listaAvisos');
  lista.replaceChildren();
  if (!a.aparelhos.length) lista.append(el('p', 'aviso', 'Nenhum celular recebe avisos ainda.'));
  for (const ap of a.aparelhos) {
    const linha = el('div', 'linha-registro');
    linha.append(el('strong', '', ap.nome), el('span', 'quando', ap.servico + ' · desde ' + new Date(ap.desde).toLocaleDateString()),
      botao('Remover', async () => {
        try { desenharAvisos(await ideias('POST', 'avisos/remover', { id: ap.id })); } catch (err) { mostrar($('statusAvisos'), textoErro(err)); }
      }));
    lista.append(linha);
  }
  const tipos = $('tiposAvisos');
  tipos.replaceChildren();
  for (const t of a.tipos) {
    const rotulo = el('label');
    const caixa = el('input'); caixa.type = 'checkbox'; caixa.checked = t.ligado;
    caixa.addEventListener('change', async () => {
      try { await ideias('POST', 'avisos/tipo', { tipo: t.tipo, ligado: caixa.checked }); }
      catch (err) { caixa.checked = !caixa.checked; mostrar($('statusAvisos'), textoErro(err)); }
    });
    rotulo.append(caixa, ' ' + t.rotulo);
    tipos.append(rotulo);
  }
}

/** base64url -> bytes (a chave VAPID que o navegador pede). */
function deB64url(s) {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

$('btnAvisosInscrever').addEventListener('click', async () => {
  const st = $('statusAvisos');
  if (!window.isSecureContext || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    mostrar(st, 'Este navegador não recebe avisos por aqui. Abra o painel com cadeado (https, porta 8444) no Chrome do celular: veja Extras, "Instalar como app".');
    return;
  }
  if (!chaveVapid) { mostrar(st, 'A TV não criou a chave dos avisos. Tente de novo.'); return; }
  try {
    if (await Notification.requestPermission() !== 'granted') { mostrar(st, 'Permissão de notificação negada no navegador.'); return; }
    const reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    await navigator.serviceWorker.ready;
    let ins = await reg.pushManager.getSubscription();
    if (!ins) ins = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: deB64url(chaveVapid) });
    const nome = $('nomeAvisos').value.trim() || 'celular';
    desenharAvisos(await ideias('POST', 'avisos/inscrever', { inscricao: ins.toJSON(), nome }));
    mostrar(st, 'Pronto: este aparelho recebe os avisos da TV.');
  } catch (err) { mostrar(st, textoErro(err)); }
});

$('btnAvisosTestar').addEventListener('click', async () => {
  try { mostrar($('statusAvisos'), 'Teste: ' + (await ideias('POST', 'avisos/testar')).resultado); }
  catch (err) { mostrar($('statusAvisos'), textoErro(err)); }
});

// ---------- Meu time e lembretes ----------

function desenharLembretes(l) {
  $('palavrasMeuTime').value = l.palavras || '';
  const lista = $('listaLembretes');
  lista.replaceChildren();
  if (!l.lembretes.length) lista.append(el('p', 'aviso', 'Nenhum lembrete nas próximas 24 h.'));
  for (const x of l.lembretes) {
    const linha = el('div', 'linha-registro');
    linha.append(el('strong', '', x.hora + ' · ' + x.titulo),
      el('span', 'quando', x.canal + (x.palavra ? ' · Meu time: ' + x.palavra : ' · marcado à mão')),
      botao('Apagar', async () => {
        try { desenharLembretes(await ideias('POST', 'lembretes/apagar', { chave: x.chave })); }
        catch (err) { mostrar($('statusLembretes'), textoErro(err)); }
      }));
    lista.append(linha);
  }
}

$('btnPalavrasMeuTime').addEventListener('click', async () => {
  try {
    desenharLembretes(await ideias('POST', 'palavras', { texto: $('palavrasMeuTime').value }));
    mostrar($('statusLembretes'), 'Salvo. A TV procura no guia em alguns segundos; abra esta aba de novo para ver os lembretes.');
  } catch (err) { mostrar($('statusLembretes'), textoErro(err)); }
});

// ---------- mural ----------

function desenharMural(m) {
  const lista = $('itensMural');
  lista.replaceChildren();
  const grupos = [['compras', 'Compras', m.compras], ['recados', 'Recados', m.recados]];
  for (const [lista_, nome, itens] of grupos) {
    if (!itens.length) continue;
    lista.append(el('strong', '', nome));
    for (const t of itens) {
      const linha = el('div', 'linha-registro');
      const lido = lista_ === 'recados' && (m.lidos || []).includes(t);
      linha.append(el('span', '', lido ? '✓ ' + t + ' (lido)' : t));
      if (lista_ === 'recados') linha.append(botao(lido ? 'Não lido' : 'Lido', async () => {
        try { desenharMural(await ideias('POST', 'mural', { lista: lista_, acao: lido ? 'nao_lido' : 'lido', texto: t })); } catch (_) {}
      }));
      linha.append(botao('Tirar', async () => {
        try { desenharMural(await ideias('POST', 'mural', { lista: lista_, acao: 'remover', texto: t })); } catch (_) {}
      }));
      lista.append(linha);
    }
  }
  if (!m.compras.length && !m.recados.length) lista.append(el('p', 'aviso', 'Mural vazio.'));
}

$('formMural').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const texto = $('textoMural').value.trim();
  if (!texto) return;
  try {
    desenharMural(await ideias('POST', 'mural', { lista: $('listaMural').value, acao: 'adicionar', texto }));
    $('textoMural').value = '';
  } catch (_) {}
});

// ---------- podcasts (Ouvir parte 2, 17/11/2026) ----------

function desenharPodcasts(lista) {
  const caixa = $('itensPodcasts');
  caixa.replaceChildren();
  lista.forEach((p) => {
    const linha = el('div', 'linha-registro');
    linha.append(el('span', '', p.titulo + (p.autor ? ' · ' + p.autor : '')), botao('Deixar de seguir', async () => {
      try { desenharPodcasts((await ideias('POST', 'podcasts', { acao: 'remover', url: p.url })).lista); } catch (_) {}
    }));
    caixa.append(linha);
  });
  if (!lista.length) caixa.append(el('p', 'aviso', 'Nenhum podcast seguido.'));
}

$('formPodcast').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const url = $('urlPodcast').value.trim();
  if (!url) return;
  mostrar($('statusPodcasts'), 'Lendo o feed…');
  try {
    desenharPodcasts((await ideias('POST', 'podcasts', { acao: 'colar', url })).lista);
    $('urlPodcast').value = '';
    mostrar($('statusPodcasts'), 'Podcast na TV.');
  } catch (err) { mostrar($('statusPodcasts'), textoErro(err)); }
});

$('arquivoOpml').addEventListener('change', async (ev) => {
  const arq = ev.target.files && ev.target.files[0];
  ev.target.value = '';
  if (!arq) return;
  if (arq.size > 500 * 1024) { mostrar($('statusPodcasts'), 'Lista grande demais.'); return; }
  mostrar($('statusPodcasts'), 'Importando…');
  try {
    const r = await ideias('POST', 'podcasts/opml', { opml: await arq.text() });
    desenharPodcasts(r.lista);
    mostrar($('statusPodcasts'), r.falharam ? `Seguindo ${r.assinados}. Ficaram de fora: ${r.falharam}.` : `Seguindo ${r.assinados}.`);
  } catch (err) { mostrar($('statusPodcasts'), textoErro(err)); }
});

$('exportarOpml').addEventListener('click', async () => {
  try {
    const r = await ideias('GET', 'podcasts/opml');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([r.opml], { type: 'text/xml' }));
    a.download = 'podcasts-sofaly.opml';
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  } catch (err) { mostrar($('statusPodcasts'), textoErro(err)); }
});

// ---------- rádios (ideia 87) ----------

function desenharRadios(lista) {
  const caixa = $('itensRadios');
  caixa.replaceChildren();
  lista.forEach((r, i) => {
    const linha = el('div', 'linha-registro');
    linha.append(el('span', '', (i < 9 ? (i + 1) + '  ' : '') + r.nome), botao('Tirar', async () => {
      try { desenharRadios((await ideias('POST', 'radios', { acao: 'remover', id: r.id })).lista); } catch (_) {}
    }));
    caixa.append(linha);
  });
  if (!lista.length) caixa.append(el('p', 'aviso', 'Nenhuma rádio favorita.'));
}

$('formRadio').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeRadio').value.trim(), url = $('urlRadio').value.trim();
  if (!nome || !url) return;
  try {
    desenharRadios((await ideias('POST', 'radios', { acao: 'colar', nome, url })).lista);
    $('nomeRadio').value = ''; $('urlRadio').value = '';
    mostrar($('statusRadios'), 'Rádio na TV.');
  } catch (err) { mostrar($('statusRadios'), textoErro(err)); }
});

// ---------- aniversários (ideia 93) ----------

const MESES = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];

function desenharAniversarios(lista) {
  const caixa = $('itensAniversarios');
  caixa.replaceChildren();
  for (const p of lista) {
    const linha = el('div', 'linha-registro');
    const data = String(p.dia).padStart(2, '0') + ' ' + MESES[p.mes - 1] + (p.ano ? ' ' + p.ano : '');
    linha.append(el('span', '', p.nome + ' · ' + data), botao('Tirar', async () => {
      try { desenharAniversarios((await ideias('POST', 'aniversarios', { acao: 'remover', nome: p.nome })).lista); } catch (_) {}
    }));
    caixa.append(linha);
  }
  if (!lista.length) caixa.append(el('p', 'aviso', 'Nenhum aniversário.'));
}

$('formAniversario').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeAniversario').value.trim();
  const d = $('dataAniversario').value;   // "AAAA-MM-DD"; o ano é opcional (caixa "sei o ano")
  if (!nome || !d) return;
  const [ano, mes, dia] = d.split('-').map(Number);
  const corpo = { acao: 'adicionar', nome, dia, mes };
  if ($('anoAniversario').checked) corpo.ano = ano;
  try {
    desenharAniversarios((await ideias('POST', 'aniversarios', corpo)).lista);
    $('nomeAniversario').value = '';
  } catch (err) { mostrar($('statusAniversarios'), textoErro(err)); }
});

// ---------- cartões próprios da tela inicial (HomeCards.kt, 18/11/2026) ----------

const DIAS_CURTOS = ['seg', 'ter', 'qua', 'qui', 'sex', 'sáb', 'dom'];   // bit 0 = segunda (HomeCards.bit)
const hhmmCartao = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');

(function montarDiasLembrete() {
  const caixa = $('diasLembreteCasa');
  DIAS_CURTOS.forEach((d, i) => {
    const r = el('label');
    const c = el('input'); c.type = 'checkbox'; c.value = String(1 << i);
    r.append(c, ' ', el('span', '', d));
    caixa.append(r, ' ');
  });
})();

/** Uma linha com as partes em pedaços separados (cada pedaço traduz sozinho: "seg", "o dia todo", "fora do ar"). */
function linhaCartao(partes, aoTirar) {
  const linha = el('div', 'linha-registro');
  const t = el('span');
  partes.forEach((p, i) => { if (i) t.append(' · '); t.append(el('span', '', p)); });
  linha.append(t, botao('Tirar', aoTirar));
  return linha;
}

async function mandarCartao(corpo) {
  try { desenharCartoesHome((await ideias('POST', 'cartoes', corpo)).cartoes); return true; }
  catch (err) { mostrar($('statusCartoesHome'), textoErro(err)); return false; }
}

function desenharCartoesHome(c) {
  $('cartoesSemPlano').hidden = !!c.permitido;
  const cont = $('itensContagem'); cont.replaceChildren();
  for (const x of c.contagens) cont.append(linhaCartao([x.nome, x.data.split('-').reverse().join('/')], () => mandarCartao({ tipo: 'contagem', acao: 'remover', nome: x.nome })));
  if (!c.contagens.length) cont.append(el('p', 'aviso', 'Nenhuma contagem.'));

  const lem = $('itensLembreteCasa'); lem.replaceChildren();
  for (const x of c.lembretes) {
    const dias = DIAS_CURTOS.filter((_, i) => x.dias & (1 << i));
    lem.append(linhaCartao([x.texto, ...dias, x.minuto !== undefined ? hhmmCartao(x.minuto) : 'o dia todo'],
      () => mandarCartao({ tipo: 'lembrete', acao: 'remover', texto: x.texto })));
  }
  if (!c.lembretes.length) lem.append(el('p', 'aviso', 'Nenhum lembrete da casa.'));

  const srv = $('itensServico'); srv.replaceChildren();
  for (const x of c.servicos) srv.append(linhaCartao([x.nome, x.url, x.fora ? 'fora do ar' : 'no ar'], () => mandarCartao({ tipo: 'servico', acao: 'remover', nome: x.nome })));
  if (!c.servicos.length) srv.append(el('p', 'aviso', 'Nenhum serviço.'));

  const moe = $('moedasCotacao'); moe.replaceChildren();
  const valor = Object.fromEntries((c.cotacoes || []).map((q) => [q.codigo, q]));
  for (const m of c.ativos.filter((a) => a !== c.moedaCasa)) {
    const r = el('label');
    const cb = el('input'); cb.type = 'checkbox'; cb.value = m; cb.checked = c.moedas.includes(m);
    const q = valor[m];
    r.append(cb, ' ', el('span', '', m + (q ? ' = ' + q.valor.toLocaleString(undefined, { maximumFractionDigits: q.valor < 100 ? 2 : 0 }) + ' ' + c.moedaCasa : '')));
    moe.append(r, ' ');
  }
}

$('formContagem').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeContagem').value.trim(), data = $('dataContagem').value;
  if (!nome || !data) return;
  if (await mandarCartao({ tipo: 'contagem', nome, data })) $('nomeContagem').value = '';
});

$('formLembreteCasa').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const texto = $('textoLembreteCasa').value.trim();
  const dias = [...$('diasLembreteCasa').querySelectorAll('input:checked')].reduce((s, c) => s | Number(c.value), 0);
  if (!texto || !dias) return;
  const h = $('horaLembreteCasa').value;
  const corpo = { tipo: 'lembrete', texto, dias };
  if (h) { const [hh, mm] = h.split(':').map(Number); corpo.minuto = hh * 60 + mm; }
  if (await mandarCartao(corpo)) $('textoLembreteCasa').value = '';
});

$('formServico').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeServico').value.trim(), url = $('urlServico').value.trim();
  if (!nome || !url) return;
  if (await mandarCartao({ tipo: 'servico', nome, url })) { $('nomeServico').value = ''; $('urlServico').value = ''; }
});

$('btnMoedas').addEventListener('click', async () => {
  const moedas = [...$('moedasCotacao').querySelectorAll('input:checked')].map((c) => c.value).join(',');
  if (await mandarCartao({ tipo: 'moedas', moedas })) mostrar($('statusCartoesHome'), 'Moedas salvas.');
});

// ---------- presença ----------

let presencaEscolhidos = new Set();

$('btnPresencaVer').addEventListener('click', async () => {
  const st = $('statusPresenca');
  mostrar(st, 'Perguntando ao roteador…');
  try {
    const r = await ideias('GET', 'presenca');
    mostrar(st, r.erro ? 'Roteador: ' + r.erro : '');
    presencaEscolhidos = new Set(r.escolhidos);
    const lista = $('listaPresenca');
    lista.replaceChildren();
    for (const a of (r.aparelhos || [])) {
      const rotulo = el('label');
      const caixa = el('input'); caixa.type = 'checkbox'; caixa.checked = a.conta; caixa.value = a.mac;
      caixa.addEventListener('change', () => { if (caixa.checked) presencaEscolhidos.add(a.mac); else presencaEscolhidos.delete(a.mac); });
      rotulo.append(caixa, ' ' + (a.nome || 'sem nome') + ' (' + a.mac + ')');
      lista.append(rotulo);
    }
  } catch (err) { mostrar(st, textoErro(err)); }
});

$('btnPresencaSalvar').addEventListener('click', async () => {
  try {
    const r = await ideias('POST', 'presenca', { macs: [...presencaEscolhidos], entidades: $('presencaHa').value, ips: $('presencaIps').value });
    $('presencaHa').value = (r.entidades || []).join(', '); $('presencaIps').value = (r.ips || []).join(', ');
    const n = r.escolhidos.length + (r.entidades || []).length + (r.ips || []).length;
    mostrar($('statusPresenca'), n + ' aparelho(s) ou pessoa(s) contam como "alguém em casa".');
  } catch (err) { mostrar($('statusPresenca'), textoErro(err)); }
});

// ---------- semana e desempenho ----------

function desenharSemana(r, d) {
  $('textoSemana').textContent = r && r.linhas ? 'Última semana: ' + r.linhas.filter(Boolean).join(' · ') : 'A semana ainda não foi montada (domingo, 20h).';
  $('textoDesempenho').textContent = d && d.situacao ? 'Desenho da HOME nesta versão: ' + d.situacao : '';
}

$('btnSemanaMontar').addEventListener('click', async () => {
  try { await ideias('POST', 'retrospectiva/montar'); carregarIdeias(); } catch (err) { $('textoSemana').textContent = textoErro(err); }
});

// ---------- modo simples ----------

function desenharSimples(s) {
  const marcados = new Set(s.apps);
  const lista = $('appsSimples');
  lista.replaceChildren();
  for (const a of s.instalados) {
    const rotulo = el('label');
    const caixa = el('input'); caixa.type = 'checkbox'; caixa.checked = marcados.has(a.pacote); caixa.value = a.pacote;
    rotulo.append(caixa, ' ' + a.nome);
    lista.append(rotulo);
  }
}

$('btnSimplesSalvar').addEventListener('click', async () => {
  const apps = [...$('appsSimples').querySelectorAll('input:checked')].map((c) => c.value);
  if (apps.length > 6) { mostrar($('statusSimples'), 'No máximo 6 apps.'); return; }
  try {
    const r = await ideias('POST', 'simples', { apps });
    mostrar($('statusSimples'), r.apps.length ? r.apps.length + ' app(s) salvos.' : 'Sem apps marcados: vão os mais usados.');
  } catch (err) { mostrar($('statusSimples'), textoErro(err)); }
});

// ---------- visual de outra TV (tema por QR, degrau 1) ----------
// POST/GET /api/extras/ideias/visual (PainelIdeias.kt). Aceita o código puro ou o link inteiro do QR (VisualCodigo.decodificar).

$('btnVisualAplicar').addEventListener('click', async () => {
  const codigo = $('codigoVisual').value.trim();
  if (!codigo) { mostrar($('statusVisual'), 'Cole o código ou o link primeiro.'); return; }
  try {
    await ideias('POST', 'visual', { codigo });
    mostrar($('statusVisual'), 'Visual aplicado. Volte à tela inicial da TV para ver.');
    $('codigoVisual').value = '';
  } catch (err) { mostrar($('statusVisual'), textoErro(err)); }
});

$('btnVisualCopiar').addEventListener('click', async () => {
  try {
    const r = await ideias('GET', 'visual');
    try { await navigator.clipboard.writeText(r.link); mostrar($('statusVisual'), 'Link do visual desta TV copiado.'); }
    catch (_) { $('codigoVisual').value = r.link; mostrar($('statusVisual'), 'Copie o link do campo acima.'); }
  } catch (err) { mostrar($('statusVisual'), textoErro(err)); }
});

// ---------- ajuste fino pelo celular (21/11/2026) ----------
// Lê o visual desta TV (o mesmo código do QR, VisualCodigo.kt), mostra letra e ajuste fino e, a cada troca, manda de
// volta só essas duas partes pela rota "visual". A TV refaz a tela inicial sozinha (MainActivity.ouvinteAjuste).
const FINO_PADRAO = { hf: 0, hc: 0, ac: 0, co: 1, gl: 0, sz: 1, bt: 0, bc: 0, bb: 0, bf: 1 };

function lerCodigoVisual(codigo) {
  const b64 = (codigo || '').replace(/^.*?visual:1:/, '').replace(/-/g, '+').replace(/_/g, '/');
  try { return JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(b64 + '==='.slice((b64.length + 3) % 4)), c => c.charCodeAt(0)))); }
  catch (_) { return null; }
}

function codigoVisual(j) {
  const bytes = new TextEncoder().encode(JSON.stringify(j));
  let bin = ''; bytes.forEach(b => { bin += String.fromCharCode(b); });
  return 'sofaly-visual:1:' + btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function carregarFino() {
  if (!$('cartaoFino')) return;
  try {
    const r = await ideias('GET', 'visual');
    const j = lerCodigoVisual(r.codigo) || {};
    if (j.f) $('finoLetra').value = j.f;
    const a = j.a || {};
    document.querySelectorAll('[data-fino]').forEach(s => { s.value = String(a[s.dataset.fino] ?? FINO_PADRAO[s.dataset.fino]); });
  } catch (err) { mostrar($('statusFino'), textoErro(err)); }
}

async function mandarFino() {
  const a = {};
  document.querySelectorAll('[data-fino]').forEach(s => { a[s.dataset.fino] = Number(s.value); });
  try {
    await ideias('POST', 'visual', { codigo: codigoVisual({ f: $('finoLetra').value, a }) });
    mostrar($('statusFino'), 'Ajuste aplicado na TV.');
  } catch (err) { mostrar($('statusFino'), textoErro(err)); }
}

if ($('cartaoFino')) {
  $('cartaoFino').addEventListener('change', (ev) => { if (ev.target.matches('select')) mandarFino(); });
  document.addEventListener('aba', (ev) => { if (ev.detail === 'telainicial') carregarFino(); });
}
