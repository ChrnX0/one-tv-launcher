// Primeira configuração pelo celular (ideia 97, 14/11/2026). A TV sem PIN mostra um QR (EntrarPeloQrActivity) que abre
// "/#primeira=<código>". Aqui, 5 passos, um por vez e com letra grande (quem usa nem sempre é de tecnologia):
//   1. PIN: com o código do QR, cria o PIN e já entra (POST /api/primeira; PrimeiraConfig.kt). Sem volta: depois do
//      PIN, o resto usa a sessão normal do painel e as mesmas rotas das outras abas.
//   2. Idioma: o launcher segue o idioma do Android (nunca um fixo); o botão abre na TV a tela de idioma do sistema.
//      E o áudio preferido em filmes e séries (o mesmo campo "audio" da aba Configurações).
//   3. TV ao vivo: a conta de canais (/api/tv/contas, a mesma da aba TV).
//   4. Cidade: /api/clima, a mesma da aba Configurações.
//   5. Serviços: o cartão de Serviços da aba Configurações vem emprestado para cá e volta no fim.
// Usa as funções globais do app.js: $, chamar, mostrar, confirmar, PrecisaPin, textoErro, abrirPainel, carregarServicos.
'use strict';

const PASSOS_PRIMEIRA = [
  { id: 'pin', titulo: 'Crie um PIN' },
  { id: 'idioma', titulo: 'Idioma' },
  { id: 'tv', titulo: 'TV ao vivo', pular: true },
  { id: 'cidade', titulo: 'Sua cidade', pular: true },
  { id: 'servicos', titulo: 'Filmes e séries', fim: true },
];
const primeira = { codigo: '', passo: 0, servicosDe: null };

/** Tira "#primeira=<código>" do endereço (não fica no histórico) e devolve o código, ou '' se não é este caso. */
function pegarCodigoPrimeira() {
  if (!location.hash.startsWith('#primeira=')) return '';
  const m = /^#primeira=([0-9a-f]{48})(?:&(?:fp|ts)=[a-z0-9.-]*)*$/.exec(location.hash);   // fp/ts: para o app de celular
  history.replaceState(null, '', location.pathname + location.search);
  return m ? m[1] : '';
}

/** Chamada que pode pedir o PIN de novo (a sessão "elevada" dura 5 min): o modal pede e repete. */
async function comPinPrimeira(fazer) {
  try { return await fazer(); } catch (e) {
    if (!(e instanceof PrecisaPin)) throw e;
    const r = await confirmar('Confirme o PIN', 'Por segurança, digite o PIN que você acabou de criar.', 'Continuar', fazer, false);
    if (r === null) throw new Error('');   // cancelou: fica no mesmo passo, sem mensagem
    return r;
  }
}

function mostrarPassoPrimeira() {
  const p = PASSOS_PRIMEIRA[primeira.passo];
  $('passoPrimeira').textContent = `Passo ${primeira.passo + 1} de ${PASSOS_PRIMEIRA.length}`;
  $('tituloPrimeira').textContent = p.titulo;
  for (const el of document.querySelectorAll('#formPrimeira [data-passo]')) el.hidden = el.dataset.passo !== p.id;
  mostrar($('erroPrimeira'), '');
  $('btnPrimeiraOk').textContent = p.fim ? 'Concluir' : 'Continuar';
  $('btnPrimeiraPular').hidden = !p.pular;
  $('btnPrimeiraVoltar').hidden = primeira.passo < 2;   // depois do PIN criado, não volta para ele
  if (p.id === 'pin') $('primeiraPin').focus();
  if (p.id === 'servicos') emprestarServicos();
}

async function prepararPassos() {
  // O que a TV já tem (idioma do Android, áudio, cidade, contas): cada passo mostra antes de perguntar.
  const e = await chamar('GET', '/api/primeira/tv');
  $('primeiraIdioma').textContent = e.idiomaTv;
  for (const r of document.querySelectorAll('input[name=primeiraAudio]')) r.checked = r.value === e.audio;
  mostrar($('primeiraContas'), e.contasTv ? `A TV já tem ${e.contasTv} conta(s). Pode pular ou pôr outra.` : '');
  mostrar($('primeiraCidadeAtual'), e.cidade ? `Agora: ${e.cidade}` : '');
}

function ajustarTvPrimeira() {
  const xt = $('primeiraTvTipo').value === 'xtream';
  $('primeiraTvEndereco').placeholder = xt ? 'Servidor (ex.: http://servidor:8080)' : 'Link da lista (http:// ou https://)';
  $('primeiraTvUsuario').hidden = !xt;
  $('primeiraTvSenha').hidden = !xt;
}
$('primeiraTvTipo').addEventListener('change', ajustarTvPrimeira);

/** O cartão de Serviços da aba Configurações vem para o assistente (mesmos botões de entrar, QR e código). */
function emprestarServicos() {
  const c = $('cartaoServicos');
  if (!primeira.servicosDe) primeira.servicosDe = { pai: c.parentNode, depois: c.nextSibling };
  $('primeiraServicos').appendChild(c);
  carregarServicos();
}
function devolverServicos() {
  const d = primeira.servicosDe;
  if (d) d.pai.insertBefore($('cartaoServicos'), d.depois);
  primeira.servicosDe = null;
}

