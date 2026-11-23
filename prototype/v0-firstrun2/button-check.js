// 主会话用：追加修改之后，工程师在「不是指派给自己的工单」详情页上还能不能看到操作按钮。只读。
const { chromium } = require('/opt/render/node_modules/playwright-core');

(async () => {
  const b = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, args: ['--no-sandbox'] });
  const p = await b.newPage({ viewport: { width: 1366, height: 900 }, locale: 'zh-CN' });
  await p.goto('http://localhost:8081/login', { waitUntil: 'networkidle', timeout: 45000 });
  const status = await p.evaluate(async () => (await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'wangxiaolei', password: '123456' }),
  })).status);
  console.log('login as wangxiaolei (engineer):', status);
  const list = await p.evaluate(async () => (await (await fetch('/api/tickets?size=50')).json()));
  const items = (list.tickets || list.data || list.items || []);
  console.log('tickets visible:', items.length);
  // 自己的一张、别人的一张（处理中且未关闭）
  const pick = (pred) => items.find((t) => t.status === 'processing' && pred(t));
  const mine = pick((t) => t.assigneeName === '王筱蕾');
  const others = pick((t) => t.assigneeName && t.assigneeName !== '王筱蕾');
  for (const [label, t] of [['自己的工单', mine], ['别人的工单', others]]) {
    if (!t) { console.log(label, ': 没找到合适的样本'); continue; }
    await p.goto('http://localhost:8081/tickets/' + t.id, { waitUntil: 'networkidle', timeout: 45000 });
    const info = await p.evaluate(() => {
      const main = document.querySelector('main') || document.body;
      const btns = Array.from(main.querySelectorAll('button')).map((x) => x.innerText.replace(/\s+/g, ' ').trim()).filter((x) => x && x.length < 16);
      const note = (document.body.innerText.match(/[^\n]*(只能查看|你不是被指派)[^\n]*/) || [''])[0].slice(0, 80);
      return { buttons: Array.from(new Set(btns)), note };
    });
    console.log(label, '(id ' + t.id + ', 指派给 ' + t.assigneeName + '):', JSON.stringify(info));
    await p.screenshot({ path: '/tmp/linecheck/q1-' + (t === mine ? 'mine' : 'others') + '.png' });
  }
  await b.close();
})();
