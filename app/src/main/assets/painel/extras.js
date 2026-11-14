// Aba "Extras" do painel (ideias de 10/11/2026; rotas em PainelExtras.kt). Arquivo separado do app.js para não
// conflitar com os PRs abertos. Usa as funções globais do app.js: $, chamar, mostrar, confirmar, PrecisaPin.
// Todo texto vindo da TV entra por textContent (nunca innerHTML).
'use strict';

const NOMES_CHAVES = {
  JANELA_OTA: 'Atualização do sistema só de madrugada (3h às 5h)',
  TRAVAR_CRITICOS: 'Travar "Forçar parada" e "Limpar dados" do launcher e da VPN',
  PIN_HOME: 'Pedir o PIN para mudar a organização da HOME',
  FILTRO_NOTURNO: 'Filtro noturno (tela mais quente no horário abaixo)',
  COR_DO_FUNDO: 'Cor de destaque tirada do fundo (tema Galeria)',
  DIARIO_DNS: 'Diário de DNS por app (fica só na TV, 7 dias)',
  DEBLOAT_POS_OTA: 'Refazer a limpeza de apps depois de atualizar o sistema',
  HORA_DORMIR: 'Hora de dormir (só apps liberados e volume com teto)',
  TOCANDO_AGORA: 'Tocando agora (lê o que está tocando na TV)',
  MODO_LEVE: 'Modo leve (animações do sistema mais curtas; desligar volta como era)',
  SOM_NAVEGACAO: 'Som de navegação nas telas do launcher',
  RECADO_LINK: 'Recado por link (Home Assistant, TvOverlay)',
  VER_TELA: 'Ver a tela pelo Claude (MCP ver_tela; a TV avisa cada vez)',
  SONO_SUAVE: 'Desligar suave (o timer escurece a tela e baixa o volume nos últimos 5 min)',
  MURAL: 'Mural da família no protetor de tela',
  PRESENCA: 'Presença: apagar a TV quando todos saírem (roteador UniFi)',
  HOJE_NA_TV: 'Hoje na TV (sugestões na tela Buscar)',
  RETROSPECTIVA: 'Sua semana na TV (domingo, 20h)',
  MODO_SIMPLES: 'Modo simples (cards grandes no lugar da HOME)',
  TEMPO_TELA: 'Tempo de tela por dia (acabou, só os apps liberados abrem)',
  AGENDA_TODAS: 'Agenda de todas as contas Google da TV juntas',
  LER_FOCO: 'Ler em voz alta o que está em foco nas telas do launcher',
};

const hhmm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
const minutos = (t) => { const [h, m] = (t || '0:0').split(':').map(Number); return h * 60 + m; };

/** Ação que pode pedir o PIN: o quadro do app.js pede o PIN e tenta de novo. */
const comPin = (titulo, texto, ok, fazer) => confirmar(titulo, texto, ok, fazer);

// ---------- aba ----------

// O mudarAba do app.js avisa com o evento "aba": aqui carrega a nossa, e para o vídeo ao sair dela.
document.addEventListener('aba', (ev) => {
  if (ev.detail === 'extras') carregarExtras(); else pararVideo();
});

async function carregarExtras() {
  await Promise.all([carregarChaves(), carregarCenas(), carregarLoja()]).catch((e) => mostrar($('statusExtras'), textoErro(e)));
}

// ---------- chaves ----------

async function carregarChaves(dados) {
  const e = dados || await chamar('GET', '/api/extras/estado');
  const lista = $('chavesExtras');
  lista.replaceChildren();
  for (const [chave, ligado] of Object.entries(e.chaves)) {
    const rotulo = document.createElement('label');
    const caixa = document.createElement('input');
    caixa.type = 'checkbox';
    caixa.checked = ligado;
    caixa.addEventListener('change', async () => {
      const mudar = () => chamar('POST', '/api/extras/chave', { chave, ligado: caixa.checked });
      try {
        const r = e.comPin.includes(chave)
          ? await comPin('Mudar chave', NOMES_CHAVES[chave] || chave, caixa.checked ? 'Ligar' : 'Desligar', mudar)
          : await mudar();
        if (!r) { caixa.checked = !caixa.checked; return; }   // cancelou
        carregarChaves(r);
      } catch (err) { caixa.checked = !caixa.checked; mostrar($('statusExtras'), textoErro(err)); }
    });
    rotulo.append(caixa, ' ' + (NOMES_CHAVES[chave] || chave) + (e.comPin.includes(chave) ? ' (PIN)' : ''));
    lista.append(rotulo);
  }
  $('filtroInicio').value = hhmm(e.filtro.inicio);
  $('filtroFim').value = hhmm(e.filtro.fim);
  $('filtroForca').value = e.filtro.forca;
  $('macWol').value = e.mac || '';
  mostrar($('otaExtras'), e.otaPendente ? 'Há uma atualização do sistema esperando a janela das 3h.' : '');
  if (e.diarioErro) mostrar($('statusExtras'), 'Diário de DNS: ' + e.diarioErro);
  // 2ª leva (10/11/2026)
  $('dormirInicio').value = hhmm(e.dormir.inicio);
  $('dormirFim').value = hhmm(e.dormir.fim);
  $('dormirTeto').value = e.dormir.teto;
  $('dormirApps').value = e.dormir.apps;
  desenharDias(e.dormir.dias || []);
  mostrar($('livreExtras'), 'Espaço livre na TV: ' + e.livre);
  if (e.chaves.TOCANDO_AGORA) verTocando();
}

