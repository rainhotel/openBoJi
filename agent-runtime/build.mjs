import { build } from 'esbuild';
import { resolve } from 'node:path';
import { writeFileSync, readFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
const result = await build({
  entryPoints: ['src/runtime.ts'], bundle: true, format: 'iife', globalName: 'BojiAgent',
  platform: 'browser', target: 'chrome90', minify: true, legalComments: 'eof', metafile: true,
  alias: { fs: resolve('src/skill-fs.ts'), path: 'path-browserify' },
  define: { 'process.env.NODE_ENV': '"production"' },
  outfile: '../app/src/main/assets/agent/runtime.js'
});
writeFileSync('bundle-meta.json', JSON.stringify(result.metafile, null, 2));
const output = Object.values(result.metafile.outputs)[0];
const packages = new Set();
for (const [path, item] of Object.entries(output.inputs)) {
  if (!item.bytesInOutput || !path.startsWith('node_modules/')) continue;
  const parts = path.slice(13).split('/');
  packages.add(parts[0].startsWith('@') ? parts.slice(0,2).join('/') : parts[0]);
}
let notices = 'Third party code included in the Boji Agent browser bundle\n\n';
for (const name of [...packages].sort()) {
  const dir = 'node_modules/' + name;
  const metadata = JSON.parse(readFileSync(dir + '/package.json','utf8'));
  const file = readdirSync(dir).find(n => /^licen[sc]e(?:[.-]|$)/i.test(n));
  const license = file ? readFileSync(dir + '/' + file,'utf8') : name.startsWith('@earendil-works/pi-') ? readFileSync('vendor/pi/LICENSE','utf8') : null;
  if (!license) throw Error('License file missing: ' + name);
  notices += `\n=== ${name} ${metadata.version} (${metadata.license}) ===\n` + license + '\n';
}
notices += '\n=== Vendored Pi Skill loader (MIT) ===\n' + readFileSync('vendor/pi/LICENSE','utf8');
writeFileSync('../app/src/main/assets/agent/THIRD-PARTY-NOTICES.txt',notices);
const walk = dir => readdirSync(dir,{withFileTypes:true}).flatMap(e => e.isDirectory() ? walk(dir+'/'+e.name) : [dir+'/'+e.name]);
const sourceFiles = ['package.json','package-lock.json','build.mjs',...walk('src'),...walk('vendor')].sort();
const sha = data => createHash('sha256').update(data).digest('hex');
writeFileSync('../app/src/main/assets/agent/bundle-manifest.json',JSON.stringify({
  piVersion: '1.3.0', upstreamCommit: '482c1381a00d8d00bc81697e4a980c593db6803e',
  bundleSha256: sha(readFileSync('../app/src/main/assets/agent/runtime.js')),
  sourceFiles: Object.fromEntries(sourceFiles.map(p=>[p,sha(readFileSync(p))])),
  packages: [...packages].sort(), bytes: output.bytes
},null,2));
console.log('Bundled Pi runtime for Android WebView');
