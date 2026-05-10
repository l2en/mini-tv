const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = 3456;
const WALLPAPER_DIR = path.join(__dirname, 'wallpaper');
const IMAGE_EXTS = ['.jpg', '.jpeg', '.png', '.bmp', '.webp'];

const MIME_TYPES = {
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
  '.bmp': 'image/bmp',
  '.webp': 'image/webp',
};

function getImageFiles() {
  try {
    return fs.readdirSync(WALLPAPER_DIR)
      .filter(f => IMAGE_EXTS.includes(path.extname(f).toLowerCase()))
      .sort();
  } catch (e) {
    return [];
  }
}

function getLocalIP() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');

  // GET /api/list — 返回图片列表
  if (url.pathname === '/api/list') {
    const files = getImageFiles();
    const ip = getLocalIP();
    const list = files.map(f => `http://${ip}:${PORT}/image/${encodeURIComponent(f)}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ count: list.length, images: list }));
    return;
  }

  // GET /image/:filename — 返回图片文件
  if (url.pathname.startsWith('/image/')) {
    const filename = decodeURIComponent(url.pathname.slice(7));
    // 安全检查：防止路径穿越
    if (filename.includes('..') || filename.includes('/')) {
      res.writeHead(400);
      res.end('Bad Request');
      return;
    }
    const filePath = path.join(WALLPAPER_DIR, filename);
    if (!fs.existsSync(filePath)) {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    const ext = path.extname(filename).toLowerCase();
    const mime = MIME_TYPES[ext] || 'application/octet-stream';
    const stat = fs.statSync(filePath);
    res.writeHead(200, {
      'Content-Type': mime,
      'Content-Length': stat.size,
      'Cache-Control': 'public, max-age=3600',
    });
    fs.createReadStream(filePath).pipe(res);
    return;
  }

  // 其他路径
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  const files = getImageFiles();
  res.end(`壁纸服务运行中\n图片数量: ${files.length}\n\n接口:\n  GET /api/list    — 图片列表(JSON)\n  GET /image/:name — 获取图片\n`);
});

server.listen(PORT, '0.0.0.0', () => {
  const ip = getLocalIP();
  console.log(`\n壁纸服务已启动`);
  console.log(`  地址: http://${ip}:${PORT}`);
  console.log(`  图片目录: ${WALLPAPER_DIR}`);
  console.log(`  图片数量: ${getImageFiles().length}`);
  console.log(`\n把图片放到 wallpaper/ 目录即可，服务会自动识别。\n`);
});