$('btnFiltroExtras').addEventListener('click', async () => {
  try {
    await carregarChaves(await chamar('POST', '/api/extras/filtro', {
      inicio: minutos($('filtroInicio').value), fim: minutos($('filtroFim').value), forca: Number($('filtroForca').value),
    }));
    mostrar($('statusExtras'), 'Filtro salvo.');
  } catch (e) { mostrar($('statusExtras'), textoErro(e)); }
});

// ---------- tela ao vivo (H.264 cru em quadros [4 bytes de tamanho][NAL], ver VideoAoVivo.kt) ----------

let video = null;   // { leitor, jmuxer, alvo }

// Onde o vídeo aparece: na aba Extras ou na aba Controle (no lugar da foto). Só um de cada vez (a TV só grava um).
const ALVO_EXTRAS = { node: 'videoExtras', status: 'statusVideoExtras', parar: 'btnPararVideoExtras' };

async function verAoVivo(alvo = ALVO_EXTRAS) {
  pararVideo();
  mostrar($(alvo.status), 'Ligando o vídeo na TV...');
  const jmuxer = new JMuxer({ node: alvo.node, mode: 'video', flushingTime: 0, fps: 30, debug: false });
  let resp;
  try {
    resp = await fetch('/api/extras/video', { credentials: 'same-origin', cache: 'no-store' });
  } catch (e) { jmuxer.destroy(); mostrar($(alvo.status), 'Não consegui falar com a TV.'); if (alvo.fim) alvo.fim(); return; }
  if (!resp.ok) { jmuxer.destroy(); mostrar($(alvo.status), `A TV recusou (${resp.status}).`); if (alvo.fim) alvo.fim(); return; }
  const leitor = resp.body.getReader();
  video = { leitor, jmuxer, alvo };
  $(alvo.parar).hidden = false;
  mostrar($(alvo.status), '');
  let pendente = new Uint8Array(0);
  let chegou = false;
  try {
    for (;;) {
      const { value, done } = await leitor.read();
      if (done) break;
      const junto = new Uint8Array(pendente.length + value.length);
      junto.set(pendente); junto.set(value, pendente.length);
      let i = 0;
      while (junto.length - i >= 4) {
        const n = ((junto[i] << 24) >>> 0) + (junto[i + 1] << 16) + (junto[i + 2] << 8) + junto[i + 3];
        if (junto.length - i - 4 < n) break;
        jmuxer.feed({ video: junto.subarray(i + 4, i + 4 + n) });
        chegou = true;
        i += 4 + n;
      }
      pendente = junto.slice(i);
    }
  } catch (e) { /* conexão fechada: fim */ }
  mostrar($(alvo.status), chegou ? 'O vídeo terminou (2 min). Clique em "Ver ao vivo" para continuar.'
    : 'Não chegou vídeo: o shell próprio da TV pode estar desligado, ou outra pessoa já está vendo.');
  $(alvo.parar).hidden = true;
  if (alvo.fim) alvo.fim();
}

function pararVideo() {
  if (!video) return;
  const { alvo } = video;
  try { video.leitor.cancel(); } catch (e) {}
  try { video.jmuxer.destroy(); } catch (e) {}
  video = null;
  $(alvo.parar).hidden = true;
}

$('btnVideoExtras').addEventListener('click', () => verAoVivo());
$('btnPararVideoExtras').addEventListener('click', pararVideo);

// ---------- recado com foto (a página reduz a foto antes de mandar) ----------

async function reduzir(arquivo, lado = 960) {
  const bmp = await createImageBitmap(arquivo);   // sem <img>: a CSP do painel não deixa abrir imagem de blob:
  const k = Math.min(1, lado / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  return c.toDataURL('image/jpeg', 0.82).split(',')[1];
}

$('formFotoExtras').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const f = $('fotoExtras').files[0];
  if (!f) { mostrar($('statusFotoExtras'), 'Escolha uma foto.'); return; }
  try {
    mostrar($('statusFotoExtras'), 'Mandando...');
    await chamar('POST', '/api/extras/recado-foto', { imagem: await reduzir(f), texto: $('textoFotoExtras').value });
    mostrar($('statusFotoExtras'), 'Na tela da TV.');
  } catch (e) { mostrar($('statusFotoExtras'), textoErro(e)); }
});

// ---------- cenas ----------
// Cena montada por lista (14/11/2026, ideia 3): cada ação é uma linha com menus que mudam conforme o tipo. O JSON
// continua por baixo (o mesmo {"acoes":[...]} do catálogo ComandosIa) e aparece em "Ver como JSON" para quem quiser.

