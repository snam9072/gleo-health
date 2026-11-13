// 시뮬레이터 장면(?scene=1..12)을 헤드리스 Chrome 으로 캡처 + Windows 한국어 음성(SAPI)으로 내레이션 → ffmpeg 로 mp4
// 사용: (시뮬레이터 서버 실행 중) node make-video.mjs <출력폴더> [http://127.0.0.1:5175]
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const out = path.resolve(process.argv[2] ?? "out");
const base = process.argv[3] ?? "http://127.0.0.1:5175";
fs.mkdirSync(out, { recursive: true });

const CHROME = ["C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].find((p) => fs.existsSync(p));
if (!CHROME) throw new Error("Chrome/Edge 를 찾지 못함");

const narration = {
  1: "글리오 헬스. 좌석마다 안전벨트와 휴식을 챙기고, 보험 혜택으로 돌려줍니다.",
  2: "뒷좌석 착용률은 28에서 70퍼센트. 안 매면 치사율이 최대 3.7배입니다.",
  3: "가족 네 명 탑승. 정차 중에는 알리지 않습니다.",
  4: "출발하면 좌석 이름으로 한 번만. 뒷좌석 오른쪽 안전벨트를 매 주세요.",
  5: "모두 착용. 좌석별 체결 시간을 기록합니다.",
  6: "두 시간째 운전 중이에요. 휴게소에 들를까요? 말 한마디로 경유지 추가.",
  7: "15분 넘게 쉬면 휴식으로 인정합니다.",
  8: "출발 전, 저장된 자세로 되돌릴까요? 주행 중에는 시트를 움직이지 않습니다.",
  9: "점수는 정차 중에만. 벨트 80, 휴식 20퍼센트.",
  10: "동의한 운전자만, 점수만 보험사로. 기록은 할인에만 씁니다.",
  11: "글리오, 자리 벨트 점검해 줘. 모두 맸어요.",
  12: "Pleos SDK와 Gleo 연동으로 만들었습니다. 보험사 B2B로 시작해 렌트, 법인차, 블루핸즈로. 글리오 헬스.",
};

function tts(text, wav) {
  const ps = `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer;
    $v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1;
    if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }; $s.Rate = 2; $s.SetOutputToWaveFile('${wav.replace(/'/g, "''")}');
    $s.Speak([Console]::In.ReadToEnd()); $s.Dispose()`;
  execFileSync("powershell", ["-NoProfile", "-Command", ps], { input: text, encoding: "utf8" });
}
const duration = (file) => Number(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file], { encoding: "utf8" }).trim());

const parts = [];
for (const n of Object.keys(narration).map(Number)) {
  const png = path.join(out, `scene-${String(n).padStart(2, "0")}.png`);
  const wav = path.join(out, `scene-${String(n).padStart(2, "0")}.wav`);
  const mp4 = path.join(out, `scene-${String(n).padStart(2, "0")}.mp4`);
  execFileSync(CHROME, ["--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1", "--window-size=1920,1080", "--virtual-time-budget=3000", `--screenshot=${png}`, `${base}/?scene=${n}`], { stdio: "ignore" });
  tts(narration[n], wav);
  const d = duration(wav) + 0.4; // 장면 사이 여백
  execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-loop", "1", "-i", png, "-i", wav, "-af", "apad", "-t", d.toFixed(2),
    "-vf", "scale=1920:1080,format=yuv420p,fade=t=in:st=0:d=0.3", "-r", "30", "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-ar", "44100", "-ac", "2", mp4]);
  parts.push(mp4);
  console.log(`scene ${n}: ${d.toFixed(1)}s`);
}
const list = path.join(out, "list.txt");
fs.writeFileSync(list, parts.map((p) => `file '${p.replace(/\\/g, "/")}'`).join("\n"));
const final = path.join(out, "gleo-health-demo.mp4");
execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list, "-c", "copy", final]);
console.log(`완료: ${final} (${duration(final).toFixed(1)}초)`);
