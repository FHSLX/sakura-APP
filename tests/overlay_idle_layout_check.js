'use strict';
const fs=require('fs'), path=require('path'), assert=require('assert');
const root=path.join(__dirname,'..');
const current=path.join(root,'sakura_remote/static/app.js');
const baseline=path.join(root,'backup/passthrough-perf-20261013/static/app.js');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function run(file) {
  const source=fs.readFileSync(file,'utf8');
  const start=source.indexOf('function installContentWidthSync()');
  const end=source.indexOf('/* ---------- 手机截屏',start);
  assert(start>=0&&end>start);
  const counters={measurements:0,writes:0,requests:0};
  const timers=[],listeners={},docListeners={};
  let observer, last=null;
  const style={setProperty(){counters.writes++},removeProperty(){counters.writes++}};
  const portrait={complete:true,naturalHeight:900,addEventListener(){}};
  Object.defineProperty(portrait,'naturalWidth',{get(){counters.measurements++;return 800}});
  const el={portrait,portraitWrap:{style},msgNav:null,msgBody:{},composer:{offsetHeight:70}};
  const doc={hidden:false,body:{classList:{contains(){return false}}},documentElement:{style},
    addEventListener(name,fn){docListeners[name]=fn}};
  const win={screen:{width:393,height:851},innerWidth:393,innerHeight:700,
    addEventListener(name,fn){listeners[name]=fn}};
  const api=new Function('el','state','document','window','nativeBridge','isOverlayPage',
    'applyMessageNav','syncDialogOffset','applyPortraitTransform','applyPortraitOffset',
    'readOverlayWindowSize','rememberOverlayWindowSize','setInterval','ResizeObserver',
    'requestAnimationFrame','let relayoutOverlay=null;'+source.slice(start,end)
      +'installContentWidthSync();return {relayout:()=>relayoutOverlay()};')(
      el,{portraitScale:1},doc,win,()=>({setWindowSize(){counters.requests++}}),()=>true,
      ()=>{},()=>{},()=>{},()=>{},()=>last,(w,h)=>{last={w,h}},
      (fn,ms)=>{const timer=setInterval(fn,ms);timers.push(timer);return timer},
      class {constructor(fn){observer=fn}observe(){}disconnect(){}},
      fn=>setTimeout(fn,0));
  try {
    const initial={...counters};
    await sleep(1300);
    const idle={measurements:counters.measurements-initial.measurements,
      writes:counters.writes-initial.writes,requests:counters.requests-initial.requests};
    for(let i=0;i<15;i++)api.relayout();
    await sleep(30);
    const burst=counters.measurements-initial.measurements-idle.measurements;
    const before=counters.measurements;
    if(observer)observer();
    else listeners.resize();
    await sleep(30);
    const resize=counters.measurements-before;
    doc.hidden=true;
    const hiddenBefore=counters.measurements;
    api.relayout();
    await sleep(30);
    const hidden=counters.measurements-hiddenBefore;
    return {idle,burst,resize,hidden};
  } finally {timers.forEach(clearInterval)}
}
(async()=>{
  const before=await run(baseline),after=await run(current);
  console.log(JSON.stringify({before,after},null,2));
  assert(before.idle.measurements>=3,'Baseline did not exercise periodic layout');
  assert.strictEqual(after.idle.measurements,0,'Idle layout still runs');
  assert.strictEqual(after.idle.writes,0,'Idle style writes remain');
  assert.strictEqual(after.burst,1,'Same-frame changes not coalesced');
  assert.strictEqual(after.resize,1,'Real size changes lost');
  assert.strictEqual(after.hidden,0,'Hidden page still measures');
  console.log('PASS: actual production installer removes idle layout, coalesces changes, preserves resize');
})().catch(error=>{console.error(error);process.exitCode=1});
