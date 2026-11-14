// Aba Controle, parte 2 (10/11/2026): digitar ao vivo, touchpad e teclado do computador como controle.
// Usa as globais do app.js ($, chamar, atualizarFoto, mostrar) e do ui.js (aviso).
'use strict';

// ---------- fila única de envio ----------
// Cada tecla vira um "input" no shell da TV (uns 200-500 ms cada). Mandar tudo junto embaralharia a ordem; então
// uma fila manda um por vez, e letras seguidas que esperam na fila viram um pedido só ("ola" em vez de o, l, a).

const filaTv = [];
let enviandoTv = false;

function porNaFila(item) {
  const ultimo = filaTv[filaTv.length - 1];
  if (item.texto && ultimo && ultimo.texto && ultimo.texto.length + item.texto.length <= 100) ultimo.texto += item.texto;
  else filaTv.push(item);
  if (!enviandoTv) esvaziarFila();
}

async function esvaziarFila() {
  enviandoTv = true;
  try {
    while (filaTv.length) {
      const it = filaTv.shift();
      try {
        if (it.texto) await chamar('POST', '/api/tela/texto', { texto: it.texto });
        else await chamar('POST', '/api/tela/tecla', { tecla: it.tecla });
      } catch (e) {
        filaTv.length = 0;   // deu erro: descarta o resto (mandar depois, fora de ordem, seria pior)
        // Não se sabe mais o que a TV tem no campo: recomeça do zero (o próximo texto vai inteiro).
        textoAnterior = ''; $('campoTexto').value = '';
        aviso(textoErro(e), 'erro');
      }
    }
  } finally {
    enviandoTv = false;
    fotoRapida();
    setTimeout(atualizarFoto, 300);
  }
}

// ---------- vídeo ao vivo no lugar da foto ----------
// O mesmo vídeo da aba Extras (VideoAoVivo.kt: H.264 da tela, até 2 min por vez, um espectador só).

const ALVO_CONTROLE = {
  node: 'videoControle', status: 'statusTela', parar: 'btnPararVideoControle',
  fim: () => { mostrarFotoDeNovo(); },
};

function mostrarFotoDeNovo() {
  $('videoControle').hidden = true;
  $('btnVideoControle').hidden = false;
  $('btnPararVideoControle').hidden = true;
  atualizarFoto();
}

$('btnVideoControle').addEventListener('click', () => {
  pararFoto();
  $('fotoTela').hidden = true; $('semFoto').hidden = true;
  $('videoControle').hidden = false;
  $('btnVideoControle').hidden = true;
  verAoVivo(ALVO_CONTROLE);   // extras.js
});
$('btnPararVideoControle').addEventListener('click', () => { pararVideo(); mostrarFotoDeNovo(); });

// ---------- digitar ao vivo ----------

/**
 * Deixa o texto no que a TV aceita (a mesma regra do servidor, TextoNaTv.validoPainel): letras com ou sem acento,
 * números, espaço e . , @ _ -. Desde a ideia 56 o acento vai pela acessibilidade, direto no campo da TV.
 */
function paraTv(t) {
  return t.normalize('NFC').replace(/[^\p{L}\p{M}\p{N} .,@_\-]/gu, '');
}

let textoAnterior = '';
let avisouSimbolo = false;   // o aviso de acento aparece uma vez por vez que o modo ao vivo é ligado

$('aoVivo').addEventListener('change', () => {
  textoAnterior = $('campoTexto').value = '';
  $('campoTexto').placeholder = $('aoVivo').checked ? 'Cada letra vai direto para a TV' : 'Digitar na TV';
  avisouSimbolo = false;
  $('formTexto').classList.toggle('ao-vivo', $('aoVivo').checked);
  $('campoTexto').focus();
});

// Ao vivo: compara o campo com o que já foi mandado. Funciona com teclado de celular (que troca a palavra inteira
// ao corrigir): apaga na TV o que mudou e digita o novo.
$('campoTexto').addEventListener('input', () => {
  if (!$('aoVivo').checked) return;
  const novo = $('campoTexto').value;
  // Compara o que a TV deveria ter (o campo já convertido) com o que ela recebeu: assim símbolo pulado, acento ou
  // emoji nunca fazem apagar letra a mais ou a menos na TV.
  const alvo = paraTv(novo);
  let comum = 0;
  while (comum < alvo.length && comum < textoAnterior.length && alvo[comum] === textoAnterior[comum]) comum++;
  for (let i = 0; i < textoAnterior.length - comum; i++) porNaFila({ tecla: 'apagar_letra' });
  if (alvo.length > comum) porNaFila({ texto: alvo.slice(comum) });
  if (alvo.length < novo.normalize('NFC').length && !avisouSimbolo) { avisouSimbolo = true; aviso('Símbolos que a TV não digita são pulados.', 'erro'); }
  textoAnterior = alvo;
});

