// Texto da fala gerado na nuvem (proposta C do Ouvir, 21/11/2026). Rotas públicas:
//   POST /v1/transcribe?lang=pt     corpo = um pedaço de MP3 em Base64 (texto puro, até ~2,6 MB = ~2 min de áudio)
//                                    resposta: {"segments":[{"start":0.0,"end":3.2,"text":"…"}]} (tempos no pedaço)
//   POST /v1/summary?lang=pt        corpo = {"title":"…","text":"…"} (até 24 mil letras) → {"summary":"…","ads":[[ini,fim],…]}
// Por quê: só uma parte dos podcasts publica a transcrição (podcast:transcript); a TV pede a dos outros aqui.
// Grátis de verdade: Workers AI no plano Free dá 10.000 neurônios por dia e PARA quando acaba (não cobra). O Whisper
// turbo custa US$ 0,000513 por minuto de áudio = ~47 neurônios/min, então ~3,5 h de áudio por dia para todas as TVs.
// A TV também tem um teto próprio por dia (TranscricaoNuvem.kt). Acabou a cota: 429, a TV diz "volte amanhã".
// Por que o Base64 já vem pronto da TV: no plano Free o Worker tem 10 ms de CPU por pedido; codificar 2 MB aqui
// gastaria isso. Assim o corpo passa direto para o modelo.
// Privacidade: só áudio de podcast público e o texto dele; nada da casa. Workers AI não guarda o pedido (zero retention).

export const MAX_B64 = 2_800_000;   // ~2 MB de MP3
export const MAX_TEXT = 24_000;
const WHISPER = '@cf/openai/whisper-large-v3-turbo';
const LLM = '@cf/meta/llama-3.1-8b-instruct-fast';

const reply = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
});

export function cleanLang(lang) {
  const l = String(lang ?? '').trim().toLowerCase().slice(0, 2);
  return /^[a-z]{2}$/.test(l) ? l : '';
}

/** Base64 válido (o que o modelo aceita): só o alfabeto e o "=" no fim. */
export function looksBase64(s) {
  return s.length > 0 && s.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(s.slice(0, 4096)) && /^[A-Za-z0-9+/]*={0,2}$/.test(s.slice(-4096));
}

/** Os trechos do Whisper, só o que a TV usa, com tempo em segundos e sem texto vazio. */
export function cleanSegments(res) {
  const segs = Array.isArray(res?.segments) ? res.segments : [];
  return segs.map((s) => ({ start: Number(s.start), end: Number(s.end), text: String(s.text ?? '').trim() }))
    .filter((s) => Number.isFinite(s.start) && Number.isFinite(s.end) && s.text).slice(0, 2000);
}

/** Lê a resposta do modelo de texto: {"summary": "...", "ads": [[ini, fim]]}; o que vier fora disso é descartado. */
export function parseSummary(raw) {
  const t = typeof raw === 'string' ? raw : String(raw?.response ?? '');
  const m = t.match(/\{[\s\S]*\}/);
  let o = {};
  try { o = m ? JSON.parse(m[0]) : {}; } catch { o = {}; }
  const summary = String(o.summary ?? '').replace(/\s+/g, ' ').trim().slice(0, 1200);
  const ads = (Array.isArray(o.ads) ? o.ads : [])
    .map((a) => (Array.isArray(a) ? [Number(a[0]), Number(a[1])] : null))
    .filter((a) => a && Number.isFinite(a[0]) && Number.isFinite(a[1]) && a[1] > a[0] && a[1] - a[0] <= 300)
    .slice(0, 20);
  return { summary, ads };
}

const NOMES = { pt: 'Portuguese', es: 'Spanish', fr: 'French', de: 'German', it: 'Italian', pl: 'Polish', ru: 'Russian',
  tr: 'Turkish', ar: 'Arabic', hi: 'Hindi', id: 'Indonesian', ja: 'Japanese', ko: 'Korean', zh: 'Chinese', en: 'English' };

export function summaryPrompt(title, text, lang) {
  const idioma = NOMES[lang] ?? 'English';
  return [
    { role: 'system', content: 'You summarize podcast episodes for a TV screen read by older people. Answer ONLY with JSON: ' +
      '{"summary": "<3 to 5 short sentences in ' + idioma + ', plain words, no lists>", "ads": [[startSeconds, endSeconds], ...]}. ' +
      '"ads" are paid sponsor reads or advertisements in the transcript (each line starts with its time in seconds); ' +
      'leave it empty when unsure. Never invent facts that are not in the transcript.' },
    { role: 'user', content: `Episode: ${title}\n\nTranscript:\n${text}` },
  ];
}

async function allowed(limiter, key) {
  if (!limiter) return true;
  try { return (await limiter.limit({ key })).success === true; } catch { return true; }
}

const quotaError = (e) => /quota|limit|neuron|429|capacity/i.test(String(e?.message ?? e));

export async function handleTranscribe(kind, req, url, env, { ip }) {
  if (!env.AI) return reply({ error: 'unavailable' }, 503);
  if (!(await allowed(env.TRANSCRIBE_IP_LIMITER, `ip:${ip}`))) return reply({ error: 'rate limited' }, 429);
  const lang = cleanLang(url.searchParams.get('lang'));
  const size = Number(req.headers.get('content-length') ?? 0);
  if (kind === 'transcribe') {
    if (size > MAX_B64) return reply({ error: 'too large' }, 413);
    const audio = (await req.text()).trim();
    if (audio.length > MAX_B64) return reply({ error: 'too large' }, 413);
    if (!looksBase64(audio)) return reply({ error: 'bad audio' }, 400);
    try {
      const res = await env.AI.run(WHISPER, { audio, task: 'transcribe', vad_filter: true, ...(lang ? { language: lang } : {}) });
      return reply({ segments: cleanSegments(res) });
    } catch (e) {
      return quotaError(e) ? reply({ error: 'quota' }, 429) : reply({ error: 'failed' }, 502);
    }
  }
  // summary
  if (size > MAX_TEXT * 4 + 2048) return reply({ error: 'too large' }, 413);
  let body = {};
  try { body = await req.json(); } catch { return reply({ error: 'bad json' }, 400); }
  const text = String(body.text ?? '').slice(0, MAX_TEXT);
  if (text.length < 200) return reply({ error: 'too short' }, 400);
  try {
    const res = await env.AI.run(LLM, { messages: summaryPrompt(String(body.title ?? '').slice(0, 200), text, lang || 'en'), max_tokens: 500 });
    return reply(parseSummary(res));
  } catch (e) {
    return quotaError(e) ? reply({ error: 'quota' }, 429) : reply({ error: 'failed' }, 502);
  }
}
