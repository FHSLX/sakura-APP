// 最小 PNG alpha 解码器（只依赖 Node 内置 zlib）。
//
// 用途：本地验证「真实 Sakura 立绘 PNG」的轮廓简化行为，以及建立合成夹具的
// 参考 alpha 图。**只读取图像的像素/alpha 元数据**，不读取任何提示词、聊天、
// 用户配置或凭据；真实图片只读，不写入代码、提交或发布包。
//
// 支持：非交错、8/16 位深度、颜色类型 0/2/3/4/6；tRNS 透明色；调色板 alpha。
// 不支持（会抛出明确错误）：Adam7 交错、位深 < 8 的非调色板灰度。
'use strict';

const fs = require('fs');
const zlib = require('zlib');

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  if (pb <= pc) return b;
  return c;
}

function readChunks(buffer) {
  if (buffer.length < 8 || !buffer.subarray(0, 8).equals(SIGNATURE)) {
    throw new Error('not-a-png');
  }
  const chunks = [];
  let offset = 8;
  while (offset + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    if (dataEnd > buffer.length) throw new Error('truncated-chunk:' + type);
    chunks.push({ type, data: buffer.subarray(dataStart, dataEnd) });
    offset = dataEnd + 4; // 跳过 CRC
    if (type === 'IEND') break;
  }
  return chunks;
}

/** 解码 PNG，返回 { width, height, alpha: Uint8Array }。 */
function decodePngAlpha(filePath) {
  const buffer = fs.readFileSync(filePath);
  const chunks = readChunks(buffer);
  const ihdr = chunks.find((chunk) => chunk.type === 'IHDR');
  if (!ihdr || ihdr.data.length < 13) throw new Error('missing-ihdr');
  const width = ihdr.data.readUInt32BE(0);
  const height = ihdr.data.readUInt32BE(4);
  const bitDepth = ihdr.data[8];
  const colorType = ihdr.data[9];
  const compression = ihdr.data[10];
  const filterMethod = ihdr.data[11];
  const interlace = ihdr.data[12];
  if (compression !== 0 || filterMethod !== 0) throw new Error('unsupported-png-method');
  if (interlace !== 0) throw new Error('unsupported-interlace');
  if (bitDepth !== 8 && bitDepth !== 16) throw new Error('unsupported-bit-depth:' + bitDepth);

  const channelsOf = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };
  const channels = channelsOf[colorType];
  if (!channels) throw new Error('unsupported-color-type:' + colorType);
  const bytesPerSample = bitDepth / 8;
  const bpp = channels * bytesPerSample;
  const stride = width * bpp;
  const raw = zlib.inflateSync(Buffer.concat(
    chunks.filter((chunk) => chunk.type === 'IDAT').map((chunk) => chunk.data)));
  const expected = (stride + 1) * height;
  if (raw.length < expected) throw new Error('short-image-data');

  // 逐行反滤波
  const image = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y += 1) {
    const filter = raw[y * (stride + 1)];
    const src = y * (stride + 1) + 1;
    const dst = y * stride;
    const prev = dst - stride;
    for (let x = 0; x < stride; x += 1) {
      const value = raw[src + x];
      const a = x >= bpp ? image[dst + x - bpp] : 0;
      const b = y > 0 ? image[prev + x] : 0;
      const c = (x >= bpp && y > 0) ? image[prev + x - bpp] : 0;
      let out;
      switch (filter) {
        case 0: out = value; break;
        case 1: out = value + a; break;
        case 2: out = value + b; break;
        case 3: out = value + ((a + b) >> 1); break;
        case 4: out = value + paeth(a, b, c); break;
        default: throw new Error('bad-filter:' + filter);
      }
      image[dst + x] = out & 0xff;
    }
  }

  // 提取每个像素的 alpha（0..255）
  const alpha = new Uint8Array(width * height);
  const trns = chunks.find((chunk) => chunk.type === 'tRNS');
  const plte = chunks.find((chunk) => chunk.type === 'PLTE');
  const paletteAlpha = [];
  if (colorType === 3 && trns) {
    for (let i = 0; i < trns.data.length; i += 1) paletteAlpha[i] = trns.data[i];
  }
  const transparentGray = (colorType === 0 && trns && bitDepth === 8) ? trns.data[0] : -1;
  const transparentRgb = (colorType === 2 && trns && bitDepth === 8)
    ? [trns.data[0], trns.data[1], trns.data[2]] : null;

  const sample = (row, x, channel) => {
    const base = row * stride + x * bpp + channel * bytesPerSample;
    return bytesPerSample === 1 ? image[base] : image[base]; // 16 位取高字节即可
  };

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      let value = 255;
      if (colorType === 6) {
        value = sample(y, x, 3);
      } else if (colorType === 4) {
        value = sample(y, x, 1);
      } else if (colorType === 3) {
        const index = sample(y, x, 0);
        value = index < paletteAlpha.length && paletteAlpha[index] !== undefined
          ? paletteAlpha[index] : 255;
      } else if (colorType === 0) {
        value = (transparentGray >= 0 && sample(y, x, 0) === transparentGray) ? 0 : 255;
      } else if (colorType === 2) {
        if (transparentRgb
            && sample(y, x, 0) === transparentRgb[0]
            && sample(y, x, 1) === transparentRgb[1]
            && sample(y, x, 2) === transparentRgb[2]) {
          value = 0;
        } else {
          value = 255;
        }
      }
      alpha[y * width + x] = value;
    }
  }
  return { width, height, alpha, colorType, bitDepth, palette: plte ? plte.data.length / 3 : 0 };
}

/** 按行统计 alpha>=threshold 的「段」总数与逐行段数组（用于复杂度探测）。 */
function scanSegments(alpha, width, height, threshold) {
  const rows = [];
  let segments = 0;
  let opaque = 0;
  for (let y = 0; y < height; y += 1) {
    const runs = [];
    let x = 0;
    while (x < width) {
      while (x < width && alpha[y * width + x] < threshold) x += 1;
      if (x >= width) break;
      const start = x;
      while (x < width && alpha[y * width + x] >= threshold) {
        opaque += 1;
        x += 1;
      }
      runs.push([start, x]);
      segments += 1;
    }
    rows.push(runs);
  }
  return { rows, segments, opaque };
}

/** 与前端 mapRunsToCssEx 相同的 band 合并：统计最终矩形数。 */
function countRects(rows) {
  const sameRuns = (a, b) => {
    if (a === b) return true;
    if (!a || !b || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i += 1) {
      if (a[i][0] !== b[i][0] || a[i][1] !== b[i][1]) return false;
    }
    return true;
  };
  let rects = 0;
  let bandStart = -1;
  let bandRuns = null;
  const flush = (runs) => { if (runs && runs.length) rects += runs.length; };
  for (let y = 0; y < rows.length; y += 1) {
    const runs = rows[y];
    if (bandStart < 0) { bandStart = y; bandRuns = runs; continue; }
    if (sameRuns(bandRuns, runs)) continue;
    flush(bandRuns);
    bandStart = y;
    bandRuns = runs;
  }
  flush(bandRuns);
  return rects;
}

module.exports = { decodePngAlpha, scanSegments, countRects };
