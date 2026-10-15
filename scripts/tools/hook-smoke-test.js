/**
 * PageHook 冒烟测试（本机跑，不需要设备）：验证 document-start 钩子的真实行为。
 *
 * 用法：node tools\hook-smoke-test.js
 *
 * 它会从 app/src/main/java/com/dy/scraper/core/PageHook.kt 里抽出注入脚本，
 * 用 vm 造一个假 WebView 环境（window/document/navigator/DyBridge + 假 XHR/fetch），断言：
 *   1. 命中详情 API 的响应体经 DyBridge.onApiBody 回传（视频地址的唯一来源）；
 *   2. 非详情接口 / 整页 HTML 壳 / 空 JSON 一律丢弃；
 *   3. `window._ROUTER_DATA` 等 SSR 变量在赋值瞬间被快照，且页面删除后快照仍在；
 *   4. 脚本可重复注入（幂等），不会重复上报。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const hookKt = path.join(root, 'app', 'src', 'main', 'java', 'com', 'dy', 'scraper', 'core', 'PageHook.kt');

function extractScript() {
    const src = fs.readFileSync(hookKt, 'utf8');
    const start = src.indexOf('return """');
    const end = src.indexOf('""".trimIndent()', start);
    if (start < 0 || end < 0) throw new Error('PageHook.kt 里找不到 return """...""" 注入脚本');
    let js = src.substring(start + 'return """'.length, end);
    // Kotlin 模板串 → buildScript 实际拼出的字面量
    js = js.replace('[$patternArray]',
        '["/aweme/v1/web/aweme/detail/","/aweme/v1/web/note/detail/","/aweme/v1/web/note/","/aweme/v1/aweme/detail/"]');
    js = js.replace('[$ssrArray]',
        "['_ROUTER_DATA','__INITIAL_STATE__','__UNIVERSAL_DATA__','__NEXT_DATA__','__NUXT__','__DATA__']");
    if (js.indexOf('$patternArray') >= 0 || js.indexOf('$ssrArray') >= 0) {
        throw new Error('模板占位符未替换成功（buildScript 结构变了？）');
    }
    return js;
}

const results = [];
function check(cond, name, extra) {
    results.push((cond ? 'PASS' : 'FAIL') + ' ' + name + (extra === undefined ? '' : ' :: ' + extra));
}

const code = extractScript();
const captured = [];
const bridge = { onApiBody: (url, body) => captured.push({ url: url, body: body }) };

function FakeXHR() {
    this._ls = {};
    this.responseText = '';
    this.responseType = '';
}
FakeXHR.prototype.addEventListener = function (type, fn) {
    (this._ls[type] = this._ls[type] || []).push(fn);
};
FakeXHR.prototype.open = function (method, url) { this.method = method; this.url = url; };
FakeXHR.prototype.send = function () { this._sent = true; };
FakeXHR.prototype.finish = function (text) {
    this.responseText = text;
    const ls = this._ls['loadend'] || [];
    for (const fn of ls) fn();
};

const fakeScript = {
    id: 'RENDER_DATA',
    getAttribute: () => null,
    textContent: '<script id="RENDER_DATA">' +
        JSON.stringify({ play_addr: 'x', aweme_id: '7690515078848542890' }) + '</script>'
};

const win = { XMLHttpRequest: FakeXHR };
win.DyBridge = bridge;

const doc = {
    readyState: 'complete',
    addEventListener: () => { },
    querySelectorAll: (sel) => (sel === 'script' ? [fakeScript] : [])
};

let fetchUrl = '';
win.fetch = function (input) {
    fetchUrl = (typeof input === 'string') ? input : (input && input.url);
    const body = JSON.stringify({ aweme_detail: { aweme_id: '7690515078848542890', video: {} } });
    return Promise.resolve({ ok: true, clone: () => ({ text: () => Promise.resolve(body) }) });
};

const sandbox = {
    window: win,
    document: doc,
    navigator: {},
    DyBridge: bridge,
    Object: Object,
    JSON: JSON,
    String: String,
    Array: Array,
    Math: Math,
    Date: Date,
    Promise: Promise,
    console: console
};
vm.createContext(sandbox);
const runHook = () => vm.runInContext(code, sandbox);

const DETAIL = 'https://www.douyin.com/aweme/v1/web/aweme/detail/' +
    '?device_platform=webapp&aweme_id=7690515078848542890&a_bogus=abc';
const body = JSON.stringify({ aweme_detail: { aweme_id: '7690515078848542890', desc: 'x' } });

function sendXhr(url, text) {
    const x = new win.XMLHttpRequest();
    x.open('GET', url);
    x.send();
    x.finish(text);
}

// 1. 幂等安装
check(runHook() === 'installed', 'install returns installed');
check(runHook() === 'already', 'second install is no-op');
check(win.__dyHookV1 === true, 'idempotence flag set');

// 2. SSR 快照
win._ROUTER_DATA = { loaderData: { video: { play_addr: { url_list: ['https://v3-web.douyinvod.com/x'] } } } };
const snap = win.__dySsr && win.__dySsr['_ROUTER_DATA'];
check(typeof snap === 'string' && snap.indexOf('play_addr') >= 0,
    'router data snapshotted', snap ? snap.length + ' chars' : 'missing');
delete win._ROUTER_DATA;
check(snap === win.__dySsr['_ROUTER_DATA'], 'snapshot survives page deleting the global');
check(typeof win.__dySsr['script:RENDER_DATA'] === 'string', 'inline script snapshotted');

// 3. XHR 旁听
let before = captured.length;
sendXhr(DETAIL, body);
check(captured.length === before + 1 && captured[captured.length - 1].body === body, 'xhr detail body reported');

before = captured.length;
sendXhr('https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=1', body);
check(captured.length === before, 'xhr non-detail ignored');

before = captured.length;
sendXhr(DETAIL, '<!DOCTYPE html><html><body>verify</body></html>');
check(captured.length === before, 'xhr html shell dropped');

before = captured.length;
sendXhr(DETAIL, '{}');
check(captured.length === before, 'xhr empty json dropped');

// 4. fetch 旁听
(async () => {
    before = captured.length;
    await win.fetch(DETAIL);
    await new Promise((r) => setImmediate(r));
    check(captured.length === before + 1, 'fetch detail body reported');
    check(fetchUrl === DETAIL, 'fetch passthrough url kept');

    before = captured.length;
    await win.fetch('https://www.douyin.com/aweme/v1/web/comment/list/');
    await new Promise((r) => setImmediate(r));
    check(captured.length === before, 'fetch non-detail ignored');

    const failed = results.filter((r) => r.indexOf('FAIL') === 0);
    for (const r of results) console.log(r);
    console.log('SUMMARY total=' + results.length + ' failed=' + failed.length);
    process.exit(failed.length === 0 ? 0 : 1);
})();
