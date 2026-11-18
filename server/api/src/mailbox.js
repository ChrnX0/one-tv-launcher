// Caixa postal de UM aparelho (TV ou celular), como Durable Object da Cloudflare.
// Cada id de aparelho tem a sua: a Cloudflare cria perto do primeiro pedido (a casa da pessoa) e guarda num SQLite
// só dela. Os envelopes são opacos (cifrados no aparelho); aqui só se guarda quem mandou, quando e até quando vale.
import { DurableObject } from 'cloudflare:workers';
import { LIMITS, utcDay, TXT_RE } from './rules.js';

const now = () => Math.floor(Date.now() / 1000);

export class Mailbox extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    // Tabelas criadas na 1ª vez. "limits" conta envios por remetente por dia (contra abuso).
    this.sql.exec(`CREATE TABLE IF NOT EXISTS envelopes (id TEXT PRIMARY KEY, sender TEXT, created INTEGER, expires INTEGER, data BLOB);
      CREATE TABLE IF NOT EXISTS limits (sender TEXT, day INTEGER, n INTEGER, PRIMARY KEY (sender, day));
      CREATE TABLE IF NOT EXISTS txt (value TEXT PRIMARY KEY, expires INTEGER);`);
  }

  // Guarda um envelope e, se o aparelho estiver conectado, entrega na hora.
  async deliver(sender, data /* ArrayBuffer */) {
    const t = now();
    if (data.byteLength > LIMITS.envelopeBytes) return { error: 'too large', status: 413 };
    const n = this.sql.exec('SELECT COUNT(*) AS n FROM envelopes').one().n;
    if (n >= LIMITS.envelopesPerInbox) return { error: 'inbox full', status: 507 };
    const day = utcDay(t);
    const used = this.sql.exec('SELECT n FROM limits WHERE sender = ? AND day = ?', sender, day).toArray()[0]?.n ?? 0;
    if (used >= LIMITS.sendsPerDayPerSender) return { error: 'daily limit', status: 429 };
    this.sql.exec('INSERT INTO limits (sender, day, n) VALUES (?, ?, 1) ON CONFLICT (sender, day) DO UPDATE SET n = n + 1', sender, day);
    this.sql.exec('DELETE FROM limits WHERE day < ?', day - 1);

    const id = crypto.randomUUID();
    const expires = t + LIMITS.envelopeTtlSec;
    this.sql.exec('INSERT INTO envelopes VALUES (?, ?, ?, ?, ?)', id, sender, t, expires, data);
    await this.scheduleCleanup(expires);
    for (const ws of this.ctx.getWebSockets()) ws.send(this.message({ id, sender, created: t, data }));
    return { id };
  }

  // Lista o que espera entrega (para quem não usa WebSocket).
  pending() {
    this.deleteExpired();
    return this.sql.exec('SELECT id, sender, created, data FROM envelopes ORDER BY created').toArray()
      .map((e) => ({ id: e.id, from: e.sender, created: e.created, data: base64(e.data) }));
  }

  // O aparelho confirma o que recebeu; só então o envelope é apagado (entregar = apagar).
  ack(ids) {
    for (const id of ids.slice(0, LIMITS.envelopesPerInbox)) this.sql.exec('DELETE FROM envelopes WHERE id = ?', String(id));
  }

  // Só o WebSocket passa por fetch (o resto é chamada direta, RPC).
  async fetch(req) {
    if (new URL(req.url).pathname === '/connect') return this.connect();
    return new Response('unknown route', { status: 404 });
  }

  // Conexão aberta da TV/celular. Hibernável: parada, não custa duração (só conta quando chega mensagem).
  async connect() {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    for (const e of this.pending()) server.send(JSON.stringify(e));
    return new Response(null, { status: 101, webSocket: client });
  }

  // Única mensagem que o aparelho manda pela conexão: {"ack":[ids]}.
  async webSocketMessage(ws, msg) {
    try {
      const { ack } = JSON.parse(typeof msg === 'string' ? msg : new TextDecoder().decode(msg));
      if (Array.isArray(ack)) this.ack(ack);
    } catch { ws.close(1003, 'invalid message'); }
  }

  // O aparelho fechou: responde o fechamento. Códigos reservados (1005, 1006) não podem ser devolvidos, então 1000.
  async webSocketClose(ws) {
    try { ws.close(1000, 'closed'); } catch { /* já fechado */ }
  }

  // Desafio do Let's Encrypt (DNS-01) do certificado desta TV. As máquinas de DNS leem por GET público.
  setTxt(value) {
    if (!TXT_RE.test(value)) return { error: 'invalid value', status: 400 };
    this.deleteExpired();
    // Um certificado pede no máximo 2 desafios (nome e *.nome); 4 dá folga para uma nova tentativa.
    if (this.sql.exec('SELECT COUNT(*) AS n FROM txt').one().n >= 4) return { error: 'too many challenges', status: 429 };
    this.sql.exec('INSERT OR REPLACE INTO txt VALUES (?, ?)', value, now() + LIMITS.txtTtlSec);
    return {};
  }

  deleteTxt() { this.sql.exec('DELETE FROM txt'); }

  readTxt() {
    return this.sql.exec('SELECT value FROM txt WHERE expires > ?', now()).toArray().map((r) => r.value);
  }

  deleteExpired() {
    const t = now();
    this.sql.exec('DELETE FROM envelopes WHERE expires <= ?', t);
    this.sql.exec('DELETE FROM txt WHERE expires <= ?', t);
  }

  async scheduleCleanup(when) {
    const current = await this.ctx.storage.getAlarm();
    if (current == null || current > when * 1000) await this.ctx.storage.setAlarm(when * 1000);
  }

  async alarm() {
    this.deleteExpired();
    const next = this.sql.exec('SELECT MIN(expires) AS m FROM envelopes').one().m;
    if (next) await this.ctx.storage.setAlarm(next * 1000);
  }

  message({ id, sender, created, data }) {
    return JSON.stringify({ id, from: sender, created, data: base64(data) });
  }
}

function base64(data) {
  const bytes = new Uint8Array(data);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
