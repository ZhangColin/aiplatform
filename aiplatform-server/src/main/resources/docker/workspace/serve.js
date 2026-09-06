// 极简静态文件服务器（demo 预览示意用，零依赖）
// 用法：node /opt/serve.js <root> <port>
// #97 圈注 B 档：对 HTML 响应内联注入平台标注脚本（/opt/annotation.js，缺失则
// 不注入——历史版本/纯静态兜底路径也不因注入失败而打不开）。
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || '/workspace';
const port = Number(process.argv[3] || 8081);
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

// 标注脚本资产（平台产物，注入用户系统页面用）；读取失败降级为空注入
let annotationScript = '';
try {
  annotationScript = fs.readFileSync('/opt/annotation.js', 'utf8');
} catch (e) {
  // 无脚本资产：跳过注入（静态兜底路径不依赖标注）
}

function injectAnnotation(html) {
  if (!annotationScript) return html;
  const tag = '<script data-aiplatform="annotation">' + annotationScript + '</script>';
  if (html.indexOf('</body>') !== -1) {
    return html.replace('</body>', tag + '</body>');
  }
  if (html.indexOf('</html>') !== -1) {
    return html.replace('</html>', tag + '</html>');
  }
  return html + tag;
}

http.createServer((req, res) => {
  const p = decodeURIComponent(req.url.split('?')[0]);
  const file = path.join(root, p === '/' ? '/index.html' : p);
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 not found: ' + p + '\n(agent 还没写出这个文件？)');
      return;
    }
    const type = mime[path.extname(file)] || 'application/octet-stream';
    const body = type.startsWith('text/html') ? injectAnnotation(data.toString('utf8')) : data;
    res.writeHead(200, { 'Content-Type': type });
    res.end(body);
  });
}).listen(port, '0.0.0.0', () => {
  console.log('serving ' + root + ' on ' + port);
});