const MAX_ACOES_CENA = 5;
const SIM_NAO = [['true', 'Ligar'], ['false', 'Desligar']];
// Um campo: {k, op: [[valor, rótulo]...]} = menu; {k, num: [min, max], padrao, sufixo} = número; {k, txt, max, dica} = texto;
// {k: 'pacote', app: true} = apps da TV; {k: 'entidade', casa: true} = aparelhos do Home Assistant; {hora: true} = despertador.
const TIPOS_CENA = [
  { tipo: 'abrir_app', nome: 'Abrir app', campos: [{ k: 'pacote', app: true }] },
  { tipo: 'abrir_tela', nome: 'Abrir tela', campos: [{ k: 'tela', op: [['previsao', 'Previsão do tempo'], ['agenda', 'Agenda'],
    ['despertador', 'Despertador'], ['configuracoes', 'Configurações'], ['inicio', 'Tela inicial']] }] },
  { tipo: 'volume', nome: 'Volume', campos: [{ k: 'acao', op: [['subir', 'Aumentar'], ['descer', 'Diminuir'], ['mudo', 'Mudo']] },
    { k: 'vezes', num: [1, 10], padrao: 3, sufixo: 'passos' }] },
  { tipo: 'midia', nome: 'Mídia', campos: [{ k: 'acao', op: [['pausar', 'Pausar'], ['tocar', 'Continuar'], ['proxima', 'Próximo'], ['anterior', 'Anterior']] }] },
  { tipo: 'modo_visita', nome: 'Modo visita', campos: [{ k: 'ligado', op: SIM_NAO, bool: true }] },
  { tipo: 'tocar_canal', nome: 'Canal da TV', campos: [{ k: 'canal', txt: true, max: 40, dica: 'Nome ou número (ex.: Globo)' }] },
  { tipo: 'casa', nome: 'Aparelho da casa', campos: [{ k: 'entidade', casa: true },
    { k: 'acao', op: [['ligar', 'Ligar'], ['desligar', 'Desligar'], ['alternar', 'Alternar']] }] },
  { tipo: 'recado', nome: 'Recado na tela', campos: [{ k: 'texto', txt: true, max: 200, dica: 'Mensagem' },
    { k: 'segundos', op: [['60', '1 min na tela'], ['30', '30 s na tela'], ['300', '5 min na tela']], numero: true }] },
  { tipo: 'timer', nome: 'Timer', campos: [{ k: 'minutos', num: [1, 240], padrao: 10, sufixo: 'min' }] },
  { tipo: 'desligar_em', nome: 'Desligar a TV em', campos: [{ k: 'minutos', num: [0, 480], padrao: 30, sufixo: 'min (0 cancela)' }] },
  { tipo: 'filtro_noturno', nome: 'Filtro noturno', campos: [{ k: 'ligado', op: SIM_NAO, bool: true }] },
  { tipo: 'mural', nome: 'Mural da família', campos: [{ k: 'lista', op: [['compras', 'Lista de compras'], ['recados', 'Recado fixo']] },
    { k: 'texto', txt: true, max: 60, dica: 'Item (ex.: leite)' }] },
  { tipo: 'tocar_titulo', nome: 'Tocar filme ou série', campos: [{ k: 'titulo', txt: true, max: 80, dica: 'Nome do filme ou da série' }] },
  { tipo: 'continuar', nome: 'Continuar o que estava vendo', campos: [] },
  { tipo: 'proximo_episodio', nome: 'Próximo episódio', campos: [{ k: 'titulo', txt: true, max: 80, dica: 'Série (vazio = a última vista)', opcional: true }] },
  { tipo: 'despertador', nome: 'Despertador', campos: [{ hora: true },
    { k: 'dias', op: [['', 'Uma vez'], ['0,1,2,3,4,5,6', 'Todo dia'], ['1,2,3,4,5', 'Seg a sex'], ['0,6', 'Fim de semana']], dias: true }] },
];
const TIPO_POR_NOME = Object.fromEntries(TIPOS_CENA.map((t) => [t.tipo, t]));

let acoesCena = [];   // [{tipo, ...campos}] no formato do catálogo
let appsCena = null;  // [[pacote, nome]]
let casaCena = null;  // [[id, nome]]

async function listaApps() {
  if (!appsCena) {
    const r = await chamar('GET', '/api/apps');
    appsCena = r.apps.map((a) => [a.pacote, a.nome]).sort((a, b) => a[1].localeCompare(b[1], 'pt-BR'));
  }
  return appsCena;
}
async function listaCasa() {
  if (!casaCena) {
    const r = await chamar('GET', '/api/extras/casa');
    casaCena = (r.aparelhos || []).map((a) => [a.id, a.nome]);
  }
  return casaCena;
}

/** Ação nova com os valores padrão de cada campo (o primeiro do menu, o padrão do número). */
function acaoPadrao(tipo) {
  const a = { tipo };
  for (const c of TIPO_POR_NOME[tipo].campos) {
    if (c.hora) { a.hora = 7; a.minuto = 0; } else if (c.dias) { /* uma vez: sem dias */ } else if (c.op) a[c.k] = valorDe(c, c.op[0][0]);
    else if (c.num) a[c.k] = c.padrao;
  }
  return a;
}
const valorDe = (c, v) => c.bool ? v === 'true' : c.numero ? Number(v) : v;

function menu(rotulo, opcoes, atual, mudou) {
  const s = document.createElement('select'); s.setAttribute('aria-label', rotulo);
  for (const [v, t] of opcoes) { const o = document.createElement('option'); o.value = v; o.textContent = t; s.append(o); }
  s.value = atual; s.addEventListener('change', () => mudou(s.value));
  return s;
}

