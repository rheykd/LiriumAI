const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/java/com/localmodels/app/data/LanServer.kt', 'utf8');
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];

function element() {
  return { value: '', options: [], children: [], disabled: false, textContent: '',
    addEventListener() {}, appendChild(child) { this.children.push(child); if (child.value) { this.options.push(child); if (!this.value) this.value = child.value; } },
    set innerHTML(value) { this.options = []; this.value = ''; } };
}

async function check() {
  const elements = Object.fromEntries(['log', 'model', 'text', 'send'].map(k => [k, element()]));
  const calls = [];
  let loaded = null;
  let failLoad = false;
  const context = { TextDecoder, console,
    document: { getElementById: id => elements[id], createElement: element }, setInterval() {},
    fetch: async (url, options) => {
      calls.push(url);
      if (url === '/api/state') return { ok: true, json: async () => ({ loaded, models: [{ id: 'm', name: 'Model' }] }) };
      if (url === '/api/load') {
        if (failLoad) return { ok: false, json: async () => ({ error: 'не удалось загрузить' }) };
        loaded = JSON.parse(options.body).id;
        return { ok: true, json: async () => ({ loaded }) };
      }
      const bytes = new TextEncoder().encode('data: {"token":"Проверяю"}\n\ndata: {"done":true,"text":"Готово"}\n\ndata: [DONE]\n\n');
      const chunks = [bytes.slice(0, 23), bytes.slice(23, 34), bytes.slice(34)];
      return { ok: true, body: { getReader: () => ({ read: async () => chunks.length ? { value: chunks.shift(), done: false } : { done: true } }) } };
    }
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  await new Promise(resolve => setImmediate(resolve));
  elements.text.value = 'hello';
  await context.sendMsg();
  assert.deepEqual(calls.slice(-2), ['/api/load', '/api/chat?stream=1']);
  assert.equal(elements.log.children.at(-1).textContent, 'Готово');
  assert.equal(elements.send.disabled, false);
  assert.equal(elements.model.disabled, false);
  loaded = null;
  context.loaded = null;
  failLoad = true;
  elements.text.value = 'retry';
  const before = calls.filter(x => x.startsWith('/api/chat')).length;
  await context.sendMsg();
  assert.equal(calls.filter(x => x.startsWith('/api/chat')).length, before);
  assert.equal(elements.log.children.at(-1).textContent, 'не удалось загрузить');
  console.log('PASS: first model load, UTF-8/chunked SSE, final text, controls and load failure');
}
check().catch(e => { console.error(e); process.exitCode = 1; });
