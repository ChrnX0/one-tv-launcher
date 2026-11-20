// Cartão "Addons" da aba Configurações (16/11/2026; rotas em PainelAddons.kt). Addons no formato do Stremio, só vídeo
// HTTP: torrent e P2P são recusados pela TV. Instalar e remover pedem o PIN; instalar pede o aceite dos termos.
// Usa as funções globais do app.js: $, chamar, mostrar, confirmar, textoErro. Texto da TV/addon só por textContent.
'use strict';

const MOTIVO_ADDON = {
  P2P: 'Este addon usa torrent/P2P. A TV só aceita addons com vídeo por link direto (HTTP).',
  ADULTO: 'Addon marcado como conteúdo adulto: não é aceito.',
  PRECISA_CONFIGURAR: 'Este addon precisa ser configurado antes. Configure no site dele e cole o endereço que ele der.',
  SEM_RECURSO_UTIL: 'Este addon não oferece catálogo, ficha, vídeo ou legenda.',
  INVALIDO: 'O endereço não tem um addon válido (manifest.json).',
  ENDERECO: 'Endereço inválido. Cole o link que termina em manifest.json.',
  CHEIO: 'Limite de addons atingido. Remova um para instalar outro.',
  SEM_REDE: 'O addon não respondeu. Confira o endereço e tente de novo.',
  SEM_ACEITE: 'Aceite os termos para instalar.',
};

const TERMOS_ADDON = 'Addons são feitos por terceiros. A Sofaly não cria, não hospeda e não confere o que eles mostram. ' +
  'Quem instala um addon responde pelo conteúdo dele e por ter direito de assistir. A Sofaly só toca vídeo por link ' +
  'direto: fontes de torrent e P2P são ignoradas.';

document.addEventListener('aba', (ev) => { if (ev.detail === 'config') carregarAddons(); });

function erroAddon(e) {
  const m = e && e.dados && e.dados.motivo;
  return (m && MOTIVO_ADDON[m]) || textoErro(e);
}

// Filtro por tipo (pedido do Paulo, 18/11/2026: "separar por tipo, como o Stremio, ou um filtro melhor"): vale para
// os instalados e os recomendados ao mesmo tempo. '' = Todos. Os nomes das seções vêm da TV, já traduzidos.
let filtroAddon = '';
let ultimoAddons = null;

async function carregarAddons() {
  const status = $('statusAddons');
  mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/addons'); } catch (e) { mostrar(status, textoErro(e)); return; }
  $('termosAddons').textContent = TERMOS_ADDON;
  $('aceiteAddons').checked = !!r.aceite;
  ultimoAddons = r;
  desenharAddons();
}

function desenharAddons() {
  const r = ultimoAddons;
  if (!r) return;
  const status = $('statusAddons'), lista = $('listaAddons');
  const sugeridos = r.sugeridos || [];
  // Só os tipos que têm algo; com um tipo só, o filtro não ajuda e some.
  const presentes = new Set([...r.addons, ...sugeridos].map((x) => x.grupo));
  const grupos = (r.grupos || []).filter((g) => g.id === '' || presentes.has(g.id));
  if (filtroAddon && !presentes.has(filtroAddon)) filtroAddon = '';
  const barra = $('filtroAddons');
  barra.replaceChildren();
  barra.hidden = grupos.length <= 2;
  for (const g of grupos) {
    const b = Object.assign(document.createElement('button'), {
      type: 'button', textContent: g.nome, className: 'botao-secundario',
    });
    b.setAttribute('aria-pressed', String(g.id === filtroAddon));
    b.addEventListener('click', () => { filtroAddon = g.id; desenharAddons(); });
    barra.appendChild(b);
  }
  const passa = (x) => !filtroAddon || x.grupo === filtroAddon;
  const nomeGrupo = (id) => (r.grupos || []).find((g) => g.id === id)?.nome || '';
  lista.replaceChildren();
  const instalados = r.addons.filter(passa);
  if (!instalados.length) {
    lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso-curto', textContent: 'Nenhum addon instalado.' }));
  }
  mostrarSugeridos(sugeridos.filter(passa), nomeGrupo);
  for (const a of instalados) {
    const linha = document.createElement('p');
    const nome = document.createElement('strong');
    nome.textContent = a.nome;
    const det = document.createElement('span');
    det.className = 'aviso-curto';
    det.textContent = ` · ${nomeGrupo(a.grupo)} · ${a.versao || ''} · ${a.base}`;
    const botao = document.createElement('button');
    botao.type = 'button';
    botao.className = 'botao-secundario';
    botao.textContent = 'Remover';
    botao.addEventListener('click', async () => {
      try {
        const ok = await confirmar('Remover addon?', `${a.nome} sai da TV.`, 'Remover',
          () => chamar('POST', '/api/addons/remover', { base: a.base }));
        if (ok) carregarAddons();
      } catch (e) { mostrar(status, erroAddon(e)); }
    });
    linha.append(nome, det, ' ', botao);
    lista.appendChild(linha);
  }
}

