// Seus atalhos, "Conversa com" e Desfazer (PR 4 do estudo "painel com a identidade Sofaly", 20/11/2026). Regras:
//   4. Cada coisa diz com o que conversa: no pé dos blocos ligados entre si, "Conversa com" e um link para cada um.
//   6. Seu controle, seu jeito: a estrela de um bloco ou de uma chave fixa ele em "Seus atalhos" na Início, com o
//      interruptor ali mesmo. A lista fica na TV (/api/config, campo "atalhos"; PanelShortcuts.kt), 4 de fábrica.
//   Desfazer na própria página: mudar uma chave mostra um aviso com "Desfazer" (app.js faz o mesmo nas opções).
// Usa as globais do app.js ($, chamar, irPara, gravarConfig, paginaAtual) e linhaChave/NOMES_CHAVES do extras.js.
// Texto vindo da página entra por textContent; a tradução (i18n.js) já trocou os títulos antes de serem copiados.
'use strict';

// ---------- "Conversa com" ----------

// Grupos de blocos que mexem um no outro: o primeiro conversa com cada um dos outros, nos dois sentidos.
// Só ids de blocos que existem no index.html (PainelRelacoesTest confere; bloco que sair quebra o teste, não a tela).
const RELACOES = [
  ['cartaoDesligar', 'cartaoHoraDeDormir', 'cartaoPresenca', 'cartaoTelaEEnergia'],
  ['cartaoFiltroNoturno', 'cartaoHoraDeDormir', 'cartaoTelaEEnergia'],
  ['cartaoHoraDeDormir', 'cartaoFamilia'],
  ['cartaoFamilia', 'cartaoCuidar', 'cartaoConfirmacoes'],
  ['cartaoCartoesHome', 'cartaoTelaInicial', 'cartaoAniversarios'],
  ['cartaoAniversarios', 'cartaoMural', 'cartaoTelaEEnergia'],
  ['cartaoMural', 'cartaoFotosDoProtetorDeTela', 'cartaoRecado'],
  ['cartaoFotosDoProtetorDeTela', 'cartaoTelaEEnergia'],
  ['cartaoTelaInicial', 'cartaoClima', 'cartaoModoSimples'],
  ['cartaoTVEFilmes', 'cartaoServicos', 'cartaoAddons'],
  ['cartaoRadios', 'cartaoPodcasts'],
  ['cartaoPresenca', 'cartaoCasa', 'cartaoAvisosNoCelular'],
  ['cartaoCasa', 'cartaoCenas'],
  ['cartaoPINDoPainel', 'cartaoOutrasPessoas', 'cartaoDigital', 'cartaoLoginPelaContaGoogle', 'cartaoConfirmacoes'],
  ['cartaoOutrasPessoas', 'cartaoAcessos'],
  ['cartaoClaudePelaInternetMCP', 'cartaoRedeTailscale', 'cartaoConectarUmaIAATV'],
  ['cartaoUnidadeDeRede', 'cartaoStorage', 'cartaoInstalarComoAppHttps'],
  ['cartaoObservador', 'cartaoDiarioDaTV', 'cartaoComoATVEsta'],
  ['cartaoComoATVEsta', 'cartaoManutencao', 'cartaoQuemMaisOcupaMemoria'],
  ['cartaoBackupCompletoCifrado', 'cartaoBaixarBackup', 'cartaoBackupNoturnoCifrado', 'cartaoRestaurar', 'cartaoDriveRestaurar', 'cartaoMudarTv'],
  ['cartaoRoteadorUniFi', 'cartaoCertificadoDoRoteador'],
  ['cartaoConfirmacoes', 'cartaoShellEmprestadoShizuku', 'cartaoControleDeDonoEmprestadoDhizuku'],
];

function vizinhos() {
  const m = {};
  const liga = (a, b) => { (m[a] = m[a] || []).includes(b) || m[a].push(b); };
  for (const [a, ...resto] of RELACOES) for (const b of resto) { liga(a, b); liga(b, a); }
  return m;
}

/** O nome que o link mostra: o título do bloco e, se mora em outra página, o nome dela ("Hora de dormir · Família"). */
function nomeDoBloco(cartao, deOnde) {
  const t = cartao.querySelector(':scope > h2')?.textContent.trim() || '';
  const pagina = cartao.closest('.painel-aba');
  if (!pagina || pagina === deOnde) return t;
  return t + ' · ' + (pagina.querySelector('.titulo-pagina')?.textContent.trim() || '');
}

