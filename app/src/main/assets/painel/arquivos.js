// Aba Arquivos, parte 2 (10/11/2026): prévia (foto, vídeo, música, texto) e organizar (renomear, mover, apagar).
// Usa as globais do app.js ($, chamar, pastaAtual, itensPasta, juntar, abrirPasta, confirmar, tamanhoLegivel).
// A prévia vem de /api/arquivos/baixar?ver=1, que só aceita uma lista fechada de tipos e responde com CSP "sandbox"
// (ver PainelHttp.tipoPrevia). A lista abaixo é a mesma, para não oferecer prévia que a TV vai recusar.
'use strict';

const EXT_PREVIA = {
  imagem: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'],
  video: ['mp4', 'm4v', 'webm', 'mkv', 'mov'],
  audio: ['mp3', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wav', 'flac'],
  texto: ['txt', 'log', 'md', 'csv', 'json', 'srt', 'vtt', 'm3u', 'm3u8', 'properties', 'conf', 'ini', 'nfo'],
};
const LIMITE_TEXTO = 512 * 1024;   // texto maior que isso: mostra só o começo (um log de 50 MB travaria a aba)

/** 'imagem' | 'video' | 'audio' | 'texto' | '' (sem prévia). */
function tipoDoArquivo(nome) {
  const ext = (nome.includes('.') ? nome.split('.').pop() : '').toLowerCase();
  for (const [tipo, lista] of Object.entries(EXT_PREVIA)) if (lista.includes(ext)) return tipo;
  return '';
}

const urlPrevia = (nome) => '/api/arquivos/baixar?ver=1&caminho=' + encodeURIComponent(juntar(pastaAtual, nome));

// ---------- prévia ----------

let previaAtual = null;   // item da pasta mostrado agora
let textoAbortar = null;  // cancela a leitura de um texto grande se a pessoa passar para o próximo

function abrirPrevia(it) {
  previaAtual = it;
  const d = $('previa');
  const caixa = $('conteudoPrevia');
  if (textoAbortar) { textoAbortar.abort(); textoAbortar = null; }
  // Para o vídeo/música anterior antes de trocar (senão o som continua tocando escondido).
  for (const m of caixa.querySelectorAll('video, audio')) { m.pause(); m.removeAttribute('src'); m.load(); }
  caixa.replaceChildren();
  $('tituloPrevia').textContent = it.nome;
  $('infoPrevia').textContent = tamanhoLegivel(it.tamanho) + ' · ' + new Date(it.modificado).toLocaleString('pt-BR');
  $('baixarPrevia').href = '/api/arquivos/baixar?caminho=' + encodeURIComponent(juntar(pastaAtual, it.nome));
  $('baixarPrevia').setAttribute('download', it.nome);
  const tipo = tipoDoArquivo(it.nome);
  const falhou = (texto) => caixa.replaceChildren(Object.assign(document.createElement('p'), { className: 'aviso', textContent: texto }));
  if (tipo === 'imagem') {
    const img = document.createElement('img');
    img.alt = it.nome;
    img.onerror = () => falhou('Não consegui mostrar esta imagem. Baixe para abrir.');
    img.src = urlPrevia(it.nome);
    caixa.appendChild(img);
  } else if (tipo === 'video' || tipo === 'audio') {
    const m = document.createElement(tipo);
    m.controls = true; m.autoplay = true; m.preload = 'metadata';
    // MKV e MOV dependem do codec: muitos tocam no Chrome, alguns não. Sem jeito de saber antes de tentar.
    m.onerror = () => falhou('O navegador não tocou este arquivo (formato ou codec sem suporte). Baixe para abrir num player.');
    m.src = urlPrevia(it.nome);
    caixa.appendChild(m);
  } else if (tipo === 'texto') {
    const pre = document.createElement('pre');
    pre.className = 'previa-texto';
    pre.textContent = 'Lendo…';
    caixa.appendChild(pre);
    textoAbortar = new AbortController();
    lerTexto(it, pre, textoAbortar.signal);
  }
  if (!d.open) d.showModal();
  atualizarSetas();
}

/** Lê só o começo do arquivo (pedido com Range): um log enorme não vem inteiro da TV. */
async function lerTexto(it, pre, sinal) {
  try {
    const r = await fetch(urlPrevia(it.nome), { credentials: 'same-origin', cache: 'no-store', signal: sinal,
      headers: { Range: `bytes=0-${LIMITE_TEXTO - 1}` } });
    if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(erroAmigavel(r.status, d.erro)); }
    let t = new TextDecoder('utf-8').decode(await r.arrayBuffer());
    if (it.tamanho > LIMITE_TEXTO) t += `\n\n… (mostrando os primeiros ${tamanhoLegivel(LIMITE_TEXTO)} de ${tamanhoLegivel(it.tamanho)}; baixe para ver tudo)`;
    pre.textContent = t;
  } catch (e) {
    if (e.name !== 'AbortError') pre.textContent = textoErro(e);
  }
}

/** Os arquivos da pasta que têm prévia, na ordem da lista: as setas andam por eles. */
const comPrevia = () => itensPasta.filter((i) => !i.pasta && tipoDoArquivo(i.nome));

function andarPrevia(passo) {
  const l = comPrevia();
  const i = l.findIndex((x) => x.nome === previaAtual?.nome);
  const prox = l[i + passo];
  if (prox) abrirPrevia(prox);
}