/** Os campos de uma linha. Apps e aparelhos da casa vêm das listas da TV (carregadas na 1ª vez). */
function camposDe(a, i) {
  const caixa = document.createElement('div'); caixa.className = 'cena-campos';
  const t = TIPO_POR_NOME[a.tipo];
  caixa.append(menu('Tipo da ação', TIPOS_CENA.map((x) => [x.tipo, x.nome]), a.tipo, (v) => { acoesCena[i] = acaoPadrao(v); desenharCena(); }));
  for (const c of t.campos) {
    if (c.hora) {
      const h = document.createElement('input'); h.type = 'time'; h.setAttribute('aria-label', 'Hora');
      h.value = String(a.hora).padStart(2, '0') + ':' + String(a.minuto).padStart(2, '0');
      h.addEventListener('change', () => { const m = minutos(h.value); a.hora = Math.floor(m / 60); a.minuto = m % 60; atualizarCena(); });
      caixa.append(h);
    } else if (c.dias) {
      caixa.append(menu('Dias', c.op, (a.dias || []).join(','), (v) => { if (v) a.dias = v.split(',').map(Number); else delete a.dias; atualizarCena(); }));
    } else if (c.op) {
      caixa.append(menu(c.k, c.op, String(a[c.k]), (v) => { a[c.k] = valorDe(c, v); atualizarCena(); }));
    } else if (c.num) {
      const n = document.createElement('input'); n.type = 'number'; n.min = c.num[0]; n.max = c.num[1]; n.value = a[c.k];
      n.setAttribute('aria-label', c.sufixo); n.title = c.sufixo; n.placeholder = c.sufixo;
      n.addEventListener('input', () => { a[c.k] = Math.round(Number(n.value)); atualizarCena(); });
      const rot = document.createElement('label'); rot.className = 'cena-numero';
      const suf = document.createElement('span'); suf.textContent = c.sufixo;
      rot.append(n, suf); caixa.append(rot);
    } else if (c.txt) {
      const x = document.createElement('input'); x.type = 'text'; x.maxLength = c.max; x.placeholder = c.dica; x.value = a[c.k] || '';
      x.setAttribute('aria-label', c.dica);
      x.addEventListener('input', () => { if (x.value.trim() || !c.opcional) a[c.k] = x.value; else delete a[c.k]; atualizarCena(); });
      caixa.append(x);
    } else if (c.app || c.casa) {
      const s = menu(c.app ? 'App' : 'Aparelho', [[a[c.k] || '', 'Carregando…']], a[c.k] || '', (v) => { a[c.k] = v; atualizarCena(); });
      caixa.append(s);
      (c.app ? listaApps() : listaCasa()).then((l) => {
        if (!l.length) { s.options[0].textContent = c.casa ? 'Casa não configurada' : 'Nenhum app'; return; }
        s.replaceChildren(...l.map(([v, t]) => { const o = document.createElement('option'); o.value = v; o.textContent = t; return o; }));
        if (!a[c.k] || !l.some(([v]) => v === a[c.k])) a[c.k] = l[0][0];
        s.value = a[c.k]; atualizarCena();
      }).catch((e) => { s.options[0].textContent = 'Não consegui listar'; mostrar($('statusCena'), textoErro(e)); });
    }
  }
  return caixa;
}

function botaoPequeno(texto, rotulo, classe, fazer) {
  const b = document.createElement('button'); b.type = 'button'; b.className = classe + ' botao-pequeno';
  b.textContent = texto; b.setAttribute('aria-label', rotulo); b.addEventListener('click', fazer);
  return b;
}

function desenharCena() {
  const lista = $('acoesLista');
  lista.replaceChildren(...acoesCena.map((a, i) => {
    const linha = document.createElement('div'); linha.className = 'cena-acao';
    const num = document.createElement('span'); num.className = 'cena-num'; num.textContent = String(i + 1);
    const mover = document.createElement('div'); mover.className = 'cena-mover';
    const trocar = (j) => { [acoesCena[i], acoesCena[j]] = [acoesCena[j], acoesCena[i]]; desenharCena(); };
    const subir = botaoPequeno('↑', 'Subir', 'botao-secundario', () => trocar(i - 1)); subir.disabled = i === 0;
    const descer = botaoPequeno('↓', 'Descer', 'botao-secundario', () => trocar(i + 1)); descer.disabled = i === acoesCena.length - 1;
    mover.append(subir, descer, botaoPequeno('✕', 'Tirar', 'botao-perigo', () => { acoesCena.splice(i, 1); desenharCena(); }));
    linha.append(num, camposDe(a, i), mover);
    return linha;
  }));
  atualizarCena();
}

/** Resumo legível de uma ação ("Volume: Aumentar, 3 passos"). */
function resumoAcao(a) {
  const t = TIPO_POR_NOME[a.tipo];
  if (!t) return a.tipo;
  const partes = t.campos.map((c) => {
    if (c.hora) return String(a.hora).padStart(2, '0') + ':' + String(a.minuto).padStart(2, '0');
    if (c.dias) return (c.op.find(([v]) => v === (a.dias || []).join(',')) || ['', ''])[1];
    if (c.app) return (appsCena || []).find(([v]) => v === a[c.k])?.[1] || a[c.k];
    if (c.casa) return (casaCena || []).find(([v]) => v === a[c.k])?.[1] || a[c.k];
    if (c.op) return (c.op.find(([v]) => v === String(a[c.k])) || ['', a[c.k]])[1];
    if (c.num) return a[c.k] + ' ' + c.sufixo.split(' ')[0];
    return a[c.k] ? `"${a[c.k]}"` : '';
  }).filter(Boolean);
  return t.nome + (partes.length ? ': ' + partes.join(', ') : '');
}

