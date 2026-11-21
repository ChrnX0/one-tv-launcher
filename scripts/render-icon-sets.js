#!/usr/bin/env node
// Renders the cross icon sets (art/icon-sets/<set>/<type>.svg, 120 x 100 frame) into the WebP files the app reads
// (app/src/main/assets/icon-sets/<set>/<type>.webp, 360 x 300 with transparency).
// Why raster: the Photoreal and Vintage 2.0 sets use SVG filters (relief, grain, wood) that Android's Canvas does not
// have; all sets go the same way so the app has one code path. 360 x 300 is the chosen (largest) icon on a 1080p TV.
// Animation: the moving part of each icon is marked in the SVG (class="a" data-m="<motion>", see art/icon-sets/motion.py).
// It is cut out into <type>-a.webp (only that part) and <type>.webp gets everything else; motion.json says, per
// <set>/<type>, the motion and its pivot (centre of the moving part, or its bottom for "swing") as fractions of the frame.
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
  const motion = {};
  let n = 0;
  for (const set of fs.readdirSync(SRC).sort()) {
    const dir = path.join(SRC, set);
    if (!fs.statSync(dir).isDirectory()) continue;
    fs.mkdirSync(path.join(OUT, set), { recursive: true });
    for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.svg')).sort()) {
      const svg = fs.readFileSync(path.join(dir, file), 'utf8');
      // Drawn into a canvas in the page and encoded there: Chromium writes WebP with alpha.
      const r = await page.evaluate(async ({ svg, W, H, QUALITY }) => {
        const draw = async (text) => {
          const img = new Image();
          img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(text.replace('width="120" height="100"', `width="${W}" height="${H}"`));
          await img.decode();
          const c = document.createElement('canvas');
          c.width = W; c.height = H;
          c.getContext('2d').drawImage(img, 0, 0, W, H);
          return c;
        };
        const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
        const moving = [...doc.querySelectorAll('.a')];
        if (!moving.length) return { base: (await draw(svg)).toDataURL('image/webp', QUALITY) };
        const m = moving[0].getAttribute('data-m');
        const ser = () => new XMLSerializer().serializeToString(doc);
        // base: everything but the moving part
        moving.forEach(e => e.setAttribute('display', 'none'));
        const base = (await draw(ser())).toDataURL('image/webp', QUALITY);
        // moving part: hide every drawn element, show the marked ones (visibility is inherited, so the marked
        // element and its children show even inside a hidden group; defs are untouched)
        moving.forEach(e => e.removeAttribute('display'));
        doc.querySelectorAll('svg *').forEach(e => { if (!e.closest('defs')) e.setAttribute('visibility', 'hidden'); });
        moving.forEach(e => { e.setAttribute('visibility', 'visible'); e.querySelectorAll('*').forEach(k => k.setAttribute('visibility', 'visible')); });
        const ca = await draw(ser());
        const px = ca.getContext('2d').getImageData(0, 0, W, H).data;
        let x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (px[(y * W + x) * 4 + 3] > 24) {
          if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
        }
        if (x1 < 0) return { error: 'moving part is empty' };
        const px_ = (x0 + x1) / 2 / W, py = (m === 'swing' ? y1 : (y0 + y1) / 2) / H;
        return { base, anim: ca.toDataURL('image/webp', QUALITY), m, px: +px_.toFixed(3), py: +py.toFixed(3) };
      }, { svg, W, H, QUALITY });
      const name = file.replace(/\.svg$/, '');
      if (r.error) throw new Error(`${set}/${name}: ${r.error}`);
      fs.writeFileSync(path.join(OUT, set, name + '.webp'), Buffer.from(r.base.split(',')[1], 'base64'));
      const a = path.join(OUT, set, name + '-a.webp');
      if (r.anim) {
        fs.writeFileSync(a, Buffer.from(r.anim.split(',')[1], 'base64'));
        motion[`${set}/${name}`] = { m: r.m, px: r.px, py: r.py };
      } else if (fs.existsSync(a)) fs.unlinkSync(a);
      n++;
    }
  }
  fs.writeFileSync(path.join(OUT, 'motion.json'), JSON.stringify(motion, null, 1) + '\n');
  await browser.close();
  console.log(`${n} icons rendered into ${path.relative(ROOT, OUT)} (${Object.keys(motion).length} with a moving part)`);
})();