function atualizarSetas() {
  const l = comPrevia();
  const i = l.findIndex((x) => x.nome === previaAtual?.nome);
  $('anteriorPrevia').disabled = i <= 0;
  $('proximaPrevia').disabled = i < 0 || i >= l.length - 1;
  $('posicaoPrevia').textContent = l.length > 1 && i >= 0 ? `${i + 1} de ${l.length}` : '';
}

function fecharPrevia() {
  if (textoAbortar) { textoAbortar.abort(); textoAbortar = null; }
  for (const m of $('conteudoPrevia').querySelectorAll('video, audio')) { m.pause(); m.removeAttribute('src'); m.load(); }
  $('conteudoPrevia').replaceChildren();
  if ($('previa').open) $('previa').close();
}

$('anteriorPrevia').addEventListener('click', () => andarPrevia(-1));
$('proximaPrevia').addEventListener('click', () => andarPrevia(1));
$('fecharPrevia').addEventListener('click', fecharPrevia);
$('previa').addEventListener('close', fecharPrevia);   // Esc também para o vídeo
$('previa').addEventListener('click', (ev) => { if (ev.target === $('previa')) fecharPrevia(); });
$('previa').addEventListener('keydown', (ev) => {
  if (ev.target.closest('video, audio')) return;   // setas no player = pular no vídeo
  if (ev.key === 'ArrowLeft') { ev.preventDefault(); andarPrevia(-1); }
  if (ev.key === 'ArrowRight') { ev.preventDefault(); andarPrevia(1); }
});
// Arrastar o dedo para o lado na foto troca de foto (celular).
let toqueX = null;
$('conteudoPrevia').addEventListener('touchstart', (ev) => { toqueX = ev.touches[0].clientX; }, { passive: true });
$('conteudoPrevia').addEventListener('touchend', (ev) => {
  if (toqueX === null) return;
  const dx = ev.changedTouches[0].clientX - toqueX;
  toqueX = null;
  if (Math.abs(dx) > 60 && tipoDoArquivo(previaAtual?.nome || '') === 'imagem') andarPrevia(dx < 0 ? 1 : -1);
});

// ---------- organizar: renomear, mover, apagar ----------

let organizando = null;     // item escolhido
let destinoMover = '';      // pasta de destino escolhida no seletor
let pedidoDestino = 0;

function organizarItem(it) {
  organizando = it;
  destinoMover = pastaAtual;
  $('tituloOrganizar').textContent = it.nome;
  $('nomeOrganizar').value = it.nome;
  mostrar($('erroOrganizar'), '');
  $('organizar').showModal();
  // Seleciona o nome sem a extensão (como no computador): digitar já troca só o nome.
  const ponto = it.pasta ? -1 : it.nome.lastIndexOf('.');
  $('nomeOrganizar').focus();
  $('nomeOrganizar').setSelectionRange(0, ponto > 0 ? ponto : it.nome.length);
  desenharDestino();
}

async function desenharDestino() {
  const meu = ++pedidoDestino;
  $('caminhoDestino').textContent = 'Memória interna' + (destinoMover ? ' › ' + destinoMover.split('/').join(' › ') : '');
  $('subirDestino').disabled = !destinoMover;
  const lista = $('pastasDestino');
  lista.replaceChildren(Object.assign(document.createElement('li'), { className: 'vazio', textContent: 'Carregando…' }));
  try {
    const r = await chamar('GET', '/api/arquivos?caminho=' + encodeURIComponent(destinoMover));
    if (meu !== pedidoDestino) return;
    const origem = juntar(pastaAtual, organizando.nome);
    const pastas = r.itens.filter((i) => i.pasta && juntar(r.caminho, i.nome) !== origem);   // não entra nela mesma
    lista.replaceChildren();
    for (const p of pastas) {
      const li = document.createElement('li');
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = '📁 ' + p.nome;
      b.addEventListener('click', () => { destinoMover = juntar(r.caminho, p.nome); desenharDestino(); });
      li.appendChild(b);
      lista.appendChild(li);
    }
    if (!pastas.length) lista.appendChild(Object.assign(document.createElement('li'), { className: 'vazio', textContent: 'Sem subpastas aqui.' }));
  } catch (e) {
    if (meu === pedidoDestino) lista.replaceChildren(Object.assign(document.createElement('li'), { className: 'vazio', textContent: textoErro(e) }));
  }
}

$('subirDestino').addEventListener('click', () => {
  destinoMover = destinoMover.includes('/') ? destinoMover.slice(0, destinoMover.lastIndexOf('/')) : '';
  desenharDestino();
});

$('formOrganizar').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeOrganizar').value.trim();
  if (!nome || /[\/\\]/.test(nome) || nome === '.' || nome === '..') { mostrar($('erroOrganizar'), 'Nome inválido (sem barra).'); return; }
  if (nome === organizando.nome && destinoMover === pastaAtual) { $('organizar').close(); return; }   // nada mudou
  $('salvarOrganizar').disabled = true;
  try {
    await chamar('POST', '/api/arquivos/mover', { caminho: juntar(pastaAtual, organizando.nome), pasta: destinoMover, nome });
    $('organizar').close();
    const movido = destinoMover !== pastaAtual;
    aviso(movido ? `${nome} foi para ${destinoMover || 'a memória interna'}.` : `Renomeado para ${nome}.`, 'ok');
    abrirPasta(pastaAtual);
  } catch (e) {
    mostrar($('erroOrganizar'), textoErro(e));
  } finally {
    $('salvarOrganizar').disabled = false;
  }
});

$('apagarOrganizar').addEventListener('click', () => { const it = organizando; $('organizar').close(); apagarItem(it); });
$('cancelarOrganizar').addEventListener('click', () => $('organizar').close());
