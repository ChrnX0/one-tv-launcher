// Regras puras da API (sem nada da Cloudflare), para rodar nos testes com `node --test`.
// A API só carrega envelopes cifrados: nada aqui olha dentro deles.

export const LIMITS = {
  envelopeBytes: 64 * 1024,          // tamanho máximo de um envelope (recado, comando); vídeo nunca passa aqui
  envelopeTtlSec: 7 * 24 * 3600,     // envelope não entregue some depois de 7 dias
  envelopesPerInbox: 200,            // caixa cheia recusa novos (protege contra quem lota a caixa de alguém)
  sendsPerDayPerSender: 500,         // por par remetente→destino, por dia (UTC)
  clockSkewSec: 300,                 // assinatura vale se o relógio do aparelho estiver até 5 min fora
  pairingTtlSec: 600,                // código de pareamento (TV ↔ celular) vale 10 min e uma vez só
  txtTtlSec: 3600,                   // desafio do Let's Encrypt (DNS-01) some em 1 h
};

// Id de um aparelho = 20 letras base32 do SHA-256 da chave pública Ed25519 (100 bits).
// Base32 minúsculo cabe num nome de DNS, então o mesmo id vira <id>.lan.sofaly.tv no certificado.
const B32 = 'abcdefghijklmnopqrstuvwxyz234567';
export const ID_RE = /^[a-z2-7]{20}$/;

export function base32(bytes) {
  let bits = 0, value = 0, out = '';
  for (const b of bytes) {
    value = (value << 8) | b; bits += 8;
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export async function idFromKey(publicKey /* Uint8Array(32) */) {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', publicKey));
  return base32(hash).slice(0, 20);
}

// Código de pareamento: 8 letras sem as que se confundem na TV (0/O, 1/I/L). Digitado no celular ou lido no QR.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const CODE_RE = /^[A-HJKMNP-Z2-9]{8}$/;

export function newCode(random = (n) => crypto.getRandomValues(new Uint8Array(n))) {
  let code = '';
  // Rejeita bytes do fim da faixa para não favorecer letras (31 símbolos não divide 256).
  const limit = 256 - (256 % CODE_ALPHABET.length);
  while (code.length < 8) {
    for (const b of random(16)) {
      if (b < limit && code.length < 8) code += CODE_ALPHABET[b % CODE_ALPHABET.length];
    }
  }
  return code;
}

// Texto que o aparelho assina. Muda o método, o caminho, a hora ou o corpo = assinatura não confere.
export async function signedText(method, path, timeSec, body /* Uint8Array */) {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', body));
  const hex = [...hash].map((b) => b.toString(16).padStart(2, '0')).join('');
  return new TextEncoder().encode(`${method}\n${path}\n${timeSec}\n${hex}`);
}

export function fromBase64url(text) {
  const b64 = text.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

// Confere os cabeçalhos X-Sofaly-*. Devolve { id } de quem assinou ou { error }.
export async function verifySignature({ method, path, headers, body, nowSec }) {
  const key = headers.get('x-sofaly-key');
  const time = Number(headers.get('x-sofaly-time'));
  const signature = headers.get('x-sofaly-signature');
  if (!key || !signature || !Number.isInteger(time)) return { error: 'missing signature' };
  if (Math.abs(nowSec - time) > LIMITS.clockSkewSec) return { error: 'clock skew' };
  let keyBytes, sigBytes;
  try { keyBytes = fromBase64url(key); sigBytes = fromBase64url(signature); } catch { return { error: 'invalid base64' }; }
  if (keyBytes.length !== 32 || sigBytes.length !== 64) return { error: 'invalid length' };
  const publicKey = await crypto.subtle.importKey('raw', keyBytes, { name: 'Ed25519' }, false, ['verify']);
  const text = await signedText(method, path, time, body);
  const ok = await crypto.subtle.verify({ name: 'Ed25519' }, publicKey, sigBytes, text);
  if (!ok) return { error: 'bad signature' };
  return { id: await idFromKey(keyBytes), key };
}

// Valor do TXT do desafio DNS-01: base64url de 43 letras (SHA-256), é o formato que o Let's Encrypt manda.
export const TXT_RE = /^[A-Za-z0-9_-]{43}$/;

export function utcDay(nowSec) {
  return Math.floor(nowSec / 86400);
}
