/*
 * scripts/gradle.cjs — 跨平台 Gradle 启动器（供 package.json 的 npm 脚本调用）
 *
 * Windows: gradlew.bat（cmd 执行），并自动指向本仓库 CLI 工具链的 JDK（_tools/jdk17）；
 *          VS Code 若早于环境变量设置启动、拿不到 JAVA_HOME 时也能打包。
 * mac/Linux: ./gradlew（可执行位丢了会自动 chmod +x），JDK 依次看
 *          HA3D_JAVA_HOME → 系统 JAVA_HOME → /usr/libexec/java_home 探测（mac）。
 *
 * 用法：node scripts/gradle.cjs assembleDebug [-Pcn] ...
 * 环境变量 HA3D_JAVA_HOME 可显式覆盖 JDK 路径（两台机器各装各的 JDK 时用）。
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const win = process.platform === 'win32';
const gradlew = win ? path.join(root, 'gradlew.bat') : path.join(root, 'gradlew');
const args = process.argv.slice(2);

const env = Object.assign({}, process.env);

/* ---------------- JDK ---------------- */

const override = env.HA3D_JAVA_HOME;
const bundledJdk = path.resolve(root, '../_tools/jdk17');
if (override && fs.existsSync(override)) {
  env.JAVA_HOME = override;
} else if (win) {
  /* Windows：仓库旁的 CLI 工具链 JDK 兜底（VS Code GUI 环境经常没有 JAVA_HOME） */
  if (!env.JAVA_HOME && fs.existsSync(bundledJdk)) env.JAVA_HOME = bundledJdk;
} else {
  /* mac：JAVA_HOME 没设时用系统 java_home 探测（VS Code 从 Dock 启动不带终端环境）。
     gradlew 自己也找得到 java，但那可能不是 AGP 要求的 17 —— 探测到就明确注入。 */
  if (!env.JAVA_HOME) {
    for (const argv of [['-v', '17'], []]) {
      const jh = spawnSync('/usr/libexec/java_home', argv, { encoding: 'utf8' });
      const p = (jh.stdout || '').trim();
      if (jh.status === 0 && p && fs.existsSync(p)) { env.JAVA_HOME = p; break; }
    }
  }
}

/* ---------------- Android SDK ---------------- */

/* SDK：仓库旁的 _tools/android-sdk 存在就注入（Windows 本机工具链）；否则按
 * mac 常见安装路径兜底，再往后才交给调用方的 ANDROID_HOME / local.properties。
 * local.properties 里刻意不写 sdk.dir（绝对路径跨平台无效），由各自机器生成。 */
const bundledSdk = path.resolve(root, '../_tools/android-sdk');
if (fs.existsSync(bundledSdk)) {
  env.ANDROID_HOME = bundledSdk;
  env.ANDROID_SDK_ROOT = bundledSdk;
} else if (!env.ANDROID_HOME && !env.ANDROID_SDK_ROOT && !win) {
  const macSdk = path.join(process.env.HOME || '', 'Library/Android/sdk');
  if (fs.existsSync(macSdk)) {
    env.ANDROID_HOME = macSdk;
    env.ANDROID_SDK_ROOT = macSdk;
  }
}

/* ---------------- gradlew 可执行位（mac/Linux） ---------------- */

/* git 里是 100755，但从 Windows 侧同步 / 解压 zip 会丢执行位，表现为
 * spawnSync 报 EACCES「权限被拒绝」——启动前静默补上，用户无感。 */
if (!win && fs.existsSync(gradlew)) {
  try { fs.chmodSync(gradlew, fs.statSync(gradlew).mode | 0o111); } catch (_) { }
}

/* ---------------- 启动 ---------------- */

/* .bat 在 Windows 上必须由 cmd 执行（shell:false 会 EINVAL）；
 * mac 直接 exec gradlew，不经 shell，参数不走二次转义。 */
const r = spawnSync(gradlew, args, { cwd: root, env, stdio: 'inherit', shell: win });
if (r.error) {
  console.error('[gradle] 启动失败：' + r.error.message);
  process.exit(1);
}
process.exit(r.status == null ? 1 : r.status);
