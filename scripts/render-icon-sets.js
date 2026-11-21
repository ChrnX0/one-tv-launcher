#!/usr/bin/env node
// Renders the cross icon sets (art/icon-sets/<set>/<type>.svg, 120 x 100 frame) into the WebP files the app reads
// (app/src/main/assets/icon-sets/<set>/<type>.webp, 360 x 300 with transparency).
// Why raster: the Photoreal and Vintage 2.0 sets use SVG filters (relief, grain, wood) that Android's Canvas does not
// have; all sets go the same way so the app has one code path. 360 x 300 is the chosen (largest) icon on a 1080p TV.
// Usage: node scripts/render-icon-sets.js   (needs Playwright's Chromium, already in the dev container)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'art', 'icon-sets');
const OUT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'icon-sets');
const W = 360, H = 300, QUALITY = 0.9;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.setContent('<html><body></body></html>');
  let n = 0;
  for (const set of fs.readdirSync(SRC).sort()) {
    const dir = path.join(SRC, set);
    if (!fs.statSync(dir).isDirectory()) continue;
    fs.mkdirSync(path.join(OUT, set), { recursive: true });
    for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.svg')).sort()) {
      const svg = fs.readFileSync(path.join(dir, file), 'utf8');
      // The SVG is drawn into a canvas in the page and encoded there: Chromium writes WebP with alpha.
      const dataUrl = await page.evaluate(async ({ svg, W, H, QUALITY }) => {
        const img = new Image();
        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg.replace('width="120" height="100"', `width="${W}" height="${H}"`));
        await img.decode();
        const c = document.createElement('canvas');
        c.width = W; c.height = H;
        c.getContext('2d').drawImage(img, 0, 0, W, H);
        return c.toDataURL('image/webp', QUALITY);
      }, { svg, W, H, QUALITY });
      const out = path.join(OUT, set, file.replace(/\.svg$/, '.webp'));
      fs.writeFileSync(out, Buffer.from(dataUrl.split(',')[1], 'base64'));
      n++;
    }
  }
  await browser.close();
  console.log(`${n} icons rendered into ${path.relative(ROOT, OUT)}`);
})();
