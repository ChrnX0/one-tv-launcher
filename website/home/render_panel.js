// Draws the REAL web panel (app/src/main/assets/painel) with example data, on a computer and a phone, in every
// language the panel has. Output: PNGs for make_images.py. Needs Playwright (not a dependency of the site build).
// Run: node website/home/render_panel.js <output folder>
const { chromium } = require("playwright");
const fs = require("fs"), path = require("path");
const DIR = path.resolve(__dirname, "../../app/src/main/assets/painel");
const OUT = process.argv[2];
const LANGS = ["pt", "en", "es", "fr", "de", "it", "pl", "ru", "tr", "ja", "ko", "zh", "hi", "id", "ar"];
// Example data in each language: the TV's room name and what is on it (the home screen).
const ROOM = { pt: ["Sala de estar", "Tela inicial"], en: ["Living room", "Home screen"], es: ["Sala de estar", "Pantalla de inicio"],
  fr: ["Salon", "Écran d'accueil"], de: ["Wohnzimmer", "Startbildschirm"], it: ["Soggiorno", "Schermata iniziale"],
  pl: ["Salon", "Ekran główny"], ru: ["Гостиная", "Главный экран"], tr: ["Oturma odası", "Ana ekran"], ja: ["リビング", "ホーム画面"],
  ko: ["거실", "홈 화면"], zh: ["客厅", "主屏幕"], hi: ["बैठक", "होम स्क्रीन"], id: ["Ruang keluarga", "Layar utama"],
  ar: ["غرفة المعيشة", "الشاشة الرئيسية"] };
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".woff2": "font/woff2", ".png": "image/png", ".webmanifest": "application/json" };
(async () => {
  const b = await chromium.launch();
  for (const l of LANGS) {
    for (const [w, h, n, mob] of [[1440, 900, "pc", false], [390, 844, "cel", true]]) {
      const ctx = await b.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2, isMobile: mob, hasTouch: mob, locale: l });
      const p = await ctx.newPage();
      await p.route("http://tv.local:8080/**", async (r) => {
        let f = new URL(r.request().url()).pathname;
        if (f.startsWith("/api/")) {   // example data only: a TV called by its room, showing the home screen
          const body = f === "/api/estado" ? { logado: true, aparelho: ROOM[l][0], naTela: ROOM[l][1], papel: "dono" } : {};
          return r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
        }
        if (f === "/") f = "/index.html";
        const fp = path.join(DIR, f);
        if (!fs.existsSync(fp)) return r.fulfill({ status: 404, body: "" });
        return r.fulfill({ status: 200, contentType: TYPES[path.extname(fp)] || "application/octet-stream", body: fs.readFileSync(fp) });
      });
      await p.goto("http://tv.local:8080/");
      await p.waitForTimeout(2000);
      await p.screenshot({ path: path.join(OUT, `painel-${l}-${n}.png`) });
      await ctx.close();
    }
  }
  await b.close();
})();
