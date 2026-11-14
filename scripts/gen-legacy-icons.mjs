/*
 * gen-legacy-icons.mjs — 生成传统 PNG 启动图标（非自适应图标环境的兜底，如卓易通容器）。
 * 用法：node scripts/gen-legacy-icons.mjs   （在 ha3d-app 根目录执行）
 * 纯 Node 无依赖：手写 PNG 编码（RGBA + zlib + CRC32），蓝底白房子。
 */
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const RES = join(root, 'app/src/main/res');

const SIZES = [
  ['mipmap-mdpi', 48], ['mipmap-hdpi', 72], ['mipmap-xhdpi', 96],
  ['mipmap-xxhdpi', 144], ['mipmap-xxxhdpi', 192],
];

/* ---------- PNG 编码 ---------- */
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
const crc32 = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
};
function encodePNG(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8bit RGBA
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/* ---------- 画房子（3x3 超采样抗锯齿） ---------- */
const BG = [47, 111, 237];   // #2F6FED
const FG = [255, 255, 255];

/* 点是否在房子图形内（坐标 0..1） */
function inside(x, y) {
  // 人字顶：三角 (0.5,0.30) (0.14,0.60) (0.86,0.60)；屋身：矩形 x 0.22..0.78, y 0.52..0.78
  const inRoof = y >= 0.30 && y <= 0.60 &&
    x >= 0.5 - (y - 0.30) * (0.36 / 0.30) * 0.5 && x <= 0.5 + (y - 0.30) * (0.36 / 0.30) * 0.5;
  const inBody = x >= 0.22 && x <= 0.78 && y >= 0.52 && y <= 0.78;
  if (!(inRoof || inBody)) return false;
  // 门：x 0.44..0.56, y 0.62..0.78 镂空
  return !(x >= 0.44 && x <= 0.56 && y >= 0.62 && y <= 0.78);
}

function drawIcon(size) {
  const rgba = Buffer.alloc(size * size * 4);
  const S = 3; // 超采样
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let hit = 0;
      for (let sy = 0; sy < S; sy++) {
        for (let sx = 0; sx < S; sx++) {
          const x = (px + (sx + 0.5) / S) / size;
          const y = (py + (sy + 0.5) / S) / size;
          if (inside(x, y)) hit++;
        }
      }
      const t = hit / (S * S);
      const o = (py * size + px) * 4;
      rgba[o] = FG[0] * t + BG[0] * (1 - t);
      rgba[o + 1] = FG[1] * t + BG[1] * (1 - t);
      rgba[o + 2] = FG[2] * t + BG[2] * (1 - t);
      rgba[o + 3] = 255;
    }
  }
  return encodePNG(size, size, rgba);
}

for (const [dir, size] of SIZES) {
  const out = join(RES, dir);
  mkdirSync(out, { recursive: true });
  writeFileSync(join(out, 'ic_launcher.png'), drawIcon(size));
  console.log('OK', dir, size + 'x' + size);
}
