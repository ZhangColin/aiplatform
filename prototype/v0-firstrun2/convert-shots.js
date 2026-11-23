// 把选定的截图从 png 转成 jpg（质量 80、宽度不变），供存进抛弃分支；原图只留在本地。
// 在 aiplatform/dev:0.18 镜像的临时容器里跑：
//   SHARP_PATH=$(find /workspace/node_modules/.pnpm -maxdepth 4 -type d -name sharp | head -1) \
//   node /work/convert-shots.js /work/in/list.txt /work/out
// list.txt 每行一个相对 /work/in 的 png 路径。
const fs = require('fs');
const path = require('path');
const sharp = require(process.env.SHARP_PATH);

(async () => {
  const [, , listFile, outDir] = process.argv;
  const lines = fs.readFileSync(listFile, 'utf8').split('\n').map((s) => s.trim())
    .filter((s) => s && !s.startsWith('#'));
  let total = 0;
  let failed = 0;
  for (const rel of lines) {
    const src = path.join('/work/in', rel);
    const dst = path.join(outDir, rel.replace(/\.png$/, '.jpg'));
    try {
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      const buf = await sharp(src).jpeg({ quality: 80, mozjpeg: true }).toBuffer();
      fs.writeFileSync(dst, buf);
      total += buf.length;
      console.log(rel.padEnd(56) + String(Math.round(buf.length / 1024)).padStart(5) + ' KB');
    } catch (e) {
      failed++;
      console.log('FAILED ' + rel + ' :: ' + String(e).slice(0, 120));
    }
  }
  console.log('done: ' + (lines.length - failed) + ' files, ' + (total / 1048576).toFixed(1) + ' MB, ' + failed + ' failed');
})();
