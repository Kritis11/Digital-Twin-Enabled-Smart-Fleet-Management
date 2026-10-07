// Renders every Mermaid block in README.md and docs/*.md with the real Mermaid library and reports any that fail.
//   cd e2e && node tools/mermaid-check.mjs
import { chromium } from '@playwright/test';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
const files = ['../README.md', ...readdirSync('../docs').filter((f) => f.endsWith('.md')).map((f) => '../docs/' + f)];
const blocks = files.flatMap((f) => [...readFileSync(f, 'utf8').matchAll(/```mermaid\n([\s\S]*?)```/g)].map((m, i) => ({ file: f.replace('../', ''), n: i + 1, src: m[1] })));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
await page.setContent('<div id="out" style="background:#fff;padding:16px"></div><script type="module">import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs"; mermaid.initialize({ startOnLoad: false }); window.mermaid = mermaid;</script>');
await page.waitForFunction(() => window.mermaid);
let failed = 0;
for (const [i, b] of blocks.entries()) {
  const result = await page.evaluate(async ({ src, id }) => { try { const { svg } = await window.mermaid.render(id, src); document.getElementById('out').innerHTML = svg; return 'ok'; } catch (e) { return 'ERROR: ' + e.message.slice(0, 200); } }, { src: b.src, id: 'd' + i });
  if (result !== 'ok') failed++;
  console.log(`${b.file} #${b.n} (${b.src.trim().split('\n')[0]}): ${result}`);
  if (process.argv[2] && b.file.includes(process.argv[2]) && result === 'ok') await page.locator('#out').screenshot({ path: `test-results/mermaid-${i}.png` });
}
console.log(`${blocks.length} diagrams, ${failed} failed`);
await browser.close();