function atualizarCena() {
  $('acoesCena').value = acoesCena.length ? JSON.stringify({ acoes: acoesCena }, null, 1) : '';
  $('btnAddAcao').disabled = acoesCena.length >= MAX_ACOES_CENA;
  $('contaAcoes').textContent = acoesCena.length >= MAX_ACOES_CENA ? 'máximo de 5 ações' : `até ${MAX_ACOES_CENA} ações`;
  $('resumoCena').hidden = !acoesCena.length;
  $('resumoCenaTexto').textContent = acoesCena.map(resumoAcao).join(' · ');
}

/** Quem edita o JSON à mão: se for válido, a lista segue o JSON (tipos que a lista não conhece ficam só no JSON). */
$('acoesCena').addEventListener('change', () => {
  try {
    const o = JSON.parse($('acoesCena').value || '{"acoes":[]}');
    const l = Array.isArray(o) ? o : o.acoes;
    if (!Array.isArray(l) || l.some((a) => !a || !TIPO_POR_NOME[a.tipo])) throw new Error();
    acoesCena = l.slice(0, MAX_ACOES_CENA); desenharCena(); mostrar($('statusCena'), '');
  } catch (e) { mostrar($('statusCena'), 'JSON inválido ou com tipo que a lista não conhece: {"acoes":[...]}.'); }
});

$('btnAddAcao').addEventListener('click', () => {
  if (acoesCena.length >= MAX_ACOES_CENA) return;
  acoesCena.push(acaoPadrao('abrir_app'));
  desenharCena();
});

// Eventos como gatilho (ideia 7): evento → cena. Os nomes batem com Gatilhos.Evento no Kotlin.
const EVENTOS_CENA = [
  ['midia_tocou', 'Filme ou vídeo começou'], ['midia_pausou', 'Filme ou vídeo pausou ou acabou'],
  ['tv_ligou', 'A TV ligou'], ['todos_sairam', 'Todos saíram (presença)'], ['chuva', 'Chuva chegando'],
];

function desenharGatilhos(gatilhos, cenas) {
  const lista = $('gatilhosLista');
  lista.replaceChildren();
  for (const [chave, rotulo] of EVENTOS_CENA) {
    const linha = document.createElement('label'); linha.className = 'cena-linha';
    const texto = document.createElement('span'); texto.textContent = rotulo;
    const sel = document.createElement('select'); sel.dataset.evento = chave; sel.setAttribute('aria-label', rotulo);
    for (const nome of ['', ...cenas.map(c => c.nome)]) {
      const o = document.createElement('option'); o.value = nome; o.textContent = nome || 'nada'; sel.append(o);
    }
    sel.value = gatilhos[chave] || '';
    linha.append(texto, sel);
    lista.append(linha);
  }
}

$('btnGatilhos').addEventListener('click', async () => {
  const corpo = {};
  for (const sel of $('gatilhosLista').querySelectorAll('select')) if (sel.value) corpo[sel.dataset.evento] = sel.value;
  try {
    if (await comPin('Eventos', 'Salvar os eventos que rodam cenas?', 'Salvar', () => chamar('POST', '/api/extras/cenas/gatilhos', corpo)))
      mostrar($('statusGatilhos'), 'Salvo.');
  } catch (e) { mostrar($('statusGatilhos'), textoErro(e)); }
});

async function carregarCenas() {
  const r = await chamar('GET', '/api/extras/cenas');
  desenharGatilhos(r.gatilhos || {}, r.cenas);
  const lista = $('listaCenas');
  lista.replaceChildren();
  if (!r.cenas.length) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Nenhuma cena ainda.'; lista.append(p); }
  for (const c of r.cenas) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const nome = document.createElement('strong'); nome.textContent = c.nome;
    const editar = document.createElement('button'); editar.type = 'button'; editar.className = 'botao-secundario'; editar.textContent = 'Editar';
    editar.addEventListener('click', () => {
      try { const o = JSON.parse(c.acoes); acoesCena = (Array.isArray(o) ? o : o.acoes || []).slice(0, MAX_ACOES_CENA); } catch (e) { acoesCena = []; }
      $('nomeCena').value = c.nome; desenharCena();
      mostrar($('statusCena'), 'Salvar com o mesmo nome substitui a cena e gera um link novo (o antigo para de funcionar).');
      $('nomeCena').scrollIntoView({ block: 'center' });
    });
    const apagar = document.createElement('button'); apagar.type = 'button'; apagar.className = 'botao-secundario'; apagar.textContent = 'Apagar';
    apagar.addEventListener('click', async () => {
      try {
        if (await comPin('Apagar cena', `Apagar "${c.nome}"? O link dela para de funcionar.`, 'Apagar',
          () => chamar('POST', '/api/extras/cenas/apagar', { nome: c.nome }))) carregarCenas();
      } catch (e) { mostrar($('statusCena'), textoErro(e)); }
    });
    const botoes = document.createElement('div'); botoes.className = 'cena-linha'; botoes.append(editar, apagar);
    linha.append(nome, botoes);
    lista.append(linha);
  }
}

