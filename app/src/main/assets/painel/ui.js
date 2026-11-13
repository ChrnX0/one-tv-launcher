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

// ---------- tela "Mais" (celular) ----------

/**
 * Monta os blocos da tela "Mais" a partir da própria navegação: cada título (nav-titulo) vira um grupo e cada aba
 * que não é principal vira um bloco. Assim, uma aba nova aparece aqui sozinha, sem editar este arquivo.
 */
function montarMais() {
  const grade = $('gradeMais');
  grade.replaceChildren();
  let grupo = null;
  for (const el of document.querySelectorAll('nav.abas > *')) {
    if (el.classList.contains('nav-titulo')) {
      const h = document.createElement('h2');
      h.className = 'titulo-grupo';
      h.textContent = el.textContent;
      grupo = document.createElement('div');
      grupo.className = 'blocos';
      grade.append(h, grupo);
    } else if (el.classList.contains('aba') && !el.classList.contains('principal')) {
      if (!grupo) { grupo = document.createElement('div'); grupo.className = 'blocos'; grade.appendChild(grupo); }
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'bloco';
      b.dataset.aba = el.dataset.aba;   // o clique cai no ouvinte único do app.js
      b.append(...[...el.childNodes].map((n) => n.cloneNode(true)));
      grupo.appendChild(b);
    }
  }
}
montarMais();

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

  // Saúde: só os números rápidos (a lista de memória fica na aba Saúde).
  chamar('GET', '/api/saude?rapido=1')
    .then((s) => desenharNumeros(s, $('inicioSaude')))
    .catch((e) => { $('inicioSaude').replaceChildren(Object.assign(document.createElement('p'), { className: 'aviso', textContent: textoErro(e) })); });

  // Uso de hoje: os 5 apps com mais minutos, com uma barra proporcional ao primeiro.
  try {
    const u = (await chamar('GET', '/api/uso')).minutos || {};
    const top = Object.entries(u).filter(([p]) => p !== 'br.paulo.tvlauncher').sort((a, b) => b[1] - a[1]).slice(0, 5);
    const caixa = $('inicioUso');
    caixa.replaceChildren();
    if (!top.length) { caixa.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nenhum app usado hoje.' })); return; }
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
  else if (!$('abaSaude').hidden) chamar('GET', '/api/saude?rapido=1').then((s) => desenharNumeros(s)).catch(() => {});
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
