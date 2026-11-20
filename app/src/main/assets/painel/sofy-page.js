// Aba Sofy (PR 5 do estudo "painel com a identidade Sofaly", 20/11/2026). Regras do estudo:
//   5. A Sofy aponta o lugar: todo recado leva ao ajuste que explica ou desfaz o que aconteceu.
//   9. A Sofy uma vez por tela: o rosto fica só no alto desta página (com a cara do recado mais sério).
// Mostra os recados de hoje (/api/observer, com as frases na língua do navegador), o porquê de cada um (o que ela fez:
// só anotou, sugere, agiu), os apps congelados em prova com "Desfazer" (descongelar) e as portas para Diário,
// Observador e Confirmações. Não copia nada dessas páginas: só leva até lá (regra 3, "porta, não sala").
// Usa as globais do app.js ($, chamar, aviso, textoErro, listaVazia do ui.js) e Sofy do sofy.js.
// Texto vindo da TV (nome de app, frase do recado) entra por textContent.
'use strict';

/** O que ela fez, por passo do recado (ObserverCore.Step). */
const PASSOS_SOFY = {
  OBSERVED: 'Só anotei.',
  SUGGESTS: 'É uma sugestão: nada mudou.',
  ACTED_REVERSIBLE: 'Agi sozinha, e dá para desfazer.',
  ACTED_FELT: 'Agi sozinha.',
  QUESTION: 'Preciso que você decida.',
  FAILED: 'Tentei e não consegui.',
};

/** Onde mudar ou entender cada tipo de recado (ObserverCore.Kind): página e, quando existe, o bloco. */
const LUGAR_DO_RECADO = {
  NO_INTERNET: 'avancado', SLOW_INTERNET: 'avancado', UNUSUAL_LATENCY: 'avancado',
  WEAK_WIFI: 'avancado.cartaoWiFiDaTV', UNUSUAL_WIFI: 'avancado.cartaoWiFiDaTV',
  LOW_MEMORY: 'sistema.cartaoQuemMaisOcupaMemoria', MEMORY_PRESSURE: 'sistema.cartaoQuemMaisOcupaMemoria',
  UNUSUAL_MEMORY: 'sistema.cartaoQuemMaisOcupaMemoria',
  SLOW_HOME: 'sistema.cartaoSuaSemanaEDesempenho', UNUSUAL_FRAMES: 'sistema.cartaoSuaSemanaEDesempenho',
  LOW_DISK: 'sistema.cartaoStorage',
  FREEZE_UNDONE: 'sistema.cartaoObservador',
};

/** A cara da Sofy pela gravidade (a mesma regra da faixa da Início). */
const CARA_DA_GRAVIDADE = { CRITICAL: 'SURPRISED', WARNING: 'WORRIED', INFO: 'ATTENTIVE' };

function horaCurta(ms) {
  const d = new Date(ms);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

function linhaRecado(n) {
  const linha = document.createElement('article');
  linha.className = 'recado sev-' + String(n.severity || 'INFO').toLowerCase();
  const quando = document.createElement('span');
  quando.className = 'quando';
  quando.textContent = horaCurta(n.at);
  const texto = document.createElement('div');
  texto.className = 'recado-texto';
  const t = document.createElement('strong');
  t.textContent = n.title;
  const p = document.createElement('p');
  p.textContent = n.text || '';
  const porque = document.createElement('p');
  porque.className = 'aviso-curto';
  porque.textContent = PASSOS_SOFY[n.step] || '';
  texto.append(t, p, porque);
  linha.append(quando, texto);
  const lugar = LUGAR_DO_RECADO[n.kind];
  if (lugar) {
    const ir = document.createElement('button');
    ir.type = 'button';
    ir.className = 'link';
    ir.dataset.ir = lugar;
    ir.textContent = 'Ver onde →';
    linha.appendChild(ir);
  }
  return linha;
}

function linhaProva(t, nomes) {
  const linha = document.createElement('div');
  linha.className = 'linha-registro prova';
  const nome = document.createElement('span');
  nome.className = 'nome';
  nome.textContent = nomes[t.pkg] || t.pkg;
  const quando = document.createElement('span');
  quando.className = 'aviso-curto';
  quando.textContent = horaCurta(t.at);
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'botao-secundario';
  b.textContent = 'Desfazer';
  b.addEventListener('click', async () => {
    b.disabled = true;
    try {
      await chamar('POST', '/api/apps/' + encodeURIComponent(t.pkg) + '/descongelar', {});
      aviso('Descongelado.', 'ok');
      carregarSofy();
    } catch (e) { aviso(textoErro(e), 'erro'); b.disabled = false; }
  });
  linha.append(nome, quando, b);
  return linha;
}

async function carregarSofy() {
  const lista = $('listaRecados');
  let r;
  try { r = await chamar('GET', '/api/observer?lang=' + encodeURIComponent(document.documentElement.lang || 'pt')); }
  catch (e) { lista.replaceChildren(Object.assign(document.createElement('p'), { className: 'aviso', textContent: textoErro(e) })); return; }
  const agora = Date.now();
  const notas = (r.notes || []).filter((n) => n.title && (!n.expiresAt || n.expiresAt > agora)).sort((a, b) => b.at - a.at);
  contarRecados(notas.length);

  // O rosto: a cara do recado mais sério de agora; sem recado, contente.
  const pior = ['CRITICAL', 'WARNING', 'INFO'].find((s) => notas.some((n) => n.severity === s));
  const lugar = $('sofyAbaRosto');
  if (typeof Sofy !== 'undefined' && lugar) {
    try {
      await Sofy.carregar();
      const expr = pior ? CARA_DA_GRAVIDADE[pior] : 'HAPPY';
      let svg = lugar.querySelector('svg');
      if (!svg) { svg = Sofy.criar(expr, { tamanho: 72 }); svg.setAttribute('aria-hidden', 'true'); lugar.appendChild(svg); Sofy.tocar(svg, expr); }
      else if (svg._sofy.expr !== expr) Sofy.tocar(svg, expr);
    } catch (e) { lugar.hidden = true; }   // versão sem a Sofy desenhada: a página segue sem o rosto
  }

  lista.replaceChildren(...notas.map(linhaRecado));
  if (!notas.length) lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nada fora do normal hoje.' }));

  // Congelados em prova: a TV confere em 10 min se algo parou; enquanto isso, a pessoa pode desfazer.
  const provas = r.trials || [];
  $('cartaoEmProva').hidden = !provas.length;
  if (provas.length) {
    // O nome do app: da lista dos Apps (pede uma vez se a página Apps ainda não abriu).
    if (typeof apps !== 'undefined' && !apps.length) { try { apps = (await chamar('GET', '/api/apps')).apps || []; } catch (e) { /* fica o pacote */ } }
    const nomes = {};
    for (const a of (typeof apps !== 'undefined' ? apps : [])) nomes[a.pacote] = a.nome;
    $('listaProvas').replaceChildren(...provas.map((t) => linhaProva(t, nomes)));
  }
}

/** O número ao lado de "Sofy" na barra: quantos recados há agora (some quando não há). */
function contarRecados(n) {
  for (const c of document.querySelectorAll('.conta-sofy')) { c.textContent = String(n); c.hidden = !n; }
}

document.addEventListener('aba', () => { if (paginaAtual === 'sofy') carregarSofy(); });
// A contagem da barra vem também da Início, que lê os mesmos recados para a faixa (carregarFaixaSofy no ui.js).
