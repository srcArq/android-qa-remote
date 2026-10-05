#!/usr/bin/env node
// Controla un movil Android via ADB a traves de la app QA Remote. Sin dependencias.
// Uso: node qa-remote.mjs <comando> [args] [--serial X] [--ms N] [--out fichero]
//   tap X Y | longtap X Y | swipe X Y X2 Y2 | text "..." | tapText "..." | tapId id
//   wait "texto" | dump | screenshot [fichero] | back | home | recents
//   awake on|off|status (pantalla encendida durante la prueba y restaurar al acabar)
import { spawnSync } from "node:child_process";
import { existsSync, writeFileSync, readFileSync, unlinkSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";

const COMPONENT = "dev.qaremote/.CommandReceiver";
const ACTION = "dev.qaremote.CMD";
const DUMP_PATH = "/sdcard/Android/data/dev.qaremote/files/dump.json";

const argv = process.argv.slice(2);
const opts = { serial: null, ms: 0, out: null, adb: null };
const pos = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === "--serial" || a === "-s") opts.serial = argv[++i];
  else if (a === "--ms") opts.ms = Number(argv[++i]);
  else if (a === "--out" || a === "-o") opts.out = argv[++i];
  else if (a === "--adb") opts.adb = argv[++i];
  else pos.push(a);
}
const [command, ...rest] = pos;

function findAdb() {
  if (opts.adb) return opts.adb;
  const probe = spawnSync("adb", ["version"], { encoding: "utf8" });
  if (!probe.error) return "adb";
  const sdk = process.env.LOCALAPPDATA
    ? join(process.env.LOCALAPPDATA, "Android", "Sdk", "platform-tools", "adb.exe")
    : join(process.env.HOME || "", "Android", "Sdk", "platform-tools", "adb");
  if (existsSync(sdk)) return sdk;
  fail("No encuentro adb (PATH o SDK).");
}
const ADB = findAdb();

function adb(args, binary = false) {
  const full = [...(opts.serial ? ["-s", opts.serial] : []), ...args];
  const r = spawnSync(ADB, full, { encoding: binary ? null : "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (r.error) fail(String(r.error));
  return r;
}

function fail(msg) {
  console.error(msg.startsWith("ERROR") ? msg : `ERROR: ${msg}`);
  process.exit(1);
}

function need(n) {
  if (rest.length < n) fail(`El comando '${command}' necesita ${n} argumento(s).`);
}

const b64 = (s) => Buffer.from(s, "utf8").toString("base64");
const num = (v) => {
  if (!/^-?\d+(\.\d+)?$/.test(String(v))) fail(`Valor numerico no valido: ${v}`);
  return String(v);
};

function send(cmd, extras = {}) {
  let line = `am broadcast -n ${COMPONENT} -a ${ACTION} --es cmd ${cmd}`;
  for (const [k, v] of Object.entries(extras)) line += ` --es ${k} ${v}`;
  const r = adb(["shell", line]);
  const out = `${r.stdout || ""}${r.stderr || ""}`;
  const m = out.match(/result=(-?\d+)(?:, data="([\s\S]*?)")?\s*$/m);
  if (!m) fail(`salida inesperada de am broadcast: ${out.trim()}`);
  const code = Number(m[1]);
  if (code === 0) {
    console.log(m[2]);
    return true;
  }
  if (code === -1 || !m[2]) fail(`sin respuesta de la app (¿instalada?) · ${out.trim()}`);
  fail(m[2]);
}

const textArg = () => b64(rest.join(" "));

switch (command) {
  case "tap":
    need(2);
    send("tap", { x: num(rest[0]), y: num(rest[1]) });
    break;
  case "longtap":
    need(2);
    send("longtap", { x: num(rest[0]), y: num(rest[1]), ms: num(opts.ms || 800) });
    break;
  case "swipe":
    need(4);
    send("swipe", {
      x: num(rest[0]), y: num(rest[1]), x2: num(rest[2]), y2: num(rest[3]), ms: num(opts.ms || 300),
    });
    break;
  case "back":
  case "home":
  case "recents":
    send(command);
    break;
  case "text":
  case "tapText":
  case "tapId":
    need(1);
    send(command, { b64: textArg() });
    break;
  case "wait":
    need(1);
    send("wait", { b64: textArg(), ms: num(opts.ms || 5000) });
    break;
  case "dump": {
    send("dump");
    const tmp = opts.out ? resolve(opts.out) : join(tmpdir(), "qaremote-dump.json");
    const r = adb(["pull", DUMP_PATH, tmp]);
    if (r.status !== 0) fail(`adb pull fallo: ${r.stderr}`);
    const json = readFileSync(tmp, "utf8");
    if (!opts.out) unlinkSync(tmp);
    console.log(json);
    break;
  }
  case "screenshot": {
    const file = resolve(opts.out || rest[0] || `screenshot-${Date.now()}.png`);
    const r = adb(["exec-out", "screencap", "-p"], true);
    if (!r.stdout || r.stdout.length === 0) fail("captura vacia (¿FLAG_SECURE?)");
    writeFileSync(file, r.stdout);
    console.log(`OK: captura en ${file}`);
    break;
  }
  case "awake": {
    // adb directo: 'settings put system' y 'svc power' funcionan aunque el
    // fabricante bloquee la inyeccion. Mismo fichero de estado que qa-remote.ps1.
    need(1);
    const id = opts.serial || (adb(["get-serialno"]).stdout || "").trim();
    const base = process.env.LOCALAPPDATA || join(process.env.HOME || tmpdir(), ".local", "share");
    const state = join(base, "qa-remote", `awake-${id}.txt`);
    const sh = (c) => (adb(["shell", c]).stdout || "").trim();
    if (rest[0] === "on") {
      if (!existsSync(state)) {
        mkdirSync(dirname(state), { recursive: true });
        writeFileSync(state, sh("settings get system screen_off_timeout"));
      }
      sh("settings put system screen_off_timeout 1800000");
      sh("svc power stayon usb");
      console.log(`OK: awake on · pantalla encendida mientras haya USB (original guardado: ${readFileSync(state, "utf8").trim()})`);
    } else if (rest[0] === "off") {
      const orig = existsSync(state) ? readFileSync(state, "utf8").trim() : "30000";
      sh(`settings put system screen_off_timeout ${num(orig)}`);
      sh("svc power stayon false");
      if (existsSync(state)) unlinkSync(state);
      console.log(`OK: awake off · bloqueo restaurado a ${orig} ms`);
    } else if (rest[0] === "status") {
      const stay = sh("dumpsys power").split("\n").find((l) => l.includes("mStayOn=")) || "";
      const saved = existsSync(state) ? readFileSync(state, "utf8").trim() : "(ninguno)";
      console.log(`OK: screen_off_timeout=${sh("settings get system screen_off_timeout")} · ${stay.trim()} · original guardado=${saved}`);
    } else fail("Uso: awake on|off|status");
    break;
  }
  default:
    fail(
      "Uso: node qa-remote.mjs <tap|longtap|swipe|text|back|home|recents|tapText|tapId|dump|screenshot|wait|awake> [args] [--serial X] [--ms N] [--out f]",
    );
}