$('campoTexto').addEventListener('keydown', (ev) => {
  if (!$('aoVivo').checked) return;
  if (ev.key === 'Enter') { ev.preventDefault(); porNaFila({ tecla: 'entrar' }); textoAnterior = $('campoTexto').value = ''; }
  // Apagar com o campo vazio ainda apaga na TV (o que já estava lá antes de abrir o painel).
  else if (ev.key === 'Backspace' && !$('campoTexto').value) { ev.preventDefault(); porNaFila({ tecla: 'apagar_letra' }); }
});

// ---------- touchpad ----------
// Arrastar = setas (uma por cada 50 px, até 6 de uma vez); toque curto = OK; toque longo (0,6 s) = voltar.

const PASSO_PX = 50;
let toque = null;

function direcao(dx, dy) {
  return Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'direita' : 'esquerda') : (dy > 0 ? 'baixo' : 'cima');
}

$('touchpad').addEventListener('pointerdown', (ev) => {
  ev.preventDefault();
  $('touchpad').setPointerCapture(ev.pointerId);
  toque = { x: ev.clientX, y: ev.clientY, x0: ev.clientX, y0: ev.clientY, t: Date.now(), moveu: false };
  $('touchpad').classList.add('tocando');
});

$('touchpad').addEventListener('pointermove', (ev) => {
  if (!toque) return;
  const dx = ev.clientX - toque.x, dy = ev.clientY - toque.y;
  if (Math.max(Math.abs(dx), Math.abs(dy)) < PASSO_PX) return;
  // Uma seta por passo, já durante o arrasto: a pessoa vê o cursor andando na foto e para quando chegar.
  toque.moveu = true;
  porNaFila({ tecla: direcao(dx, dy) });
  toque.x = ev.clientX; toque.y = ev.clientY;
});

function soltar(ev) {
  if (!toque) return;
  const longe = Math.hypot(ev.clientX - toque.x0, ev.clientY - toque.y0) > 12;
  if (!toque.moveu && !longe) porNaFila({ tecla: Date.now() - toque.t > 600 ? 'voltar' : 'ok' });
  toque = null;
  $('touchpad').classList.remove('tocando');
}
$('touchpad').addEventListener('pointerup', soltar);
$('touchpad').addEventListener('pointercancel', () => { toque = null; $('touchpad').classList.remove('tocando'); });
$('touchpad').addEventListener('contextmenu', (ev) => ev.preventDefault());   // toque longo no celular não abre menu

// ---------- teclado do computador como controle ----------
// Na aba Controle, fora de um campo de texto: setas, Enter (OK), Esc/Backspace (voltar), espaço (play), +/- (volume).

const ATALHOS_TECLADO = {
  ArrowUp: 'cima', ArrowDown: 'baixo', ArrowLeft: 'esquerda', ArrowRight: 'direita', Enter: 'ok',
  Escape: 'voltar', Backspace: 'voltar', ' ': 'play', '+': 'volume+', '=': 'volume+', '-': 'volume-', m: 'mudo', h: 'inicio',
};

document.addEventListener('keydown', (ev) => {
  if ($('abaControle').hidden || $('telaPainel').hidden || ev.ctrlKey || ev.metaKey || ev.altKey) return;
  const el = document.activeElement;
  if (el && (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable)) return;
  if (document.querySelector('dialog[open], .modal:not([hidden])')) return;
  const tecla = ATALHOS_TECLADO[ev.key];
  if (!tecla) return;
  // Enter/espaço num botão focado já clicam nele: deixa o botão agir em vez de mandar a tecla duas vezes.
  if ((ev.key === 'Enter' || ev.key === ' ') && el && el.tagName === 'BUTTON') return;
  ev.preventDefault();
  porNaFila({ tecla });
});