$('formCena').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeCena').value.trim();
  if (!nome) { mostrar($('statusCena'), 'Dê um nome à cena.'); return; }
  if (!acoesCena.length) { mostrar($('statusCena'), 'Adicione pelo menos uma ação.'); return; }
  try {
    const r = await comPin('Criar cena', `Criar a cena "${nome}"?`, 'Criar', () => chamar('POST', '/api/extras/cenas/criar', { nome, acoes: { acoes: acoesCena } }));
    if (!r) return;
    mostrar($('statusCena'), `Link (guarde agora, não aparece de novo): POST ${location.origin}/cena/${r.token}`);
    acoesCena = []; $('nomeCena').value = ''; desenharCena();
    carregarCenas();
  } catch (e) { mostrar($('statusCena'), textoErro(e)); }
});

// Roda as ações na TV sem salvar (o mesmo caminho e as mesmas Confirmações de uma cena de verdade).
$('btnTestarCena').addEventListener('click', async () => {
  if (!acoesCena.length) { mostrar($('statusCena'), 'Adicione pelo menos uma ação.'); return; }
  try {
    const r = await chamar('POST', '/api/extras/cenas/testar', { acoes: { acoes: acoesCena } });
    mostrar($('statusCena'), r.recusadas && r.recusadas.length ? 'Recusado: ' + r.recusadas.join('; ') : 'Enviado para a TV.');
  } catch (e) { mostrar($('statusCena'), textoErro(e)); }
});

desenharCena();

// ---------- loja curada ----------

async function carregarLoja() {
  const r = await chamar('GET', '/api/extras/loja');
  const lista = $('listaLoja');
  lista.replaceChildren();
  for (const a of r.apps) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const nome = document.createElement('strong'); nome.textContent = a.nome + (a.instalado ? ' (instalado)' : '');
    const desc = document.createElement('span'); desc.className = 'quando'; desc.textContent = `${a.descricao} · github.com/${a.repo}`;
    const botao = document.createElement('button'); botao.type = 'button'; botao.className = 'botao-secundario';
    botao.textContent = a.instalado ? 'Atualizar' : 'Instalar';
    botao.addEventListener('click', async () => {
      try {
        const res = await comPin(botao.textContent, `Baixar ${a.nome} do GitHub e instalar na TV? Pode levar alguns minutos.`, botao.textContent,
          () => chamar('POST', '/api/extras/loja/instalar', { id: a.id }));
        if (res) { mostrar($('statusLoja'), res.resultado); carregarLoja(); }
      } catch (e) { mostrar($('statusLoja'), textoErro(e)); }
    });
    linha.append(nome, desc, botao);
    if (a.extra) {   // ideia 69: repositório somado pela pessoa pode sair da lista
      const tirar = document.createElement('button'); tirar.type = 'button'; tirar.className = 'botao-secundario';
      tirar.textContent = 'Tirar';
      tirar.addEventListener('click', () => mudarRepoLoja(a.repo, true, tirar));
      linha.append(tirar);
    }
    lista.append(linha);
  }
}

async function mudarRepoLoja(repo, remover, botao) {
  try {
    const res = await comPin(remover ? 'Tirar' : 'Somar', remover ? `Tirar ${repo} da loja?` : `Somar ${repo} à loja? A TV vai aceitar instalar o que esse projeto publicar.`,
      remover ? 'Tirar' : 'Somar', () => chamar('POST', '/api/extras/loja/repo', { repo, remover }));
    if (res) { $('repoLoja').value = ''; carregarLoja(); }
  } catch (e) { mostrar($('statusLoja'), textoErro(e)); }
  if (botao) botao.disabled = false;
}

$('formRepoLoja').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const repo = $('repoLoja').value.trim();
  if (repo) mudarRepoLoja(repo, false);
});

// ---------- acordar o computador ----------

$('formWol').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try { await chamar('POST', '/api/extras/wol', { mac: $('macWol').value }); mostrar($('statusWol'), 'MAC guardado na TV.'); }
  catch (e) { mostrar($('statusWol'), textoErro(e)); }
});
$('btnAcordar').addEventListener('click', async () => {
  try { await chamar('POST', '/api/extras/acordar'); mostrar($('statusWol'), 'Pacote enviado. O PC precisa estar no cabo e com Wake-on-LAN ligado.'); }
  catch (e) { mostrar($('statusWol'), textoErro(e)); }
});

// ---------- testador de teclas e diário de DNS ----------

$('btnTeclas').addEventListener('click', async () => {
  try {
    await chamar('POST', '/api/extras/teclas/armar');
    const saida = $('saidaExtras');
    mostrar(saida, 'Armado. Aperte os botões do controle...');
    const fim = Date.now() + 120000;
    const ler = async () => {
      const r = await chamar('GET', '/api/extras/teclas');
      saida.textContent = r.teclas.length ? r.teclas.map((t) => `${t.nome} (${t.codigo}) ${t.acao}${t.remapeada ? ' remapeada' : ''} · ${t.aparelho}`).join('\n')
        : 'Armado. Aperte os botões do controle...';
      if (Date.now() < fim && !$('abaExtras').hidden) setTimeout(ler, 1000);
    };
    ler();
  } catch (e) { mostrar($('saidaExtras'), textoErro(e)); }
});

