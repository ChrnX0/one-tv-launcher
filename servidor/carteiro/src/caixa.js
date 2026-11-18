// Caixa postal de UM aparelho (TV ou celular), como Durable Object da Cloudflare.
// Cada id de aparelho tem a sua: a Cloudflare cria perto do primeiro pedido (a casa da pessoa) e guarda num SQLite
// só dela. Os envelopes são opacos (cifrados no aparelho); aqui só se guarda quem mandou, quando e até quando vale.
import { DurableObject } from 'cloudflare:workers';
import { LIMITES, diaUtc, TXT_RE } from './regras.js';

const agora = () => Math.floor(Date.now() / 1000);

export class Caixa extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    // Tabelas criadas na 1ª vez. "limites" conta envios por remetente por dia (contra abuso).
    this.sql.exec(`CREATE TABLE IF NOT EXISTS envelopes (id TEXT PRIMARY KEY, de TEXT, criado INTEGER, expira INTEGER, dados BLOB);
      CREATE TABLE IF NOT EXISTS limites (de TEXT, dia INTEGER, n INTEGER, PRIMARY KEY (de, dia));
      CREATE TABLE IF NOT EXISTS txt (valor TEXT PRIMARY KEY, expira INTEGER);`);
  }

  // Guarda um envelope e, se o aparelho estiver conectado, entrega na hora.
  async entregar(de, dados /* ArrayBuffer */) {
    const t = agora();
    if (dados.byteLength > LIMITES.envelopeBytes) return { erro: 'grande demais', status: 413 };
    const n = this.sql.exec('SELECT COUNT(*) AS n FROM envelopes').one().n;
    if (n >= LIMITES.envelopesPorCaixa) return { erro: 'caixa cheia', status: 507 };
    const dia = diaUtc(t);
    const usado = this.sql.exec('SELECT n FROM limites WHERE de = ? AND dia = ?', de, dia).toArray()[0]?.n ?? 0;
    if (usado >= LIMITES.enviosPorDiaPorRemetente) return { erro: 'limite do dia', status: 429 };
    this.sql.exec('INSERT INTO limites (de, dia, n) VALUES (?, ?, 1) ON CONFLICT (de, dia) DO UPDATE SET n = n + 1', de, dia);
    this.sql.exec('DELETE FROM limites WHERE dia < ?', dia - 1);

    const id = crypto.randomUUID();
    const expira = t + LIMITES.validadeSeg;
    this.sql.exec('INSERT INTO envelopes VALUES (?, ?, ?, ?, ?)', id, de, t, expira, dados);
    await this.agendarLimpeza(expira);
    for (const ws of this.ctx.getWebSockets()) ws.send(this.mensagem({ id, de, criado: t, dados }));
    return { id };
  }

  // Lista o que espera entrega (para quem não usa WebSocket).
  pendentes() {
    this.apagarVencidos();
    return this.sql.exec('SELECT id, de, criado, dados FROM envelopes ORDER BY criado').toArray()
      .map((e) => ({ id: e.id, de: e.de, criado: e.criado, dados: base64(e.dados) }));
  }

  // O aparelho confirma o que recebeu; só então o envelope é apagado (entregar = apagar).
  confirmar(ids) {
    for (const id of ids.slice(0, LIMITES.envelopesPorCaixa)) this.sql.exec('DELETE FROM envelopes WHERE id = ?', String(id));
  }

  // Só o WebSocket passa por fetch (o resto é chamada direta, RPC).
  async fetch(req) {
    if (new URL(req.url).pathname === '/conectar') return this.conectar();
    return new Response('rota desconhecida', { status: 404 });
  }

  // Conexão aberta da TV/celular. Hibernável: parada, não custa duração (só conta quando chega mensagem).
  async conectar() {
    const [cliente, servidor] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(servidor);
    for (const e of this.pendentes()) servidor.send(JSON.stringify(e));
    return new Response(null, { status: 101, webSocket: cliente });
  }

  // Única mensagem que o aparelho manda pela conexão: {"confirmar":[ids]}.
  async webSocketMessage(ws, msg) {
    try {
      const { confirmar } = JSON.parse(typeof msg === 'string' ? msg : new TextDecoder().decode(msg));
      if (Array.isArray(confirmar)) this.confirmar(confirmar);
    } catch { ws.close(1003, 'mensagem inválida'); }
  }

  // O aparelho fechou: responde o fechamento. Códigos reservados (1005, 1006) não podem ser devolvidos, então 1000.
  async webSocketClose(ws) {
    try { ws.close(1000, 'fechado'); } catch { /* já fechado */ }
  }

  // Desafio do Let's Encrypt (DNS-01) do certificado desta TV. As máquinas de DNS leem por GET público.
  definirTxt(valor) {
    if (!TXT_RE.test(valor)) return { erro: 'valor inválido', status: 400 };
    this.apagarVencidos();
    // Um certificado pede no máximo 2 desafios (nome e *.nome); 4 dá folga para uma nova tentativa.
    if (this.sql.exec('SELECT COUNT(*) AS n FROM txt').one().n >= 4) return { erro: 'desafios demais', status: 429 };
    this.sql.exec('INSERT OR REPLACE INTO txt VALUES (?, ?)', valor, agora() + LIMITES.txtValidadeSeg);
    return {};
  }

  apagarTxt() { this.sql.exec('DELETE FROM txt'); }

  lerTxt() {
    return this.sql.exec('SELECT valor FROM txt WHERE expira > ?', agora()).toArray().map((r) => r.valor);
  }

  apagarVencidos() {
    const t = agora();
    this.sql.exec('DELETE FROM envelopes WHERE expira <= ?', t);
    this.sql.exec('DELETE FROM txt WHERE expira <= ?', t);
  }

  async agendarLimpeza(quando) {
    const atual = await this.ctx.storage.getAlarm();
    if (atual == null || atual > quando * 1000) await this.ctx.storage.setAlarm(quando * 1000);
  }

  async alarm() {
    this.apagarVencidos();
    const proximo = this.sql.exec('SELECT MIN(expira) AS m FROM envelopes').one().m;
    if (proximo) await this.ctx.storage.setAlarm(proximo * 1000);
  }

  mensagem({ id, de, criado, dados }) {
    return JSON.stringify({ id, de, criado, dados: base64(dados) });
  }
}

function base64(dados) {
  const bytes = new Uint8Array(dados);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