/** Grava o passo atual. true = pode ir para o próximo. */
async function gravarPassoPrimeira() {
  const id = PASSOS_PRIMEIRA[primeira.passo].id;
  if (id === 'pin') {
    const pin = $('primeiraPin').value;
    if (!pinValido(pin)) { mostrar($('erroPrimeira'), 'O PIN precisa ter de 6 a 12 números.'); return false; }
    if (pin !== $('primeiraPin2').value) { mostrar($('erroPrimeira'), 'Os dois PINs não são iguais. Digite de novo.'); $('primeiraPin2').value = ''; return false; }
    try {
      await chamar('POST', '/api/primeira', { codigo: primeira.codigo, pin });
    } catch (e) {
      // TV que já tem PIN: este caminho fecha; vai para a entrada normal.
      if (e.dados && /já tem PIN/.test(e.tecnico || '')) { $('telaPrimeira').hidden = true; mostrarEntrar('Esta TV já tem PIN: entre com ele.'); return false; }
      throw e;
    }
    $('primeiraPin').value = ''; $('primeiraPin2').value = '';
    await prepararPassos();
    return true;
  }
  if (id === 'idioma') {
    const r = document.querySelector('input[name=primeiraAudio]:checked');
    if (r) await comPinPrimeira(() => chamar('POST', '/api/config', { campo: 'audio', valor: r.value }));
    return true;
  }
  if (id === 'tv') {
    const tipo = $('primeiraTvTipo').value;
    const corpo = { nome: 'TV', tipo, endereco: $('primeiraTvEndereco').value.trim(),
      usuario: tipo === 'xtream' ? $('primeiraTvUsuario').value.trim() : '', senha: tipo === 'xtream' ? $('primeiraTvSenha').value : '' };
    if (!corpo.endereco || (tipo === 'xtream' && (!corpo.usuario || !corpo.senha))) {
      mostrar($('erroPrimeira'), tipo === 'xtream' ? 'Preencha o servidor, o usuário e a senha, ou toque em Pular.' : 'Cole o link da lista, ou toque em Pular.');
      return false;
    }
    await comPinPrimeira(() => chamar('POST', '/api/tv/contas', corpo));
    $('primeiraTvSenha').value = '';
    return true;
  }
  if (id === 'cidade') {
    const cidade = $('primeiraCidade').value.trim();
    if (!cidade) { mostrar($('erroPrimeira'), 'Digite a cidade, ou toque em Pular.'); return false; }
    await chamar('POST', '/api/clima', { cidade });
    return true;
  }
  return true;   // serviços: cada botão do cartão já grava sozinho
}

async function terminarPrimeira() {
  devolverServicos();
  $('telaPrimeira').hidden = true;
  await abrirPainel();
  aviso('Pronto! A TV está configurada. O resto fica nas abas do painel.', 'ok');
}

$('formPrimeira').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const btn = $('btnPrimeiraOk');
  btn.disabled = true;
  mostrar($('erroPrimeira'), '');
  try {
    if (!await gravarPassoPrimeira()) return;
    if (PASSOS_PRIMEIRA[primeira.passo].fim) { await terminarPrimeira(); return; }
    primeira.passo++;
    mostrarPassoPrimeira();
  } catch (e) {
    mostrar($('erroPrimeira'), textoErro(e));
  } finally {
    btn.disabled = false;
  }
});
$('btnPrimeiraPular').addEventListener('click', () => {
  if (PASSOS_PRIMEIRA[primeira.passo].fim) { terminarPrimeira(); return; }
  primeira.passo++;
  mostrarPassoPrimeira();
});
$('btnPrimeiraVoltar').addEventListener('click', () => {
  if (primeira.passo < 2) return;
  if (PASSOS_PRIMEIRA[primeira.passo].id === 'servicos') devolverServicos();
  primeira.passo--;
  mostrarPassoPrimeira();
});
$('btnIdiomaTv').addEventListener('click', async () => {
  try {
    await chamar('POST', '/api/primeira/idioma-tv');
    aviso('A tela de idioma abriu na TV. Escolha com o controle.', 'ok');
  } catch (e) { mostrar($('erroPrimeira'), textoErro(e)); }
});

function iniciarPrimeira(codigo) {
  primeira.codigo = codigo;
  primeira.passo = 0;
  $('telaEntrar').hidden = true;
  $('telaPainel').hidden = true;
  $('telaPrimeira').hidden = false;
  ajustarTvPrimeira();
  mostrarPassoPrimeira();
}

(() => {
  const c = pegarCodigoPrimeira();
  if (c) iniciarPrimeira(c);
  // O leitor de QR pode abrir o link numa aba já aberta no painel: aí só o fragmento muda.
  window.addEventListener('hashchange', () => { const n = pegarCodigoPrimeira(); if (n) iniciarPrimeira(n); });
})();
