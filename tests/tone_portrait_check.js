// 用真实角色数据验证「语气 -> 立绘」匹配质量。
// 用法: node tests/tone_portrait_check.js
'use strict';

const fs = require('fs');
const path = require('path');
// Registry 投稿要求 plugin.yaml 在仓库根目录，早期在 sakura_remote/ 子目录。
// 两种布局都认，避免改了布局测试就跑不起来。
const fs0 = require('fs');
const _root = path.join(__dirname, '..');
const PLUGIN_ROOT = fs0.existsSync(path.join(_root, 'plugin.yaml'))
  ? _root
  : path.join(_root, 'sakura_remote');


const source = fs.readFileSync(path.join(PLUGIN_ROOT, 'static', 'app.js'), 'utf8');

// 只截取匹配算法部分，避免执行依赖 DOM 的代码。
const tokensStart = source.indexOf('const TONE_STOPWORDS');
const tokensEnd = source.indexOf('function scorePortrait');
const scoreEnd = source.indexOf('function setPortrait');
if (tokensStart < 0 || tokensEnd < 0 || scoreEnd < 0) {
  console.error('无法从 app.js 中提取匹配算法');
  process.exit(1);
}
const snippet =
  source.slice(tokensStart, tokensEnd) + source.slice(tokensEnd, scoreEnd);

const holder = { state: null };
const fn = new Function(
  'holder',
  'const state = holder.state;\n' + snippet + '\nreturn { resolvePortraitKey, scorePortrait };'
);

const manifest = JSON.parse(
  fs.readFileSync(path.join(__dirname, 'fixtures', 'character.json'), 'utf8')
);
const portraitKeys = Object.keys((manifest.portrait && manifest.portrait.expressions) || {});

function makeResolver(keys, defaultKey, overrides) {
  const alias = new Map(keys.map((key) => [key, key]));
  holder.state = {
    portraitAlias: alias,
    portraitByKey: alias,
    portraitOverrides: new Map(Object.entries(overrides || {})),
    defaultPortraitKey: defaultKey,
  };
  return fn(holder);
}

// 这台机器上 Sakura 的真实数据
const tones = ['中性', '不满', '害羞', '请求', '惊讶'];
const realKeys = [
  '站立待机', '开心脸红', '张嘴疑问', '害羞脸红', '难过沮丧',
  '不满无语', '两眼放光', '高兴满足', '吃醋不满', '自信拍胸',
  '自信撩发', '侧身无语', '伸手命令', '伸手抚摸',
];

console.log('—— 真实角色（14 个立绘，含 portrait_map 覆盖）——');
const realResolver = makeResolver(realKeys, '站立待机', { 中性: '站立待机', 不满: '不满无语' });
for (const tone of tones) {
  console.log(`  ${tone} -> ${realResolver.resolvePortraitKey(tone)}`);
}

console.log('\n—— 覆盖映射生效验证 ——');
const forced = makeResolver(realKeys, '站立待机', { 惊讶: '高兴满足' });
const forcedKey = forced.resolvePortraitKey('惊讶');
console.log(`  惊讶 -> ${forcedKey}（应被 portrait_map 强制为 高兴满足）`);
if (forcedKey !== '高兴满足') {
  console.error('FAIL: portrait_map 覆盖没有生效');
  process.exit(1);
}

console.log('\n—— 简化 fixture（2 个立绘）——');
const tinyResolver = makeResolver(portraitKeys, portraitKeys[0]);
for (const tone of tones) {
  console.log(`  ${tone} -> ${tinyResolver.resolvePortraitKey(tone)}`);
}

const fallback = tinyResolver.resolvePortraitKey('完全未知的语气');
console.log(`\n未知语气 -> ${fallback}（应回落到默认立绘）`);
if (fallback !== portraitKeys[0]) {
  console.error('FAIL: 未知语气没有回落到默认立绘');
  process.exit(1);
}

const resolved = tones.map((tone) => realResolver.resolvePortraitKey(tone));
const unique = new Set(resolved);
console.log('解析结果:', resolved.join(' / '));
if (unique.size < 3) {
  console.error('FAIL: 语气基本没有区分出不同立绘');
  process.exit(1);
}

// 关键词必须在带噪声的立绘名里也能命中
const noisy = makeResolver(['站立待机', '不满无语', '吃醋不满', '害羞脸红'], '站立待机');
const noisyResult = noisy.resolvePortraitKey('不满');
console.log(`\n噪声场景 不满 -> ${noisyResult}`);
if (noisyResult !== '不满无语') {
  console.error('FAIL: 不满 应命中 不满无语');
  process.exit(1);
}

console.log('\nPASS');
