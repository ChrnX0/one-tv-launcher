// Casca do painel (10/11/2026): avisos curtos ("toasts"), a tela "Mais" do celular e a tela Início.
// Carrega por último: usa as funções globais do app.js ($, chamar, mudarAba, desenharNumeros, mb, apps).
// Todo texto vindo da TV entra por textContent (nunca innerHTML): nome de app é escolhido por quem fez o app.
'use strict';

// ---------- avisos curtos ----------

/**
 * Mostra um aviso no canto da tela que some sozinho em 4 s (erro: 7 s). Serve para o resultado de ações rápidas,
 * em qualquer aba, sem precisar de um parágrafo de status em cada cartão. [tipo]: 'ok' | 'erro' | (nada).
 */
function aviso(texto, tipo) {
  if (!texto) return;
  const t = document.createElement('div');
  t.className = 'toast' + (tipo ? ' ' + tipo : '');
  t.textContent = texto;
  $('toasts').appendChild(t);
  // Dois quadros depois, a classe "visivel" liga a transição de entrada (sem ela o navegador pula a animação).
  requestAnimationFrame(() => requestAnimationFrame(() => t.classList.add('visivel')));
  setTimeout(() => { t.classList.remove('visivel'); setTimeout(() => t.remove(), 300); }, tipo === 'erro' ? 7000 : 4000);
}

/**
 * Aviso com barra de progresso (teste do Paulo 20/11: cada porcentagem virava um aviso novo e a pilha cobria a tela).
 * Por quê: um envio é UMA coisa acontecendo. Como: um só aviso, que troca o texto e enche a barra laranja (laranja =
 * progresso, regra da marca); devolve a função que o fecha. fracao = 0..1.
 */
const progressos = {};
function avisoProgresso(chave, texto, fracao) {
  let t = progressos[chave];
  if (!t) {
    t = document.createElement('div');
    t.className = 'toast toast-progresso';
    t.setAttribute('role', 'status');
    t.innerHTML = '<span class="toast-texto"></span><span class="toast-barra"><span></span></span>';
    $('toasts').appendChild(t);
    requestAnimationFrame(() => requestAnimationFrame(() => t.classList.add('visivel')));
    progressos[chave] = t;
  }
  t.querySelector('.toast-texto').textContent = texto;
  t.querySelector('.toast-barra > span').style.width = Math.round(Math.max(0, Math.min(1, fracao)) * 100) + '%';
}
function fimProgresso(chave) {
  const t = progressos[chave];
  if (!t) return;
  delete progressos[chave];
  t.classList.remove('visivel'); setTimeout(() => t.remove(), 300);
}

// A tela "Mais" do celular saiu no painel v3 (16/11/2026): as 4 seções cabem na barra de baixo e as páginas internas
// ficam em listas dentro de "Na TV" e "Configurações" (index.html, .lista-hub).

// ---------- tela Início ----------

let nomesApps = null;   // promessa de pacote -> nome; pedida uma vez só (a lista completa leva ~1,5 s na TV)

function nomeDe(pacote) {
  // Guarda a PROMESSA, não o resultado: as 5 linhas do Início pedem juntas e todas esperam o mesmo /api/apps.
  if (!nomesApps) {
    nomesApps = (typeof apps !== 'undefined' && apps.length)
      ? Promise.resolve(Object.fromEntries(apps.map((a) => [a.pacote, a.nome])))
      : chamar('GET', '/api/apps').then((r) => Object.fromEntries(r.apps.map((a) => [a.pacote, a.nome]))).catch(() => { nomesApps = null; return {}; });
  }
  return nomesApps.then((m) => m[pacote] || pacote);
}

