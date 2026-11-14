// 시뮬레이터 장면(?scene=1..14)을 헤드리스 Chrome 으로 캡처 → 자막만(무음) ffmpeg mp4. tts() 는 예전 음성판용으로 남겨 둠
// 사용: (시뮬레이터 서버 실행 중) node make-video.mjs <출력폴더> [http://127.0.0.1:5175]
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const out = path.resolve(process.argv[2] ?? "out");
const base = process.argv[3] ?? "http://127.0.0.1:5175";
fs.mkdirSync(out, { recursive: true });

const CHROME = ["C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].find((p) => fs.existsSync(p));
if (!CHROME) throw new Error("Chrome/Edge 를 찾지 못함");

// 2026-11-15 결정 D-12: 음성 없이 자막(화면의 캡션·말풍선·슬라이드)만. 장면 길이는 화면 글자 양에 맞춰 읽을 시간을 준다.
const SECONDS = { 1: 6, 2: 7, 3: 5, 4: 5.5, 5: 4.5, 6: 6.5, 7: 4.5, 8: 7, 9: 6, 10: 8, 11: 5.5, 12: 7, 13: 10, 14: 9 };
const narration = SECONDS; // 장면 번호 목록으로만 쓴다(음성 없음)

function tts(text, wav) {
  // 한글이 콘솔 입력 인코딩에서 깨지지 않도록 UTF-8 파일로 넘긴다 (깨지면 엉뚱한 소리를 길게 읽는다)
  const txt = wav.replace(/\.wav$/, ".txt");
  fs.writeFileSync(txt, text, "utf8");
  const ps = `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer;
    $v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1;
    if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }; $s.Rate = 2; $s.SetOutputToWaveFile('${wav.replace(/'/g, "''")}');
    $s.Speak([IO.File]::ReadAllText('${txt.replace(/'/g, "''")}', [Text.Encoding]::UTF8)); $s.Dispose()`;
  execFileSync("powershell", ["-NoProfile", "-Command", ps], { encoding: "utf8" });
}
const duration = (file) => Number(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file], { encoding: "utf8" }).trim());

const parts = [];
for (const n of Object.keys(narration).map(Number)) {
  const png = path.join(out, `scene-${String(n).padStart(2, "0")}.png`);
  const wav = path.join(out, `scene-${String(n).padStart(2, "0")}.wav`);
  const mp4 = path.join(out, `scene-${String(n).padStart(2, "0")}.mp4`);
  execFileSync(CHROME, ["--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1", "--window-size=1920,1080", "--virtual-time-budget=3000", `--screenshot=${png}`, `${base}/?scene=${n}`], { stdio: "ignore" });
  const d = SECONDS[n];
  // 소리는 없지만 재생기 호환을 위해 무음 오디오 트랙을 넣는다
  execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-loop", "1", "-i", png, "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", d.toFixed(2),
    "-vf", "scale=1920:1080,format=yuv420p,fade=t=in:st=0:d=0.3", "-r", "30", "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-ar", "44100", "-ac", "2", mp4]);
  parts.push(mp4);
  console.log(`scene ${n}: ${d.toFixed(1)}s`);
}
const list = path.join(out, "list.txt");
fs.writeFileSync(list, parts.map((p) => `file '${p.replace(/\\/g, "/")}'`).join("\n"));
const final = path.join(out, "gleo-health-demo.mp4");
execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list, "-c", "copy", final]);
console.log(`완료: ${final} (${duration(final).toFixed(1)}초)`);
