// Regras puras do carteiro (sem nada da Cloudflare), para rodar nos testes com `node --test`.
// O carteiro só carrega envelopes cifrados: nada aqui olha dentro deles.

export const LIMITES = {
  envelopeBytes: 64 * 1024,          // tamanho máximo de um envelope (recado, comando); vídeo nunca passa aqui
  validadeSeg: 7 * 24 * 3600,        // envelope não entregue some depois de 7 dias
  envelopesPorCaixa: 200,            // caixa cheia recusa novos (protege contra quem lota a caixa de alguém)
  enviosPorDiaPorRemetente: 500,     // por par remetente→destino, por dia (UTC)
  folgaRelogioSeg: 300,              // assinatura vale se o relógio do aparelho estiver até 5 min fora
  encontroValidadeSeg: 600,          // código de encontro (TV ↔ celular) vale 10 min e uma vez só
  txtValidadeSeg: 3600,              // desafio do Let's Encrypt (DNS-01) some em 1 h
};

// Id de um aparelho = 20 letras base32 do SHA-256 da chave pública Ed25519 (100 bits).
// Base32 minúsculo cabe num nome de DNS, então o mesmo id vira <id>.lan.sofaly.tv no certificado.
const B32 = 'abcdefghijklmnopqrstuvwxyz234567';
export const ID_RE = /^[a-z2-7]{20}$/;

export function base32(bytes) {
  let bits = 0, valor = 0, saida = '';
  for (const b of bytes) {
    valor = (valor << 8) | b; bits += 8;
    while (bits >= 5) { saida += B32[(valor >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) saida += B32[(valor << (5 - bits)) & 31];
  return saida;
}

export async function idDaChave(chavePublica /* Uint8Array(32) */) {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', chavePublica));
  return base32(hash).slice(0, 20);
}

// Código de encontro: 8 letras sem as que se confundem na TV (0/O, 1/I/L). Digitado no celular ou lido no QR.
const ALFABETO_CODIGO = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const CODIGO_RE = /^[A-HJKMNP-Z2-9]{8}$/;

export function novoCodigo(aleatorio = (n) => crypto.getRandomValues(new Uint8Array(n))) {
  let codigo = '';
  // Rejeita bytes do fim da faixa para não favorecer letras (31 símbolos não divide 256).
  const limite = 256 - (256 % ALFABETO_CODIGO.length);
  while (codigo.length < 8) {
    for (const b of aleatorio(16)) {
      if (b < limite && codigo.length < 8) codigo += ALFABETO_CODIGO[b % ALFABETO_CODIGO.length];
    }
  }
  return codigo;
}

// Texto que o aparelho assina. Muda o método, o caminho, a hora ou o corpo = assinatura não confere.
export async function textoAssinado(metodo, caminho, horaSeg, corpo /* Uint8Array */) {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', corpo));
  const hex = [...hash].map((b) => b.toString(16).padStart(2, '0')).join('');
  return new TextEncoder().encode(`${metodo}\n${caminho}\n${horaSeg}\n${hex}`);
}

export function b64url(texto) {
  const b64 = texto.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

// Confere os cabeçalhos X-Sofaly-*. Devolve { id } de quem assinou ou { erro }.
export async function conferirAssinatura({ metodo, caminho, cabecalhos, corpo, agoraSeg }) {
  const chave = cabecalhos.get('x-sofaly-chave');
  const hora = Number(cabecalhos.get('x-sofaly-hora'));
  const assinatura = cabecalhos.get('x-sofaly-assinatura');
  if (!chave || !assinatura || !Number.isInteger(hora)) return { erro: 'sem assinatura' };
  if (Math.abs(agoraSeg - hora) > LIMITES.folgaRelogioSeg) return { erro: 'relógio fora' };
  let chaveBytes, assinaturaBytes;
  try { chaveBytes = b64url(chave); assinaturaBytes = b64url(assinatura); } catch { return { erro: 'base64 inválido' }; }
  if (chaveBytes.length !== 32 || assinaturaBytes.length !== 64) return { erro: 'tamanho inválido' };
  const publica = await crypto.subtle.importKey('raw', chaveBytes, { name: 'Ed25519' }, false, ['verify']);
  const texto = await textoAssinado(metodo, caminho, hora, corpo);
  const ok = await crypto.subtle.verify({ name: 'Ed25519' }, publica, assinaturaBytes, texto);
  if (!ok) return { erro: 'assinatura não confere' };
  return { id: await idDaChave(chaveBytes), chave };
}

// Valor do TXT do desafio DNS-01: base64url de 43 letras (SHA-256), é o formato que o Let's Encrypt manda.
export const TXT_RE = /^[A-Za-z0-9_-]{43}$/;

export function diaUtc(agoraSeg) {
  return Math.floor(agoraSeg / 86400);
}
