// Entrar no painel com a digital do celular (ideia 99, 14/11/2026): chave de acesso (passkey, WebAuthn) só pelo
// endereço do Tailscale (https://<nome>.ts.net:8443). Pelo IP de casa o navegador não deixa: a regra do WebAuthn pede
// um nome de domínio, e IP não é. O PIN continua valendo sempre. A conferência é na TV (ChaveAcesso.kt).
// Usa as funções globais do app.js: $, chamar, mostrar, confirmar, PrecisaPin, textoErro, abrirPainel, aviso.
'use strict';

const b64url = {
  de: (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0)),
  para: (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
};
const temWebAuthn = () => !!(window.PublicKeyCredential && navigator.credentials && window.isSecureContext);

/** Nome curto do aparelho para a lista ("Android", "iPhone"…): só para a pessoa saber qual apagar. */
function nomeDoAparelho() {
  const u = navigator.userAgent;
  if (/iPhone/.test(u)) return 'iPhone';
  if (/iPad/.test(u)) return 'iPad';
  if (/Android/.test(u)) return 'Android';
  if (/Mac/.test(u)) return 'Mac';
  if (/Windows/.test(u)) return 'Windows';
  return 'Celular';
}

// Desafio buscado ANTES do toque: o Safari só abre a janela da digital se ela vier logo depois do toque, sem esperar
// a rede no meio. Vale 2 min na TV; aqui guardamos 90 s e buscamos outro depois.
let desafioGuardado = null;
async function pegarDesafio() {
  const d = desafioGuardado;
  desafioGuardado = null;   // uso único: o próximo toque usa outro
  setTimeout(prepararDesafio, 0);
  if (d && Date.now() - d.em < 90000) return d;
  return chamar('POST', '/api/chave/desafio');
}
async function prepararDesafio() {
  try { const d = await pegarDesafio(); d.em = Date.now(); desafioGuardado = d; } catch (_) { desafioGuardado = null; }
}

async function entrarComDigital() {
  const btn = $('btnEntrarDigital');
  btn.disabled = true;
  mostrar($('erroEntrar'), '');
  try {
    const d = await pegarDesafio();
    const cred = await navigator.credentials.get({ publicKey: {
      challenge: b64url.de(d.desafio), rpId: d.rpId, userVerification: 'required', timeout: 60000 } });
    if (!cred) return;
    const r = cred.response;
    await chamar('POST', '/api/chave/entrar', { desafio: d.desafio, id: b64url.para(cred.rawId),
      authData: b64url.para(r.authenticatorData), clientData: b64url.para(r.clientDataJSON), assinatura: b64url.para(r.signature) });
    await abrirPainel();
  } catch (e) {
    // Cancelou na janela da digital (NotAllowedError): sem mensagem.
    if (e && e.name !== 'NotAllowedError') mostrar($('erroEntrar'), textoErro(e));
  } finally {
    btn.disabled = false;
  }
}
$('btnEntrarDigital').addEventListener('click', entrarComDigital);

async function cadastrarDigital() {
  const fazer = async () => {
    const d = await pegarDesafio();
    const cred = await navigator.credentials.create({ publicKey: {
      challenge: b64url.de(d.desafio),
      rp: { id: d.rpId, name: 'One TV' },
      // Um "usuário" só (o painel desta TV): o id não diz nada de ninguém.
      user: { id: new TextEncoder().encode('painel'), name: 'Painel One TV', displayName: 'Painel One TV' },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
      attestation: 'none', timeout: 60000 } });
    if (!cred) return null;
    const r = cred.response;
    if (!r.getPublicKey || !r.getAuthenticatorData) throw new Error('Este navegador é antigo para a digital. Atualize o Chrome ou o Safari.');
    return chamar('POST', '/api/chave/cadastrar', { desafio: d.desafio, id: b64url.para(cred.rawId), nome: nomeDoAparelho(),
      chavePublica: b64url.para(r.getPublicKey()), alg: r.getPublicKeyAlgorithm(),
      authData: b64url.para(r.getAuthenticatorData()), clientData: b64url.para(r.clientDataJSON) });
  };
  try {
    const r = await confirmar('Cadastrar a digital', 'O celular vai pedir a sua digital (ou o rosto, ou o bloqueio de tela). Depois, é só tocar em "Entrar com a digital".', 'Cadastrar', fazer, false);
    if (r) { desenharChaves(r); aviso('Digital cadastrada. Da próxima vez, entre com ela.', 'ok'); }
  } catch (e) {
    if (e && e.name !== 'NotAllowedError') aviso(textoErro(e), 'erro');
  }
}
$('btnCadastrarDigital').addEventListener('click', cadastrarDigital);

function desenharChaves(r) {
  const lista = $('listaDigitais');
  lista.replaceChildren();
  if (!r.disponivel) {
    mostrar($('digitalTexto'), r.endereco
      ? `Funciona só pelo endereço do Tailscale: abra ${r.endereco} neste celular, entre com o PIN e cadastre.`
      : 'Funciona só pelo endereço do Tailscale (com cadeado). Ligue antes a opção "rede Tailscale" na aba Claude (MCP).');
  } else {
    mostrar($('digitalTexto'), temWebAuthn()
      ? 'Entre sem digitar o PIN: o celular confere a sua digital. O PIN continua valendo.'
      : 'Este navegador não sabe usar a digital. Use o Chrome ou o Safari atualizados.');
  }
  $('btnCadastrarDigital').hidden = !(r.disponivel && temWebAuthn());
  for (const c of r.chaves) {
    const linha = document.createElement('div'); linha.className = 'linha-app';
    const nome = document.createElement('span'); nome.textContent = `${c.nome} · desde ${new Date(c.criada).toLocaleDateString()}`;
    const apagar = document.createElement('button'); apagar.type = 'button'; apagar.className = 'botao-perigo botao-pequeno'; apagar.textContent = 'Apagar';
    apagar.addEventListener('click', async () => {
      const res = await confirmar('Apagar esta digital?', `${c.nome}: esse celular volta a entrar só com o PIN.`, 'Apagar',
        () => chamar('POST', '/api/chave/apagar', { id: c.id }));
      if (res) desenharChaves(res);
    });
    linha.append(nome, apagar);
    lista.appendChild(linha);
  }
}

async function carregarChaves() {
  try { const r = await chamar('GET', '/api/chave/lista'); desenharChaves(r); if (r.disponivel && !desafioGuardado) prepararDesafio(); } catch (e) { mostrar($('digitalTexto'), textoErro(e)); }
}

// Tela de entrar: o botão só aparece onde dá certo (endereço do Tailscale, navegador com WebAuthn, digital cadastrada).
(async () => {
  if (!temWebAuthn()) return;
  try {
    const e = await chamar('GET', '/api/chave/estado');
    $('btnEntrarDigital').hidden = !(e.disponivel && e.temChaves);
    if (e.disponivel) prepararDesafio();
  } catch (_) { /* TV fora do ar: a tela de entrar já diz */ }
})();
