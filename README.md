# 象棋分析 v2.0 — 源码

Android 中国象棋分析工具：**识谱**（YOLO 棋子检测）+ **引擎算招**（皮卡鱼）+ **悬浮窗实时分析**（看别的象棋 App）+ 局势折线图 / 逐手回算。

> 历史版本说明见 `README-历史版本.md`（更早期的文档，很多内容已过时，仅作沿革参考）。

## 这个包里有什么

```
AndroidManifest.xml        应用清单（版本号、权限、组件声明）
src/                       ★ Java 源码 26 个文件 —— 全部功能都在这
res/                       资源（含无障碍服务配置 res/xml/）
libs/                      第三方 jar（onnxruntime）
build-xiangqi.mjs          构建脚本（本项目**不用 gradle**，构建靠这个脚本）
README.md                  本文件
README-历史版本.md          更早期的说明（仅供沿革参考）
```

## 核心文件一览

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 主界面：对弈 / 分析 / 局势 / 棋谱四块，左侧抽屉菜单，参数设置 |
| `Board.java` | 局面表示、走法生成、将军/合法性/困毙判定、FEN 读写 |
| `BoardView.java` | 棋盘绘制（纯 Canvas）、落子动画、箭头标注、触控 |
| `Engine.java` | 皮卡鱼 UCI 对接：限层/限时搜索、MultiPV 分析、流式逐层回调 |
| `ShotService.java` | **悬浮窗**：抓屏 → 识别 → 算招 → 悬浮提示，含自动记谱与自动换边 |
| `AutoService.java` | 无障碍服务（**只用于抓屏**，不做自动走子） |
| `DetOcr.java` | **YOLO 棋子检测**（两阶段：先找棋盘框，再在裁图上找棋子） |
| `Ocr.java` | 识谱主流程：定位棋盘 + 棋子识别 + 归一化 |
| `OnnxRec.java` / `OnnxOcr.java` / `OnnxPose.java` | PP-OCR 读字 / 分类 / 角点回归 |
| `ScanFuse.java` | 多帧投票 + 被遮挡格子回填（悬浮窗面板挡住棋盘时用） |
| `OcrActivity.java` | 扫图识谱界面（选图 + 四点校准） |
| `EvalChart.java` | 局势折线图 |
| `Notation.java` | 中文记谱法（炮二平五 / 马八进七） |
| `Diag.java` | 内置诊断日志（「关于」里可直接看，不用 adb） |

## ★ v2.0 相对 v1.0 的主要改动

（v1.0 打包于 2026-09-23；以下是之后到 2026-10-01 的变化）

1. **版本号** 1.0 → **2.0**（`versionCode` 156 → 200）；界面名称统一为「象棋分析」
2. **识图定位算法换代**：原来是「从棋子反推网格」（一维点阵 RANSAC + 语法锚定），
   子少时会把格距锁成 1/2 或 2 倍导致整盘错位；现改为
   **「框优先 + 网格刚性联合搜索」**（用棋盘框给拓扑，只搜格距和原点）
3. **修「识图后点分析永远等引擎」**：识别结果少了 180° 归一化，
   导致「红在上」的图把红方登记到黑方半场 → 局面非法 → 皮卡鱼 `exit(1)` 反复自杀
4. **主界面自动定显示方向**：识别后按原图方向决定棋盘怎么画 ——
   用户执黑时（屏幕上红在上）会自动把黑方画在下方，跟屏幕视角一致
5. **逐手回算深度 12 层 → 16 层**（单手 600ms 上限不变）
6. **删掉「自动走子」功能**（悬浮窗只分析给分，不代替用户走子）
7. 「关于」页补了一条**识谱注意**：模型仅适配「天天象棋」

## 没放进来的东西（体积大，都能从公开渠道取得）

| 缺的东西 | 体积 | 从哪来 |
|---|---|---|
| `assets/pikafish.nnue` | 48 MB | 皮卡鱼官方 release：<https://github.com/official-pikafish/Pikafish/releases> |
| `assets/chess_frame.onnx`<br>`assets/chess_pieces.onnx` | 36 MB ×2 | 本项目训练的 YOLO 模型（棋盘检测 + 棋子检测） |
| `assets/rec.onnx` `cchess_reg.onnx` `pose.onnx` | 22 MB | PP-OCR 读字 / 角点回归 / 姿态 |
| `assets/board.png`、`assets/pieces/` | 3 MB | 棋盘底图 + 棋子贴图 |
| `jniLibs/arm64-v8a/libpikafish.so` | 20 MB | 皮卡鱼官方 release 的 `Pikafish-Android-arm64-universal`，**直接改名**即为此文件 |
| `jniLibs/x86_64/*.so` | 26 MB | 模拟器用（真机不需要），NDK 从源码编译 |

## 数据来源

- **YOLO 棋子检测模型**的训练集：**by chess-we7v5** — <https://universe.roboflow.com/chess-we7v5/tt-nidj4>

## 怎么编译

需要：**JDK 17、Android SDK（platform-34 的 android.jar）、Node.js、aapt2、d8、apksigner**。
补齐上面那些资源、把 `build-xiangqi.mjs` 里的路径改成你本机的，然后：

```bash
node build-xiangqi.mjs
```

脚本分 8 步：
`aapt2 compile` → `aapt2 link` → `ecj`（编译 Java）→ `d8`（转 dex）→ `axml-override` → `zipalign` → `sign` → `install`

> ⚠️ **这个脚本不会因某一步失败而中止**，退出码始终是 0。
> 判断是否真的成功，要数输出里 `"ok": true` 的个数（**正常是 8 个**）；出现 `"ok": false` 就是某步坏了。
> ⚠️ `aapt2` 在 Windows 上会把 assets 子目录写成反斜杠，Android 的 AssetManager 按字面索引会找不到
> ⇒ 构建脚本里做了路径修正。

## 开源许可

- **引擎 Pikafish（皮卡鱼）**：**GNU GPL v3**，可自由使用 / 分发 / 商用。
  本项目**未修改其源码**，直接使用官方预编译二进制。
  - 源码：<https://github.com/official-pikafish/Pikafish>
  - 许可全文：<https://www.gnu.org/licenses/gpl-3.0.html>
  - NNUE 权重数据来自 **Pika Xiangqi Zero** 项目（ODbL）
- **ONNX Runtime**：MIT
- **PaddleOCR PP-OCR 模型**：Apache-2.0

再分发时请保留上述许可声明与源码链接。
