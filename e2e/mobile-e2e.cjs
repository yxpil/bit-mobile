// yxpil · BIT Mobile — 全链接 E2E 启动器
// 拉起真实 BIT v0.5.14 实例（headless + 隔离数据目录 + mock-ai 上游），再驱动
// app/src/test/.../BitE2ETest.kt 走完 扫码→连接→对话→工具审批→非法用例 全链路。
// 用法：node e2e/mobile-e2e.cjs
//   环境变量：BIT_BIN（BIT 二进制，默认 BITLC debug 构建）、BIT_E2E_PORT（默认 18600）
const { spawn, execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const net = require("net");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const BITLC_E2E = process.env.BITLC_DIR || "/Users/zhenhun/Desktop/BITLC/bit/e2e";
const BIN = process.env.BIT_BIN || path.join(BITLC_E2E, "../src-tauri/target/debug/bit");
const PORT = Number(process.env.BIT_E2E_PORT) || 18600;
const KEY = "0123456789abcdef0123456789abcdef";
const PASSWORD = "87654321";
const MOCK_PORT = 9901;

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  ${detail}` : ""}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function portOpen(port, host = "127.0.0.1") {
  return new Promise((resolve) => {
    const s = net.connect({ host, port, timeout: 1500 });
    s.on("connect", () => { s.destroy(); resolve(true); });
    s.on("error", () => resolve(false));
    s.on("timeout", () => { s.destroy(); resolve(false); });
  });
}

/// 运行中同 realpath 二进制的 PID（单实例保护会让新实例秒退，先清场）
function findConflicts() {
  let realBin = BIN;
  try { realBin = fs.realpathSync(BIN); } catch {}
  try {
    return execSync("ps -axo pid=,command=", { encoding: "utf8" })
      .split("\n").map((l) => {
        const m = l.trim().match(/^(\d+)\s+(\S+)/);
        if (!m) return 0;
        let cmd = m[2];
        try { cmd = fs.realpathSync(cmd); } catch {}
        return cmd === realBin ? parseInt(m[1], 10) : 0;
      }).filter(Boolean);
  } catch { return []; }
}

async function main() {
  if (!fs.existsSync(BIN)) {
    console.error(`BIT 二进制不存在：${BIN}\n先构建（cd BITLC/bit/src-tauri && cargo build）或用 BIT_BIN 指定`);
    process.exit(2);
  }

  // 1) mock-ai 上游（已监听则复用）
  let mockProc = null;
  if (!(await portOpen(MOCK_PORT))) {
    mockProc = spawn(process.execPath, [path.join(BITLC_E2E, "mock-ai.cjs")], { stdio: "ignore" });
    const deadline = Date.now() + 10_000;
    while (!(await portOpen(MOCK_PORT))) {
      if (Date.now() > deadline) { console.error("mock-ai 10s 未就绪"); process.exit(2); }
      await sleep(300);
    }
    console.log("mock-ai 已启动（9901）");
  } else {
    console.log("mock-ai 已在运行（9901），复用");
  }

  // 2) 清理同二进制旧实例（单实例保护）
  const pids = findConflicts();
  if (pids.length) {
    try { execSync(`kill ${pids.join(" ")}`); } catch {}
    await sleep(1000);
    console.log(`已停掉旧 BIT 实例 pid=${pids.join(",")}`);
  }

  // 3) 隔离数据目录：远程访问开启 + ask 审批模式 + AI 指向 mock
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bit-mobile-e2e-"));
  fs.writeFileSync(path.join(dir, "config.json"), JSON.stringify({
    remote_enabled: true,
    host: "127.0.0.1",
    port: PORT,
    client_key: KEY,
    access_password: PASSWORD,
    password_enabled: true,
    tool_approval: "ask",
    revision: 1,
  }));
  fs.writeFileSync(path.join(dir, "ai_config.json"), JSON.stringify({
    providers: [{ id: "mock", name: "mock", protocol: "openai", base_url: `http://127.0.0.1:${MOCK_PORT}/v1`, api_key: "e2e", model: "mock", active: true }],
  }));

  // 4) 启动 headless BIT
  const bit = spawn(BIN, [], {
    env: { ...process.env, BIT_DATA_DIR: dir, BIT_HEADLESS: "1" },
    stdio: ["ignore", "ignore", "pipe"],
  });
  let errTail = "";
  bit.stderr.on("data", (d) => { errTail = (errTail + d.toString()).slice(-800); });

  let up = false;
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (bit.exitCode !== null) { console.error(`BIT 提前退出 code=${bit.exitCode} stderr: ${errTail}`); cleanup(2); return; }
    if (await portOpen(PORT)) { up = true; break; }
    await sleep(500);
  }
  if (!up) { console.error(`BIT 30s 未监听 ${PORT}，stderr: ${errTail}`); cleanup(2); return; }
  console.log(`BIT 已就绪（127.0.0.1:${PORT}，数据目录 ${dir}）`);

  // 5) 跑移动端 E2E 测试
  let code = 0;
  try {
    execSync(
      "./gradlew testDebugUnitTest --tests 'com.example.bitmodel.bit.BitE2ETest' --rerun-tasks",
      {
        cwd: ROOT,
        stdio: "inherit",
        env: { ...process.env, BIT_E2E: "1", BIT_E2E_PORT: String(PORT), BIT_E2E_KEY: KEY, BIT_E2E_PASSWORD: PASSWORD },
      },
    );
  } catch (e) {
    code = e.status ?? 1;
  }
  record("BitE2ETest（扫码→连接→对话→审批→非法用例）", code === 0, code === 0 ? "" : `gradle exit=${code}，报告：${ROOT}/app/build/reports/tests/testDebugUnitTest/index.html`);

  console.log(`\n共 ${results.length} 项，通过 ${results.filter((r) => r.ok).length} 项`);
  cleanup(code);
  process.exit(code);

  function cleanup(exitCode) {
    try { bit.kill(); } catch {}
    if (mockProc) { try { mockProc.kill(); } catch {} }
    // 失败时保留数据目录便于诊断
    if (exitCode === 0) { try { fs.rmSync(dir, { recursive: true, force: true }); } catch {} }
    else console.log(`（保留数据目录供诊断：${dir}）`);
  }
}

main();
