'use strict';
// Exact union at display resolution. No portrait pixels are removed and no gap
// is enlarged; count rectangles by the same independent-column merge as native.
const {makeEnv, loadModule} = require('./touch_region_real_png_check');
const {decodePngAlpha} = require('./png_alpha');
const fs = require('fs');
function partition(rects, width, height) {
  const rows = Array.from({length:height}, () => []);
  for (const [left,top,right,bottom] of rects) {
    for(let y=Math.max(0,Math.floor(top));y<Math.min(height,Math.ceil(bottom));y++) {
      rows[y].push([Math.max(0,Math.floor(left)),Math.min(width,Math.ceil(right))]);
    }
  }
  const frames=[], previous=new Map();
  for(let y=0;y<height;y++) {
    const merged=[];
    for(const range of rows[y].sort((a,b)=>a[0]-b[0])) {
      const last=merged[merged.length-1];
      if(last && range[0]<=last[1]) last[1]=Math.max(last[1],range[1]);
      else if(range[1]>range[0]) merged.push(range.slice());
    }
    for(const [left,right] of merged) {
      const key=left+':'+right, old=previous.get(key);
      if(old && old[3]===y) old[3]=y+1;
      else {const frame=[left,y,right,y+1]; previous.set(key,frame);frames.push(frame);}
    }
  }
  return frames;
}
const ring=[[0,0,10,2],[0,2,2,8],[8,2,10,8],[0,8,10,10]];
const frames=partition(ring,10,10);
for(let y=0;y<10;y++) for(let x=0;x<10;x++) {
  const inside=rs=>rs.some(r=>x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3]);
  if(inside(ring)!==inside(frames)) throw Error('Synthetic hole/coverage changed');
}
console.log('PASS: exact frame union preserves synthetic hole and all foreground');
for(const file of ['Sakura/portraits/A020.png','Sakura/portraits/E050.png',
  'nikaidou_shinku/portrait/default/01_neutral.png']) {
  const path='F:/sakura/characters/'+file;
  if(!fs.existsSync(path)) {console.log('SKIP: installed visual fixture absent');continue;}
  const image=decodePngAlpha(path), env=makeEnv(image);
  const scale=Math.min(393/image.width,366/image.height);
  const w=image.width*scale,h=image.height*scale;
  env.img.getBoundingClientRect=()=>({left:0,top:0,right:w,bottom:h,width:w,height:h});
  const api=loadModule(env), scan=api._scanAlpha(env.img,1200);
  const rects=api._mapRunsToCss(env.img,scan).map(r=>r.map(v=>v*2.75));
  const nativeFrames=partition(rects,1080,1007);
  const verticalFrames=partition(rects.map(r=>[r[1],r[0],r[3],r[2]]),1007,1080);
  console.log(JSON.stringify({fixture:file,inputRects:rects.length,
    nativeFrames:nativeFrames.length,withinBudget:nativeFrames.length<=256,
    verticalFrames:verticalFrames.length,
    toleranceCssPx:api._outlineTolerance(env.img,scan)}));
}