function minutosLegiveis(m) {
  if (!Number.isFinite(m)) return '—';
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`;
}

async function carregarInicio() {
  $('inicioNaTela').textContent = (ultimoEstado && ultimoEstado.naTela) || '…';
  miniaturaInicio();

  // Estudo do painel (20/11/2026, PR 3): a Sofy fala só se tem recado; "Como a TV está" virou a linha "Controle total".
  carregarFaixaSofy();
  carregarPoderes();
  if (typeof desenharAtalhos === 'function') desenharAtalhos();   // PR 4: Seus atalhos (shortcuts.js)

  // Uso de hoje: os 5 apps com mais minutos, com uma barra proporcional ao primeiro.
  try {
    const u = (await chamar('GET', '/api/uso')).minutos || {};
    const top = Object.entries(u).filter(([p]) => p !== 'tv.sofaly.launcher' && p !== 'br.paulo.tvlauncher').sort((a, b) => b[1] - a[1]).slice(0, 5);
    const caixa = $('inicioUso');
    caixa.replaceChildren();
    if (!top.length) { listaVazia(caixa, 'Nenhum app usado hoje.'); return; }
    const maior = top[0][1];
    for (const [pacote, min] of top) {
      const linha = document.createElement('div');
      linha.className = 'linha-uso';
      const img = document.createElement('img');
      img.alt = ''; img.loading = 'lazy';
      img.onerror = () => { img.style.visibility = 'hidden'; };   // sem ícone: fica o quadrado vazio, sem o ícone de imagem quebrada
      img.src = '/api/apps/' + encodeURIComponent(pacote) + '/icone';
      const info = document.createElement('div');
      info.className = 'info';
      const nome = document.createElement('span'); nome.className = 'nome'; nome.textContent = pacote;
      const barra = document.createElement('span'); barra.className = 'barra';
      barra.style.setProperty('--p', Math.max(4, Math.round(min * 100 / maior)) + '%');
      info.append(nome, barra);
      const t = document.createElement('span'); t.className = 'tempo'; t.textContent = minutosLegiveis(min);
      linha.append(img, info, t);
      caixa.appendChild(linha);
      nomeDe(pacote).then((n) => { nome.textContent = n; });
    }
  } catch (e) {
    $('inicioUso').replaceChildren(Object.assign(document.createElement('p'), { className: 'aviso', textContent: textoErro(e) }));
  }
}

// ---------- Início: faixa da Sofy, Controle total e listas vazias (estudo do painel 20/11/2026, PR 3) ----------

/** Recado mais novo da Sofy que ainda vale (não venceu). As frases vêm prontas da TV, na língua deste navegador. */
async function carregarFaixaSofy() {
  const faixa = $('faixaSofy');
  if (!faixa) return;
  let o;
  try { o = await chamar('GET', '/api/observer?lang=' + encodeURIComponent(document.documentElement.lang || 'pt')); }
  catch (_) { faixa.hidden = true; return; }   // versão sem o Observador: a Início fica calma
  const agora = Date.now();
  const recado = (o.notes || []).find((n) => n.title && (!n.expiresAt || n.expiresAt > agora));
  if (typeof contarRecados === 'function') contarRecados((o.notes || []).filter((n) => n.title && (!n.expiresAt || n.expiresAt > agora)).length);
  faixa.hidden = !recado;
  if (!recado) return;
  $('faixaSofyTitulo').textContent = recado.title;
  $('faixaSofyTexto').textContent = recado.text || '';
  const lugar = $('faixaSofyRosto');
  if (typeof Sofy === 'undefined') { lugar.hidden = true; return; }
  try {
    await Sofy.carregar();
    const expr = recado.severity === 'CRITICAL' ? 'SURPRISED' : recado.severity === 'WARNING' ? 'WORRIED' : 'ATTENTIVE';
    let svg = lugar.querySelector('svg');
    if (!svg) { svg = Sofy.criar(expr, { tamanho: 48 }); svg.setAttribute('aria-hidden', 'true'); lugar.appendChild(svg); Sofy.tocar(svg, expr); }
    else if (svg._sofy.expr !== expr) Sofy.tocar(svg, expr);
  } catch (_) { lugar.hidden = true; }
}

/** Os 3 poderes da TV (Dono, Shell e Acessibilidade). Cada um diz o que para sem ele, em linguagem de casa. */
const PODERES_TV = [
  { chave: 'teclas', nome: 'Acessibilidade', passo: 'teclas',
    sem: 'A acessibilidade da TV está desligada. Sem ela, o botão HOME do controle e os recados por cima dos apps param.' },
  { chave: 'shell', nome: 'Shell', passo: 'parear',
    sem: 'O shell próprio da TV caiu. Sem ele, congelar apps e os comandos avançados param.' },
  { chave: 'dono', nome: 'Dono do aparelho', passo: null,
    sem: 'A TV não é dona do aparelho. Esconder apps e o HOME fixo usam o modo simples.' },
];

let passoReligar = null;
async function carregarPoderes() {
  const linha = $('linhaPoderes');
  if (!linha) return;
  let e;
  try {
    const r = await fetch('/api/primeira/poderes', { headers: { 'X-Painel': '1' }, credentials: 'same-origin', cache: 'no-store' });
    if (!r.ok) throw new Error(String(r.status));
    e = await r.json();
  } catch (_) { linha.hidden = true; return; }
  const faltam = PODERES_TV.filter((p) => !e[p.chave]);
  // "Caiu" é acessibilidade ou shell (ligam e caem sozinhos, por atualização ou reinício). Sem dono é escolha da casa:
  // fica na linha calma, sem alerta.
  const caiu = faltam.filter((p) => p.chave !== 'dono');
  linha.hidden = false;
  linha.classList.toggle('alerta', caiu.length > 0);
  $('poderesResumo').textContent = faltam.length ? 'Controle total: parcial' : 'Controle total: ativo';
  $('poderesQuais').replaceChildren();
  for (const p of PODERES_TV) {
    const s = document.createElement('span');
    s.className = 'poder' + (e[p.chave] ? ' ligado' : '');
    s.textContent = p.nome;
    $('poderesQuais').appendChild(s);
  }
  const explica = $('poderesExplica');
  explica.hidden = !faltam.length;
  explica.textContent = faltam.length ? (caiu[0] || faltam[0]).sem : '';
  passoReligar = caiu.find((p) => p.passo)?.passo || null;
  $('btnReligarPoder').hidden = !passoReligar;
}
$('btnReligarPoder')?.addEventListener('click', async () => {
  if (!passoReligar) return;
  try { await chamar('POST', '/api/primeira/poderes', { passo: passoReligar }); aviso('Abriu na TV. Use o controle.', 'ok'); }
  catch (e) { aviso(textoErro(e), 'erro'); }
});

/**
 * Lista vazia com a Sofy: o rosto calmo e uma frase do que fazer. Só quando não há outro rosto dela na tela
 * (regra 9: a Sofy aparece uma vez por tela).
 */
function listaVazia(caixa, texto) {
  const p = Object.assign(document.createElement('p'), { className: 'aviso', textContent: texto });
  const temSofy = [...document.querySelectorAll('.painel-aba:not([hidden]) .sofy-rosto')].some((r) => r.closest('[hidden]') === null && r.querySelector('svg'));
  if (typeof Sofy === 'undefined' || temSofy) { caixa.replaceChildren(p); return; }
  const vazio = document.createElement('div');
  vazio.className = 'vazio-sofy';
  const rosto = document.createElement('span');
  rosto.className = 'sofy-rosto'; rosto.setAttribute('aria-hidden', 'true');
  vazio.append(rosto, p);
  caixa.replaceChildren(vazio);
  Sofy.carregar().then(() => { const svg = Sofy.criar('SLEEPING', { tamanho: 40 }); svg.setAttribute('aria-hidden', 'true'); rosto.appendChild(svg); })
    .catch(() => rosto.remove());
}

// Portas e links que levam a um bloco de outra página ("tvfilmes.cartaoRadios"): mesma ida da busca (irPara).
document.addEventListener('click', (ev) => {
  const b = ev.target.closest('[data-ir]');
  if (b && !$('telaPainel').hidden) { ev.preventDefault(); irPara(b.dataset.ir); }
});

document.addEventListener('aba', (ev) => { if (ev.detail === 'inicio') carregarInicio(); });
// A abertura do painel já mostrou o Início antes deste arquivo carregar? Carrega agora.
if (!$('telaPainel').hidden && !$('abaInicio').hidden) carregarInicio();

// ---------- atualização ao vivo ----------
// /api/eventos (Server-Sent Events, PainelWeb.eventos): "o que está na TV" chega na hora em que muda. Só com a aba do
// navegador visível (escondida, fecha: cada janela aberta segura uma linha de atendimento na TV, e são só 2).
// Se a TV recusar (sessão vencida, janelas demais), fica o "a cada 30 s" do app.js.

let fonteEventos = null;

function ligarEventos() {
  if (fonteEventos || typeof EventSource === 'undefined') return;
  if ($('telaPainel').hidden || document.visibilityState !== 'visible') return;
  const f = new EventSource('/api/eventos');
  fonteEventos = f;
  f.addEventListener('open', () => $('naTela').classList.add('ao-vivo'));
  f.addEventListener('estado', (ev) => {
    let d = null;
    try { d = JSON.parse(ev.data); } catch (e) { return; }
    const antes = ultimoEstado.naTela;
    aplicarNaTela(d.naTela);
    // Mudou o que está na tela: a foto do Controle e os "mais usados" do Início ficam velhos; atualiza já.
    if (antes && antes !== d.naTela) {
      if (!$('abaControle').hidden) atualizarFoto();
      if (!$('abaInicio').hidden) carregarInicio();
    }
  });
  f.addEventListener('error', () => {
    $('naTela').classList.remove('ao-vivo');
    // CONNECTING = o navegador vai tentar de novo sozinho (fim dos 5 min, Wi-Fi piscou). CLOSED = a TV recusou.
    if (f.readyState === EventSource.CLOSED && fonteEventos === f) {
      fonteEventos = null;
      // A TV recusou (vagas cheias por um instante, por exemplo): tenta de novo em 15 s; o polling de 30 s cobre o meio.
      setTimeout(ligarEventos, 15000);
    }
  });
}

function desligarEventos() {
  if (!fonteEventos) return;
  fonteEventos.close();
  fonteEventos = null;
  $('naTela').classList.remove('ao-vivo');
}

document.addEventListener('visibilitychange', () => (document.visibilityState === 'visible' ? ligarEventos() : desligarEventos()));
document.addEventListener('aba', ligarEventos);   // o painel acabou de abrir (depois do PIN) ou trocou de aba
window.addEventListener('pagehide', desligarEventos);
ligarEventos();

// Números que mudam sozinhos: o Início e a Saúde se refazem enquanto estão na tela (só com a janela visível).
setInterval(() => {
  if (document.visibilityState !== 'visible' || $('telaPainel').hidden) return;
  if (!$('abaInicio').hidden) carregarInicio();
  else if (abaAberta('saude')) chamar('GET', '/api/saude?rapido=1').then((s) => desenharNumeros(s)).catch(() => {});
}, 20000);

// Miniatura da tela da TV no cartão "Agora na TV" (13/11/2026): uma foto só, ao abrir o Início (a aba Controle é que
// atualiza a cada 2 s). TV em espera ou foto que falhou: o cartão fica só com o nome, sem ícone quebrado.
let miniaturaUrl = null;
async function miniaturaInicio() {
  const img = $('inicioMiniatura');
  if (!img) return;
  if (ultimoEstado && ultimoEstado.naTela === 'em espera') { img.hidden = true; return; }
  try {
    const r = await fetch('/api/tela/foto', { credentials: 'same-origin', cache: 'no-store' });
    if (!r.ok) throw new Error(String(r.status));
    const url = URL.createObjectURL(await r.blob());
    img.onload = () => { img.hidden = false; };
    img.onerror = () => { img.hidden = true; };
    img.src = url;
    if (miniaturaUrl) URL.revokeObjectURL(miniaturaUrl);
    miniaturaUrl = url;
  } catch (_) { img.hidden = true; }
}

// App instalável (PWA): o navegador só instala com o service worker registrado, e ele só existe em https
// (contexto seguro). Em http (http://tv:8080) nada acontece: lá vira só um atalho do navegador.
if (window.isSecureContext && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => {});
}
