// Renders docs/readme/art.html into the README pictures: banner.png, map.png and social-preview.png.
// Why: the GitHub page shows the same wordmark and the same screens as the app and sofaly.tv.
// When: after the brand, the home screen or the website screens change. Needs Playwright (no network: fonts and
// screens are files of this repo). Run from the repo root: node scripts/readme-images.js
const { chromium } = require('playwright');
const path = require('path');

const art = 'file://' + path.resolve(__dirname, '../docs/readme/art.html');
const scenes = { banner: [1600, 760], map: [1600, 900], social: [1280, 640] };
const out = { banner: 'banner.png', map: 'map.png', social: 'social-preview.png' };

(async () => {
  const browser = await chromium.launch();
  for (const [id, [w, h]] of Object.entries(scenes)) {
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    await page.goto(art + '#' + id);
    await page.evaluate(() => document.fonts.ready);
    await page.waitForLoadState('networkidle');
    await page.locator('#' + id).screenshot({ path: path.resolve(__dirname, '../docs/readme/' + out[id]) });
    await page.close();
    console.log('docs/readme/' + out[id]);
  }
  await browser.close();
})();