$('btnDiario').addEventListener('click', async () => {
  try {
    const r = await comPin('Diário de DNS', 'Ver os nomes que cada app consultou hoje?', 'Ver', () => chamar('GET', '/api/extras/diario'));
    if (!r) return;
    mostrar($('saidaExtras'), r.linhas.length ? r.linhas.map((l) => `${String(l.vezes).padStart(5)}  ${l.nome}  (${l.app})`).join('\n')
      : (r.erro || 'Nada registrado hoje. A chave do diário está ligada?'));
  } catch (e) { mostrar($('saidaExtras'), textoErro(e)); }
});

$('btnApagarDiario').addEventListener('click', async () => {
  try {
    if (await comPin('Apagar diário', 'Apagar todo o diário de DNS guardado na TV?', 'Apagar', () => chamar('POST', '/api/extras/diario/apagar')))
      mostrar($('saidaExtras'), 'Diário apagado.');
  } catch (e) { mostrar($('saidaExtras'), textoErro(e)); }
});

// ---------- 2ª leva (10/11/2026): hora de dormir, tocando agora, recado por link, caches, consumo ----------

const DIAS = ['Domingo', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado'];

/** Uma linha por dia, com início e fim; os dois vazios = segue o padrão. */
function desenharDias(dias) {
  const lista = $('dormirDias');
  lista.replaceChildren();
  DIAS.forEach((nome, d) => {
    const linha = document.createElement('p');
    linha.className = 'aviso';
    const ini = document.createElement('input'); ini.type = 'time'; ini.dataset.dia = d; ini.dataset.campo = 'inicio';
    const fim = document.createElement('input'); fim.type = 'time'; fim.dataset.dia = d; fim.dataset.campo = 'fim';
    ini.setAttribute('aria-label', nome + ': início'); fim.setAttribute('aria-label', nome + ': fim');
    if (dias[d]) { ini.value = hhmm(dias[d].inicio); fim.value = hhmm(dias[d].fim); }
    linha.append(nome.padEnd(8, ' ') + ' ', ini, ' até ', fim);
    lista.append(linha);
  });
}

/** Os 7 dias para mandar: os dois horários preenchidos = horário próprio; senão null (segue o padrão). */
function lerDias() {
  return DIAS.map((_, d) => {
    const i = document.querySelector(`#dormirDias input[data-dia="${d}"][data-campo="inicio"]`).value;
    const f = document.querySelector(`#dormirDias input[data-dia="${d}"][data-campo="fim"]`).value;
    return i && f ? { inicio: minutos(i), fim: minutos(f) } : null;
  });
}

$('btnDormir').addEventListener('click', async () => {
  const corpo = { inicio: minutos($('dormirInicio').value), fim: minutos($('dormirFim').value),
    teto: Number($('dormirTeto').value), apps: $('dormirApps').value, dias: lerDias() };
  try {
    const r = await comPin('Hora de dormir', 'Salvar o horário, o teto do volume e os apps liberados', 'Salvar',
      () => chamar('POST', '/api/extras/dormir', corpo));
    if (r) { carregarChaves(r); mostrar($('statusDormir'), 'Salvo.'); }
  } catch (e) { mostrar($('statusDormir'), textoErro(e)); }
});

async function verTocando() {
  try {
    const t = await chamar('GET', '/api/extras/tocando');
    $('tocandoExtras').textContent = !t.ligado ? 'Ligue a chave "Tocando agora" para ver o que está tocando.'
      : t.nada ? (t.acesso ? 'Nada tocando.' : 'A TV ainda não deu acesso às sessões de mídia (o shell do launcher precisa estar de pé).')
      : `${t.tocando ? '▶' : '⏸'} ${t.titulo || '(sem título)'}${t.artista ? ' — ' + t.artista : ''} · ${t.app}`;
  } catch (e) { $('tocandoExtras').textContent = textoErro(e); }
}
$('btnTocando').addEventListener('click', verTocando);
for (const b of document.querySelectorAll('[data-tocando]')) {
  b.addEventListener('click', async () => {
    try { await chamar('POST', '/api/extras/tocando', { acao: b.dataset.tocando }); setTimeout(verTocando, 800); }
    catch (e) { $('tocandoExtras').textContent = textoErro(e); }
  });
}

$('btnRecadoLink').addEventListener('click', async () => {
  try {
    const r = await comPin('Recado por link', 'Gerar um link novo (o anterior para de funcionar)', 'Gerar',
      () => chamar('POST', '/api/extras/recado-link'));
    if (!r) return;
    const url = `${location.origin}/recado/${r.token}`;
    // Exemplo pronto para o Home Assistant (rest_command) e para o curl.
    mostrar($('saidaRecadoLink'), `${url}\n\nHome Assistant (configuration.yaml):\nrest_command:\n  recado_tv:\n    url: "${url}"\n` +
      `    method: POST\n    content_type: "application/json"\n    payload: '{"title":"Casa","message":"{{ texto }}","duration":15}'\n\n` +
      `curl -X POST -H "Content-Type: application/json" -d '{"message":"Jantar pronto!"}' ${url}`);
  } catch (e) { mostrar($('saidaRecadoLink'), textoErro(e)); }
});

// ---------- diário da TV e webhook (ideias 58 e 59) ----------

const NOMES_ORIGEM = { painel: 'painel', claude: 'Claude', adb: 'adb', tv: 'TV' };

$('btnDiario').addEventListener('click', async () => {
  try {
    const d = await chamar('GET', '/api/extras/diario');
    $('campoWebhook').value = d.webhook || '';
    for (const c of $('origensWebhook').querySelectorAll('input')) c.checked = d.origens.includes(c.value);
    if (d.webhook && d.ultimo) mostrar($('statusWebhook'), 'Última chamada do link: ' + d.ultimo);
    const linhas = d.itens.map((i) => {
      const quando = new Date(i.quando).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
      return `${quando}  ${NOMES_ORIGEM[i.origem] || i.origem}  ${i.acao}${i.resultado ? ' → ' + i.resultado : ''}`;
    });
    mostrar($('saidaDiario'), linhas.length ? linhas.join('\n') : 'Nada no diário ainda.');
  } catch (e) { mostrar($('saidaDiario'), textoErro(e)); }
});

$('btnWebhook').addEventListener('click', async () => {
  const url = $('campoWebhook').value.trim();
  const origens = [...$('origensWebhook').querySelectorAll('input:checked')].map((c) => c.value);
  if (url && !origens.length) { mostrar($('statusWebhook'), 'Marque ao menos uma origem.'); return; }
  try {
    const r = await comPin('Link do diário', url ? 'A TV vai chamar ' + url : 'Desligar o link', url ? 'Salvar' : 'Desligar',
      () => chamar('POST', '/api/extras/diario/webhook', { url, origens }));
    if (r) mostrar($('statusWebhook'), url ? 'Salvo.' : 'Desligado.');
  } catch (e) { mostrar($('statusWebhook'), textoErro(e)); }
});

$('btnCaches').addEventListener('click', async () => {
  mostrar($('saidaManutencao'), 'Limpando (pode levar alguns segundos)...');
  try { const r = await chamar('POST', '/api/extras/caches/limpar'); mostrar($('saidaManutencao'), r.resultado); carregarChaves(); }
  catch (e) { mostrar($('saidaManutencao'), textoErro(e)); }
});

$('btnConsumo').addEventListener('click', async () => {
  try {
    const r = await chamar('GET', '/api/extras/consumo?dias=' + $('diasConsumo').value);
    mostrar($('saidaManutencao'), r.erro ? 'Erro: ' + r.erro
      : `Total: ${r.total}\n` + (r.apps.map((a) => `${a.texto.padStart(10)}  ${a.nome}`).join('\n') || 'nada medido ainda'));
  } catch (e) { mostrar($('saidaManutencao'), textoErro(e)); }
});

// ---------- casa (Home Assistant; PainelExtras "casa/*", CasaHA.kt) ----------

async function carregarCasa() {
  const r = await chamar('GET', '/api/extras/casa');
  $('casaEndereco').value = r.endereco || '';
  $('casaSensor').value = r.campainha || '';
  $('casaCamera').value = r.camera || '';
  const lista = $('listaCasa');
  lista.replaceChildren();
  if (r.erroCasa) mostrar($('statusCasa'), r.erroCasa);
  if (!r.configurado) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Ainda não configurado.'; lista.append(p); return; }
  for (const a of (r.aparelhos || [])) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const nome = document.createElement('strong'); nome.textContent = `${a.nome} · ${a.estado}`;
    const d = a.id.split('.')[0];
    const acao = (d === 'scene' || d === 'script') ? 'ligar' : 'alternar';
    const b = document.createElement('button'); b.type = 'button'; b.className = 'botao-secundario';
    b.textContent = acao === 'ligar' ? 'Rodar' : 'Ligar/desligar';
    b.addEventListener('click', async () => {
      try { await chamar('POST', '/api/extras/casa/acionar', { id: a.id, acao }); carregarCasa(); }
      catch (e) { mostrar($('statusCasa'), textoErro(e)); }
    });
    linha.append(nome, b);
    lista.append(linha);
  }
}

