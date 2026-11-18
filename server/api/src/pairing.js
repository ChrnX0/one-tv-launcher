// Pareamento TV ↔ celular por código curto (um Durable Object por código).
// A TV pede um código e mostra na tela (texto + QR). O celular manda o código + um envelope de apresentação
// (a chave pública dele, cifrada ou não, decisão do app). A API só junta os dois ids: a troca de chaves de ponta a
// ponta acontece nos aparelhos, por envelopes. Código vale 10 min e uma vez só.
import { DurableObject } from 'cloudflare:workers';
import { LIMITS } from './rules.js';

export class Pairing extends DurableObject {
  async open(tvId) {
    const current = await this.ctx.storage.get('tv');
    if (current) return { error: 'code in use', status: 409 }; // colisão rara: o Worker sorteia outro
    const expires = Date.now() + LIMITS.pairingTtlSec * 1000;
    await this.ctx.storage.put({ tv: tvId, expires });
    await this.ctx.storage.setAlarm(expires);
    return {};
  }

  // Devolve o id da TV e encerra o código (uso único).
  async use() {
    const { tv, expires } = Object.fromEntries(await this.ctx.storage.get(['tv', 'expires']));
    await this.ctx.storage.deleteAll();
    if (!tv || Date.now() > expires) return { error: 'invalid or expired code', status: 404 };
    return { tv };
  }

  async alarm() { await this.ctx.storage.deleteAll(); }
}