function montarConversas() {
  document.querySelectorAll('.conversa-com').forEach((e) => e.remove());
  for (const [id, outros] of Object.entries(vizinhos())) {
    const cartao = $(id);
    if (!cartao) continue;
    const alvos = outros.map((o) => $(o)).filter((c) => c && !c.hidden);
    if (!alvos.length) continue;
    const p = document.createElement('p');
    p.className = 'conversa-com';
    const r = document.createElement('span');
    r.className = 'rotulo';
    r.textContent = 'Conversa com';
    p.appendChild(r);
    for (const c of alvos) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'link';
      b.dataset.ir = 'x.' + c.id;
      b.textContent = nomeDoBloco(c, cartao.closest('.painel-aba'));
      p.appendChild(b);
    }
    cartao.appendChild(p);
  }
}

// ---------- estrelas ----------

let atalhos = null;   // a lista da TV ("chave:X", "config:x", "bloco:id"); null = ainda não leu

function svgEstrela() {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('class', 'i');
  s.setAttribute('aria-hidden', 'true');
  const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  u.setAttribute('href', '#i-estrela');
  s.appendChild(u);
  return s;
}

function pintarEstrela(b) {
  const fixado = !!atalhos && atalhos.includes(b.dataset.atalho);
  b.setAttribute('aria-pressed', String(fixado));
  b.setAttribute('aria-label', fixado ? 'Tirar da Início' : 'Fixar na Início');
  b.title = b.getAttribute('aria-label');
}

function botaoEstrela(atalho) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'estrela';
  b.dataset.atalho = atalho;
  b.appendChild(svgEstrela());
  b.addEventListener('click', (ev) => { ev.preventDefault(); ev.stopPropagation(); alternarAtalho(atalho); });
  pintarEstrela(b);
  return b;
}

/** Põe a estrela em todo bloco com id (menos os da Início) e em toda linha marcada com data-atalho (chaves, opções). */
function porEstrelas() {
  for (const h of document.querySelectorAll('.painel-aba:not(#abaInicio):not(#abaTudo):not(#abaSofy) .cartao[id] > h2')) {
    // Só id escrito no index.html: o "bloco-..." que o navigation.js gera muda se a página ganhar um bloco.
    if (h.parentElement.id.startsWith('bloco-') || h.parentElement.parentElement.closest('.cartao') || h.querySelector('.estrela')) continue;
    h.appendChild(botaoEstrela('bloco:' + h.parentElement.id));
  }
  for (const l of document.querySelectorAll('[data-atalho]:not(.estrela)')) {
    if (!l.querySelector(':scope > .estrela')) l.appendChild(botaoEstrela(l.dataset.atalho));
  }
  document.querySelectorAll('.estrela').forEach(pintarEstrela);
}

async function alternarAtalho(atalho) {
  if (!atalhos) atalhos = (await chamar('GET', '/api/config')).atalhos || [];
  const tinha = atalhos.includes(atalho);
  const nova = tinha ? atalhos.filter((a) => a !== atalho) : [...atalhos, atalho];
  try {
    const r = await chamar('POST', '/api/config', { campo: 'atalhos', valor: nova });
    atalhos = r.atalhos || nova;
    aviso(tinha ? 'Saiu da Início.' : 'Fixado na Início.', 'ok');
  } catch (e) { aviso(textoErro(e), 'erro'); }
  porEstrelas();
  desenharAtalhos();
}

// ---------- Seus atalhos (Início) ----------

/** Nome de uma opção do /api/config: o texto da linha dela na página (sem a estrela). */
function nomeDaOpcao(campo) {
  const l = document.querySelector(`[data-atalho="config:${campo}"]:not(.linha-chave):not(.estrela)`);
  if (!l) return null;
  const c = l.cloneNode(true);
  c.querySelectorAll('.estrela, .aviso-curto').forEach((e) => e.remove());
  return c.textContent.replace(/\s*\(.*\)\s*$/, '').trim();
}

function linhaOpcao(campo, ligado) {
  const rotulo = document.createElement('label');
  rotulo.className = 'linha-chave';
  rotulo.dataset.atalho = 'config:' + campo;
  const caixa = document.createElement('input');
  caixa.type = 'checkbox';
  caixa.setAttribute('role', 'switch');
  caixa.checked = ligado;
  const estado = document.createElement('span');
  estado.className = 'estado-chave';
  const pintar = () => { estado.textContent = caixa.checked ? 'Ligado' : 'Desligado'; estado.classList.toggle('ligado', caixa.checked); };
  pintar();
  const nome = document.createElement('span');
  nome.className = 'nome-chave';
  nome.textContent = nomeDaOpcao(campo) || campo;
  // O interruptor é o mesmo da página: muda a caixa original e deixa o código dela gravar (PIN, aviso, Desfazer).
  caixa.addEventListener('change', () => {
    pintar();
    const original = document.querySelector(`[data-atalho="config:${campo}"]:not(.linha-chave) input[type=checkbox]`);
    if (!original) { caixa.checked = !caixa.checked; pintar(); return; }
    original.checked = caixa.checked;
    original.dispatchEvent(new Event('change'));
  });
  rotulo.append(nome, estado, caixa);
  return rotulo;
}