/**
 * Lista curada (18/11/2026): add-ons que conferimos e funcionam na TV, uma seção por tipo. "Instalar" faz o mesmo que
 * colar o endereço (aceite dos termos + PIN). Nome, texto e endereço vêm da TV: só por textContent.
 */
function mostrarSugeridos(lista, nomeGrupo) {
  const caixa = $('sugeridosAddons');
  if (!caixa) return;
  caixa.replaceChildren();
  caixa.hidden = !lista.length;
  if (!lista.length) return;
  caixa.appendChild(Object.assign(document.createElement('h3'), { textContent: 'Recomendados' }));
  let grupo = null;
  for (const s of lista) {
    if (s.grupo !== grupo) {
      grupo = s.grupo;
      caixa.appendChild(Object.assign(document.createElement('p'), { className: 'aviso-curto', textContent: nomeGrupo(grupo) }));
    }
    const linha = document.createElement('p');
    const nome = Object.assign(document.createElement('strong'), { textContent: s.nome });
    const det = Object.assign(document.createElement('span'), { className: 'aviso-curto', textContent: s.texto ? ` · ${s.texto}` : '' });
    const botao = Object.assign(document.createElement('button'), { type: 'button', className: 'botao-secundario', textContent: 'Instalar' });
    botao.addEventListener('click', () => instalarAddon(s.url));
    linha.append(nome, det, ' ', botao);
    caixa.appendChild(linha);
  }
}

/** Instala [url] com aceite + PIN (formulário e lista curada). */
async function instalarAddon(url) {
  const status = $('statusAddons');
  if (!$('aceiteAddons').checked) { mostrar(status, MOTIVO_ADDON.SEM_ACEITE); $('aceiteAddons').focus(); return; }
  mostrar(status, '');
  const fazer = async () => {
    try { return await chamar('POST', '/api/addons/instalar', { url, aceite: true }); }
    catch (e) { if (e instanceof PrecisaPin) throw e; throw new Error(erroAddon(e)); }
  };
  try {
    const r = await confirmar('Instalar addon?', TERMOS_ADDON, 'Instalar', fazer, false);
    if (r) { $('urlAddon').value = ''; mostrar(status, `Instalado: ${r.instalado}. Aparece na TV no card Addons.`); carregarAddons(); }
  } catch (e) { mostrar(status, erroAddon(e)); }
}

document.addEventListener('DOMContentLoaded', () => {
  const form = $('formAddon');
  if (!form) return;
  form.addEventListener('submit', (ev) => {
    ev.preventDefault();
    const url = $('urlAddon').value.trim();
    if (url) instalarAddon(url);
  });
});