$('formCasa').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const endereco = $('casaEndereco').value, token = $('casaToken').value;
  try {
    if (await comPin('Home Assistant', 'Guardar o endereço e o token na TV?', 'Guardar',
      () => chamar('POST', '/api/extras/casa/config', { endereco, token }))) {
      $('casaToken').value = ''; mostrar($('statusCasa'), 'Guardado.'); carregarCasa();
    }
  } catch (e) { mostrar($('statusCasa'), textoErro(e)); }
});

$('formCampainha').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try {
    if (await comPin('Campainha', 'Mostrar a câmera na TV quando a campainha tocar?', 'Salvar',
      () => chamar('POST', '/api/extras/casa/campainha', { sensor: $('casaSensor').value, camera: $('casaCamera').value })))
      mostrar($('statusCasa'), 'Campainha salva.');
  } catch (e) { mostrar($('statusCasa'), textoErro(e)); }
});

$('btnCasaEsquecer').addEventListener('click', async () => {
  try {
    if (await comPin('Esquecer', 'Apagar o endereço e o token do Home Assistant da TV?', 'Apagar',
      () => chamar('POST', '/api/extras/casa/esquecer'))) carregarCasa();
  } catch (e) { mostrar($('statusCasa'), textoErro(e)); }
});

document.addEventListener('aba', (ev) => {
  if (ev.detail === 'extras') carregarCasa().catch((e) => mostrar($('statusCasa'), textoErro(e)));
});
