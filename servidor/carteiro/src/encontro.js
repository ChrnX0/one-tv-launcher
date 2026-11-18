// Ponto de encontro TV ↔ celular por código curto (um Durable Object por código).
// A TV pede um código e mostra na tela (texto + QR). O celular manda o código + um envelope de apresentação
// (a chave pública dele, cifrada ou não, decisão do app). O carteiro só junta os dois ids: a troca de chaves de ponta a
// ponta acontece nos aparelhos, por envelopes. Código vale 10 min e uma vez só.
import { DurableObject } from 'cloudflare:workers';
import { LIMITES } from './regras.js';

export class Encontro extends DurableObject {
  async abrir(tvId) {
    const atual = await this.ctx.storage.get('tv');
    if (atual) return { erro: 'código em uso', status: 409 }; // colisão rara: o Worker sorteia outro
    const expira = Date.now() + LIMITES.encontroValidadeSeg * 1000;
    await this.ctx.storage.put({ tv: tvId, expira });
    await this.ctx.storage.setAlarm(expira);
    return {};
  }

  // Devolve o id da TV e encerra o código (uso único).
  async usar() {
    const { tv, expira } = Object.fromEntries(await this.ctx.storage.get(['tv', 'expira']));
    await this.ctx.storage.deleteAll();
    if (!tv || Date.now() > expira) return { erro: 'código inválido ou vencido', status: 404 };
    return { tv };
  }

  async alarm() { await this.ctx.storage.deleteAll(); }
}