// ---- Ler QR (16/11/2026). O navegador só abre a câmera em https (painel na porta 8444). Usa o leitor do próprio
// navegador (BarcodeDetector, Chrome do Android) e, sem ele (iPhone, Firefox), o jsQR embutido (jsqr.js, Apache 2.0),
// carregado só quando precisa. O QR lido só preenche o campo: instalar continua pedindo aceite e PIN.
let qrFluxo = null;

function pararQrAddon() {
  if (qrFluxo) { qrFluxo.getTracks().forEach((t) => t.stop()); qrFluxo = null; }
  const v = $('videoQrAddon');
  if (v) v.srcObject = null;
  if ($('qrAddon')) $('qrAddon').hidden = true;
}

function carregarJsQr() {
  if (window.jsQR) return Promise.resolve(window.jsQR);
  return new Promise((ok, falha) => {
    const s = document.createElement('script');
    s.src = 'jsqr.js';
    s.onload = () => (window.jsQR ? ok(window.jsQR) : falha(new Error('leitor de QR não carregou')));
    s.onerror = () => falha(new Error('leitor de QR não carregou'));
    document.head.appendChild(s);
  });
}

/** Devolve uma função que lê um quadro do vídeo e responde o texto do QR, ou '' se não achou. */
async function leitorQr(video) {
  if ('BarcodeDetector' in window) {
    try {
      const formatos = await window.BarcodeDetector.getSupportedFormats();
      if (formatos.includes('qr_code')) {
        const d = new window.BarcodeDetector({ formats: ['qr_code'] });
        return async () => { const r = await d.detect(video); return r.length ? r[0].rawValue : ''; };
      }
    } catch (e) { /* cai no jsQR */ }
  }
  const jsQR = await carregarJsQr();
  const tela = document.createElement('canvas');
  const ctx = tela.getContext('2d', { willReadFrequently: true });
  return async () => {
    const w = video.videoWidth, h = video.videoHeight;
    if (!w || !h) return '';
    const escala = Math.min(1, 640 / Math.max(w, h));   // quadro menor: lê mais rápido no celular
    tela.width = Math.round(w * escala); tela.height = Math.round(h * escala);
    ctx.drawImage(video, 0, 0, tela.width, tela.height);
    const img = ctx.getImageData(0, 0, tela.width, tela.height);
    const r = jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' });
    return r ? r.data : '';
  };
}

async function lerQrAddon() {
  const status = $('statusAddons');
  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    mostrar(status, 'Para ler QR, abra o painel pelo endereço https (porta 8444). No http o navegador não libera a câmera.');
    return;
  }
  mostrar(status, '');
  const video = $('videoQrAddon');
  try {
    qrFluxo = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
  } catch (e) {
    mostrar(status, 'Não consegui abrir a câmera. Confira se o navegador tem permissão para usá-la.');
    return;
  }
  $('qrAddon').hidden = false;
  video.srcObject = qrFluxo;
  await video.play().catch(() => {});
  let ler;
  try { ler = await leitorQr(video); } catch (e) { pararQrAddon(); mostrar(status, textoErro(e)); return; }
  const passo = async () => {
    if (!qrFluxo) return;
    let texto = '';
    try { texto = await ler(); } catch (e) { texto = ''; }
    // Só aceita o que parece endereço de addon; outro QR qualquer é ignorado e a câmera continua.
    if (texto && /^(https?|stremio):\/\//i.test(texto.trim())) {
      pararQrAddon();
      $('urlAddon').value = texto.trim();
      mostrar(status, 'QR lido. Confira o endereço e toque em Instalar.');
      return;
    }
    setTimeout(passo, 250);
  };
  passo();
}

document.addEventListener('DOMContentLoaded', () => {
  if ($('btnQrAddon')) $('btnQrAddon').addEventListener('click', lerQrAddon);
  if ($('btnFecharQrAddon')) $('btnFecharQrAddon').addEventListener('click', pararQrAddon);
});
document.addEventListener('aba', (ev) => { if (ev.detail !== 'config') pararQrAddon(); });
document.addEventListener('visibilitychange', () => { if (document.hidden) pararQrAddon(); });
