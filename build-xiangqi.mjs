import fs from 'node:fs';
import path from 'node:path';
import cp from 'node:child_process';
const java = 'D:\\dsh\\apkwork\\tools\\jre17\\bin\\java.exe';
const proj = 'D:\\dsh\\xiangqi';
const build = proj + '\\build';
const aapt2bin = 'D:\\dsh\\apkwork\\bt-new\\aapt2.exe';
const bt = 'D:\\dsh\\apkwork\\bt34\\android-14';
const androidJar = 'D:\\dsh\\apkwork\\platform34\\android.jar';
const adb = 'D:\\dsh\\apkwork\\sdk\\platform-tools\\adb.exe';
// 只要出 APK 不要装：手机上正在用（装了会把 App 杀掉、正在下的一局就没了）就用 SKIP_INSTALL=1 跑。
const skipInstall = process.env.SKIP_INSTALL === '1';
const portable = process.env.PORTABLE === '1';
const appId = portable ? 'com.dsh.xiangqi.lite' : 'com.dsh.xiangqi';
const LOG = 'D:\\dsh\\apkwork\\build.log';
// 用 spawnSync + 重定向到日志文件，避免管道 stdio（受限沙箱下管道会 EPERM）
function run(name, cmd, timeout = 300000) {
  try {
    // Redirecting inside cmd.exe fails to initialize child processes on some Windows hosts
    // (0xC0000142). Give the child a real log-file handle rather than a shell redirect.
    const logFd = fs.openSync(LOG, 'a');
    let r;
    try { r = cp.spawnSync(cmd, { shell: true, timeout, stdio: ['ignore', logFd, logFd], windowsHide: true }); }
    finally { fs.closeSync(logFd); }
    if (r.error) return { name, ok: false, out: 'spawn: ' + String(r.error).slice(0, 160) };
    if (r.status === 0) return { name, ok: true, out: '' };
    let tail = '';
    try { tail = fs.readFileSync(LOG, 'utf8').slice(-800).replace(/\s+/g, ' '); } catch (e2) {}
    return { name, ok: false, out: 'exit ' + r.status + ' | ' + tail.slice(-400) };
  } catch (e) { return { name, ok: false, out: String(e).slice(0, 200) }; }
}
const steps = [];
try { fs.writeFileSync(LOG, ''); } catch (e) {}
fs.mkdirSync(build, { recursive: true });
fs.mkdirSync(build + '\\dex', { recursive: true });
fs.rmSync(build + '\\classes', { recursive: true, force: true });
fs.mkdirSync(build + '\\classes', { recursive: true });
steps.push(run('aapt2 compile', '"' + bt + '\\aapt2.exe" compile --dir "' + proj + '\\res" -o "' + build + '\\res.zip"'));
let manifestPath = proj + '\\AndroidManifest.xml';
let srcDir = proj + '\\src\\com\\dsh\\xiangqi';
if (portable) {
  // Lite 不打补丁到原版源码上：把原版源码拷一份到 build\lite-src，只在那儿做定点补丁。
  // 原版目录 xiangqi\src 永远是"原本的代码"，完整版 APK 就是拿它编译的。
  const litePatch = await import('file:///D:/dsh/apkwork/lite-patch.mjs');
  try {
    srcDir = litePatch.buildLiteSources();
  } catch (e) {
    // 补丁打不上说明原版源码结构变了，必须立刻停下：绝不用"猜着改"的源码出包。
    steps.push({ name: 'lite-src-patch', ok: false, out: String(e.message || e).slice(0, 300) });
    console.log(JSON.stringify(steps, null, 1));
    console.error('BUILD ABORTED: lite patch failed, refusing to build Lite from unpatched sources.');
    process.exit(1);
  }
  fs.writeFileSync(build + '\\lite-src-ok.txt', new Date().toISOString(), 'utf8');
  steps.push({ name: 'lite-src-patch', ok: true, out: srcDir });
  manifestPath = build + '\\AndroidManifest-lite.xml';
  let xml = fs.readFileSync(proj + '\\AndroidManifest.xml', 'utf8');
  // ★ 2026-09-29：用户要求「code 标号取消」—— 界面不再显示 versionCode，
  //   但 manifest 里这个字段必须保留（系统靠它判断升级/覆盖），所以给它一个够大的值 200。
  //   versionName 由原版 manifest 的 2.0 继承过来，这里只改 code 和 label。
  xml = xml.replace('package="com.dsh.xiangqi"', 'package="com.dsh.xiangqi.lite"')
           .replace('    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />',
                    '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />')
           .replace(/android:versionCode="\d+"/, 'android:versionCode="200"')
           // ★ 两处 `android:label="@string/app_name"` 要换成不同的名字：
           //   ① application 的 → 桌面图标名「象棋分析lite」
           //   ② AutoService 的 → 系统「无障碍」列表里显示的名字
           //   而 strings.xml 只有一份（不派生），所以都得换成字面量。
           //   ★★ 判据：**同一标签内 label 后面紧跟 BIND_ACCESSIBILITY_SERVICE 的那个**才是 AutoService。
           //      用 [^<]*? 卡住"不跨标签"，避免非贪婪匹配从 application 那行一路跨过去。
           //   ★ 锚点用 [\s\S] 系列而不是写死 \n —— manifest 存的是 CRLF。
           .replace(/android:label="@string\/app_name"([^<]*?BIND_ACCESSIBILITY_SERVICE)/,
                    'android:label="象棋分析lite（识谱）"$1')
           .replace('android:label="@string/app_name"', 'android:label="象棋分析lite"')
           .replace(/android:name="\.([A-Za-z]+)/g, 'android:name="com.dsh.xiangqi.$1');
  fs.writeFileSync(manifestPath, xml, 'utf8');
}
// 每次都清掉旧 R.java：Lite 用 --custom-package 固定生成到 com.dsh.xiangqi，避免吃到上一次的陈旧文件。
fs.rmSync(build + '\\gen', { recursive: true, force: true });
steps.push(run('aapt2 link', '"' + bt + '\\aapt2.exe" link -o "' + build + '\\base.apk" -I "' + androidJar + '" --manifest "' + manifestPath + '" -R "' + build + '\\res.zip" --auto-add-overlay -A "' + proj + '\\assets" --java "' + build + '\\gen" --custom-package com.dsh.xiangqi --min-sdk-version 21 --target-sdk-version 34'));
if (portable) steps.push({ name: 'lite-manifest', ok: true, out: appId });
const srcs = fs.readdirSync(srcDir).map(f => srcDir + '\\' + f);
const rjava = build + '\\gen\\com\\dsh\\xiangqi\\R.java';
const list = srcs.concat(fs.existsSync(rjava) ? [rjava] : []).map(s => '"' + s + '"').join(' ');
const ecjStep = run('ecj', '"' + java + '" -jar "D:\\dsh\\apkwork\\tools\\ecj.jar" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn -cp "' + androidJar + ';D:\\dsh\\xiangqi\\libs\\onnxruntime.jar" -d "' + build + '\\classes" ' + list, 180000);
steps.push(ecjStep);
// 硬闸门：编译失败必须立刻中止。否则后面会拿"上一次的旧 class"打包并安装，
// 表现为"改了代码但手机上没生效"——极难排查（曾踩过）。
if (!ecjStep.ok) {
  console.log(JSON.stringify(steps, null, 1));
  console.error('BUILD ABORTED: ecj failed, refusing to package stale classes.');
  process.exit(1);
}
const classFiles = [];
(function walk(d) { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else if (e.name.endsWith('.class')) classFiles.push(p); } })(build + '\\classes');
classFiles.push(proj + '\\libs\\onnxruntime.jar');
const d8Args = build + '\\d8args.txt';
fs.writeFileSync(d8Args, classFiles.join('\n'), 'utf8');
steps.push(run('d8', '"' + java + '" -cp "' + bt + '\\lib\\d8.jar" com.android.tools.r8.D8 --lib "' + androidJar + '" --min-api 21 --output "' + build + '\\dex" @' + d8Args));
const mod = await import('file:///D:/dsh/apkwork/ziputil.mjs');
const base = mod.readZip(fs.readFileSync(build + '\\base.apk'));
const items = [];
// 体积优化：aapt2 交出来的条目里，.so 和 rec.onnx 都是「不压缩」存的（method 0）。
// 只有 resources.arsc 必须保持不压缩（系统要 mmap 它），其余全部改成 deflate。
// 本工程 manifest 里 android:extractNativeLibs="true"，所以 .so 压缩存储完全合法
// ——安装时系统会自己解到 nativeLibraryDir，Engine.binaryPath() 那条路不受影响。
// 实测收益：libonnxruntime.so 17.57MB -> ~6MB，合计省 ~14MB。
const KEEP_STORED = new Set(['resources.arsc']);
for (const e of base.entries) {
  const assetNameForFilter = String(e.name).split('\\').join('/');
  if (portable && ['assets/pikafish.nnue', 'assets/chess_frame.onnx', 'assets/chess_pieces.onnx'].includes(assetNameForFilter)) continue;
  // ★ 必须存成 '/'：aapt2 在 Windows 上会把 assets 的**子目录**写成反斜杠
  //   （实测 base.apk 里是 `assets/pieces\red_king.png`，前缀正斜杠、子路径反斜杠）。
  //   Android 的 AssetManager 是按字面建索引的，带反斜杠的条目永远 open() 不到
  //   —— 症状就是 `am.open("pieces/xxx.png")` 抛 FileNotFoundException。
  //   注意：Python 的 zipfile 会把 '\' 显示成 '/'，所以光看它看不出来，得 dump 原始字节。
  const normalizeName = String(e.name).split('\\').join('/');
  const lo = e.localOff, nl = base.buf.readUInt16LE(lo + 26), xl = base.buf.readUInt16LE(lo + 28);
  if (!KEEP_STORED.has(normalizeName) && e.method === 0) {
    // 原来是原样存的（.so / rec.onnx）：先解出来，交给 writeZip 重新 deflate。
    // writeZip 发现压不小会自动退回 method 0，所以这里不会把体积压大。
    items.push({ name: normalizeName, data: base.getData(e), method: 8 });
  } else {
    items.push({ name: normalizeName, method: normalizeName === 'resources.arsc' ? 0 : e.method, crc: e.crc, uncompSize: e.uncompSize, fromBuf: { buf: base.buf, start: lo + 30 + nl + xl, len: e.compSize } });
  }
}
items.push({ name: 'classes.dex', data: fs.readFileSync(build + '\\dex\\classes.dex'), method: 8 });
for (const abi of ['arm64-v8a']) {
  for (const so of ['libpikafish.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so']) {
    const p = proj + '\\jniLibs\\' + abi + '\\' + so;
    if (fs.existsSync(p)) items.push({ name: 'lib/' + abi + '/' + so, data: fs.readFileSync(p), method: 8 });
  }
}
// 用自写 AXML 覆盖无障碍配置：aapt2 会丢掉 canPerformGestures 等 API24+ 属性，
// 少了它服务就没有"模拟点击"能力，自动走子必然失败。
try {
  cp.spawnSync('"' + aapt2bin + '" dump resources "' + build + '\\base.apk" > "' + build + '\\res_dump.txt" 2>&1', { shell: true, stdio: 'ignore', windowsHide: true });
  const dump = fs.readFileSync(build + '\\res_dump.txt', 'utf8');
  const m = dump.match(/resource (0x[0-9a-fA-F]+) string\/auto_service_desc/);
  if (m) {
    const ax = await import('file:///D:/dsh/apkwork/axml.mjs');
    const cfg = ax.buildAccessibilityConfig(parseInt(m[1], 16));
    const nm = 'res/xml/accessibility_service_config.xml';
    const k = items.findIndex(function (it) { return it.name === nm; });
    const entry = { name: nm, data: cfg, method: 0 };
    if (k >= 0) items[k] = entry; else items.push(entry);
    steps.push({ name: 'axml-override', ok: true, out: 'desc=0x' + m[1].slice(2) + ' bytes=' + cfg.length });
  } else {
    steps.push({ name: 'axml-override', ok: false, out: 'desc string not found' });
  }
} catch (e) {
  steps.push({ name: 'axml-override', ok: false, out: String(e).slice(0, 160) });
}

fs.writeFileSync(build + '\\u.apk', mod.writeZip(items));
steps.push(run('zipalign', '"' + bt + '\\zipalign.exe" -f -p 4 "' + build + '\\u.apk" "' + build + '\\a.apk"', 120000));
const apkOut = build + (portable ? '\\xiangqi-portable.apk' : '\\xiangqi.apk');
steps.push(run('sign', '"' + java + '" -cp "D:\\dsh\\apkwork\\tools\\apksig-new.jar;D:\\dsh\\apkwork\\classes3" Sign3 "D:\\dsh\\apkwork\\tools\\key.der" "D:\\dsh\\apkwork\\tools\\cert.der" "' + build + '\\a.apk" "' + apkOut + '"', 180000));
if (skipInstall) steps.push({ name: 'install', ok: true, out: 'SKIPPED (SKIP_INSTALL=1)' });
else steps.push(run('install', '"' + adb + '" install -r "' + apkOut + '"', 180000));
console.log(JSON.stringify(steps, null, 1));
console.log('APK=' + apkOut);
console.log('APKKB=' + Math.round(fs.statSync(apkOut).size / 1024));