function linhaBloco(cartao) {
  const linha = document.createElement('div');
  linha.className = 'linha-atalho';
  linha.dataset.atalho = 'bloco:' + cartao.id;   // a estrela entra ao lado (porEstrelas), para tirar daqui mesmo
  const b = document.createElement('button');
  b.type = 'button';
  b.dataset.ir = 'x.' + cartao.id;
  const t = document.createElement('span');
  t.textContent = nomeDoBloco(cartao, null);
  const seta = document.createElement('span');
  seta.setAttribute('aria-hidden', 'true');
  seta.textContent = '→';
  b.append(t, seta);
  linha.appendChild(b);
  return linha;
}

let desenhando = 0;
async function desenharAtalhos() {
  const lista = $('listaAtalhos');
  if (!lista) return;
  const vez = ++desenhando;
  let config, extras;
  try {
    [config, extras] = await Promise.all([chamar('GET', '/api/config'), chamar('GET', '/api/extras/estado').catch(() => null)]);
  } catch (e) { return; }
  if (vez !== desenhando) return;   // outro desenho começou depois
  atalhos = config.atalhos || [];
  const linhas = [];
  for (const a of atalhos) {
    const [tipo, nome] = a.split(':');
    if (tipo === 'chave' && extras && nome in extras.chaves) linhas.push(linhaChave(nome, extras.chaves[nome], extras));
    else if (tipo === 'config' && typeof config[nome] === 'boolean') linhas.push(linhaOpcao(nome, config[nome]));
    else if (tipo === 'bloco' && $(nome)) linhas.push(linhaBloco($(nome)));
    // o resto (chave que a TV não tem mais, bloco que saiu) fica guardado e não aparece
  }
  lista.replaceChildren(...linhas);
  $('vazioAtalhos').hidden = linhas.length > 0;
  porEstrelas();
}

// ---------- Desfazer ----------

let desfazendo = false;

/**
 * Aviso de uma mudança feita, com "Desfazer" (8 s). [nome] é o que mudou (ou a frase pronta, com [ligado] null);
 * [desfazer] volta como era, pelo mesmo caminho (o aviso da volta é só "Desfeito.").
 */
function avisoDesfazer(nome, ligado, desfazer) {
  if (desfazendo) { desfazendo = false; aviso('Desfeito.', 'ok'); return; }
  const t = document.createElement('div');
  t.className = 'toast ok toast-desfazer';
  t.setAttribute('role', 'status');
  const n = document.createElement('strong');
  n.textContent = ligado === null ? nome : nome.replace(/\s*\(.*\)\s*$/, '');   // o nome curto da chave, sem a explicação
  const e = document.createElement('span');
  if (ligado !== null) e.textContent = ligado ? 'Ligado na TV.' : 'Desligado na TV.';
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'link';
  b.textContent = 'Desfazer';
  const fechar = () => { t.classList.remove('visivel'); setTimeout(() => t.remove(), 300); };
  b.addEventListener('click', () => { fechar(); desfazendo = true; desfazer(); });
  t.append(n, e, b);
  $('toasts').appendChild(t);
  requestAnimationFrame(() => requestAnimationFrame(() => t.classList.add('visivel')));
  setTimeout(fechar, 8000);
}

// ---------- quando montar ----------

let esperaAtalhos = 0;
new MutationObserver((ms) => {
  // Só o que os outros scripts criam (linhas de chave, cartões novos); as próprias estrelas e conversas não contam.
  if (ms.every((m) => [...m.addedNodes].every((n) => n.nodeType !== 1 || n.matches('.estrela, .conversa-com, .toast')))) return;
  clearTimeout(esperaAtalhos);
  esperaAtalhos = setTimeout(porEstrelas, 150);
}).observe($('telaPainel') || document.body, { childList: true, subtree: true });

document.addEventListener('aba', () => { if (paginaAtual !== 'inicio') montarConversas(); });
montarConversas();
porEstrelas();
// A Início desenha os atalhos junto com o resto dela (carregarInicio no ui.js, que só roda com o painel aberto).
