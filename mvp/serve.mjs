// 시뮬레이터용 작은 정적 서버 (ES 모듈은 file:// 에서 막히므로). 사용: node serve.mjs [port]
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const port = Number(process.argv[2] ?? process.env.PORT ?? 5175);
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".json": "application/json; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml" };

http
  .createServer((req, res) => {
    let rel;
    try {
      const url = new URL(req.url, "http://localhost");
      rel = decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname);
    } catch {
      res.writeHead(400).end("bad request"); // 잘못된 % 표기로 서버가 죽지 않게(감사 P2-11)
      return;
    }
    const file = path.join(root, path.normalize(rel));
    if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404).end("not found");
      return;
    }
    res.writeHead(200, { "content-type": types[path.extname(file)] ?? "application/octet-stream", "cache-control": "no-store" });
    fs.createReadStream(file).pipe(res);
  })
  .listen(port, "127.0.0.1", () => console.log(`글리오 헬스 시뮬레이터: http://127.0.0.1:${port}/`));
