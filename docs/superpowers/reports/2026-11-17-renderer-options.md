# 渲染方案比较：WSL、只读 Viewer 与跨平台

> **状态**：调研输入（2026-11-17）  
> **本文性质**：技术方案比较与工程建议，不是用户裁定。  
> **局部取代（2026-11-19）**：首版开工和技术选型见[只读地图查看器设计](../specs/2026-11-19-readonly-map-viewer.md)。
> **用户原话（逐字，2026-11-17）**：
>
> 「问题是我用的是wsl啊」
>
> 「不过复杂的编辑器倒是不用，因为编辑工作目前事实上是Agent主做，给人看的客户端主要用于查看」
>
> 「先记录一下裁定和设计，然后为我介绍一下WebGL2/WebGPU + Worker + 二进制 buffer、 LWJGL + Dear ImGui等方案吧，最好是跨平台通用性好一些的」
>
> **前置设计**：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md)
> **结论边界**：当前不选型、不写渲染器代码；本报告只做比较，等待用户裁定。

---

## 0. 结论先行

**首选：WSL 内 headless core + Windows 浏览器里的只读 WebGL2 Viewer；WebGPU 作为可选增强。**

理由：

1. 用户的运行环境是 WSL；人类客户端在 Windows 上用浏览器打开 `localhost` 最省事，不依赖 WSLg、X11、Linux GUI 库或显卡直通细节。
2. 人类客户端只做查看，不做复杂编辑器，这大幅降低了 Web 前端的复杂度：不需要拖拽编辑、属性面板、复杂表单、撤销栈，只需要：
   - 地图/省份/显示坐标；
   - 单位标记/选中轮廓；
   - 时间线节点切换；
   - ORBAT/INFO 的只读详情。
3. WebGL2 的浏览器覆盖最广，适合“跨平台通用性优先”；WebGPU 作为新后端，可在支持的浏览器上提供更低开销和 compute，但需要 WebGL2 fallback。
4. 渲染器必须与 core 解耦：core 只输出 polygon 状态 + `displayPoint` + 二进制 viewer payload，不负责窗口/浏览器/GPU。

不推荐首版做 **JavaFX/LWJGL 原生 GUI**：它们在 WSL 下需要 WSLg/X11 和 Linux 图形栈，跨 Windows 使用体验反而更麻烦。  
如果以后要离线桌面 viewer，再考虑 **Rust/wgpu** 或 **LWJGL + Dear ImGui**。

---

## 1. 先明确“查看器”需要什么

只读查看器的核心需求：

| 能力 | 频率 | 说明 |
|---|---|---|
| 地图 polygon/边界 | 低频 | 可服务端预计算、LOD、裁剪 |
| 省/州/区域高亮 | 中 | 选中/搜索/时间线切换 |
| 单位标记和选中轮廓 | 高 | 人主要看态势，不一定看每个 polygon |
| 时间线节点切换 | 中 | `tree1-360` 查询 |
| ORBAT/INFO 详情 | 中 | 树/文本/表格 |
| 图层开关 | 低频 | 地形/州/区域/补给/单位 |
| 搜索/跳转 | 中 | 按 ID/名称/地址 |

不需要：

- 在地图上直接绘制新省界（Agent 做）；
- 复杂表单/属性编辑；
- 多窗口/插件 UI；
- 本地撤销/重做（时间线 fork 替代）。

这使“只读 Web Viewer”成为合理选择，而不是“重做 WebUI 编辑器”。

---

## 2. 方案比较

### 2.1 WebGL2 + Web Worker + 二进制 buffer

**结构**：

```text
Java core (WSL)
  └─ HTTP/WebSocket (localhost)
       └─ Windows browser
            ├─ main thread: WebGL2 draw loop, input, UI
            ├─ Worker: fetch/parse binary tile, build typed arrays
            └─ GPU: vertex/index buffers, outlines, points, labels
```

**关键做法**：

- 服务端输出二进制 tile，而不是每帧/每次交互返回 JSON；
- 几何 buffer：
  - `Float32Array` positions；
  - `Uint32Array` indices；
  - `Uint32Array` province/unit IDs（用于 picking）；
  - 可选 per-instance attributes（颜色、选中状态、图层）；
- Worker 只负责：接收 `ArrayBuffer` → 解析/解压 → 构建 typed arrays → `postMessage` transferables；主线程只上传 GPU；
- 视口裁剪 + LOD：服务端/客户端只保留当前屏幕需要的 polygon/简化轮廓；
- 选中轮廓/单位标记优先用 point/instanced quad、顶点 buffer；不要给每个省建 DOM 节点；
- 可选 `OffscreenCanvas` 把渲染移到 Worker；`SharedArrayBuffer` 需要 COOP/COEP，首版可不用；
- 拾取可用 ID framebuffer 或 CPU 空间索引（JTS STRtree/服务端索引），不要做逐 polygon CPU 命中循环。

**优点**：

- 浏览器覆盖最广（现代 Windows/macOS/Linux/Android/iOS 基本都有 WebGL2）；
- 浏览器在 Windows 上直接使用 Windows GPU，绕开 WSLg；
- 与 Java core 通过 HTTP/WebSocket 解耦，跨平台部署简单；
- 只读 viewer 的复杂度可控。

**缺点**：

- WebGL2 的 API 老、扩展多，复杂 GPU 功能不如 WebGPU；
- JS/TS 需要维护 Worker、二进制协议、fallback；
- 浏览器标签页受内存/驱动/上下文丢失影响；
- 需要处理本地端口转发/防火墙（WSL2 的 localhost 通常可用，但需实测）。

**适用**：首版只读查看器、跨平台优先、人类主要在 Windows 浏览器看。

### 2.2 WebGPU（可选的现代后端）

**结构**：

- JS/TS + WGSL shader；
- `GPUBuffer` storage/vertex/index；
- compute 可选（几何简化/筛选/实例化）；
- WebGL2 fallback。

**优点**：

- 更低的 CPU 提交开销，适合大量实例/顶点；
- storage buffer、compute、multi-draw 等能力更强；
- 与 native `wgpu` 概念相近，可共享渲染思路/数据布局。

**缺点**：

- 需要较新的浏览器/驱动；即使 2026 已广泛可用，也不能保证每个用户环境（尤其旧 Windows/远程桌面/虚拟机）都有合适 adapter；
- WebGPU 的 device lost、adapter/limits、shader 调试比 WebGL2 更复杂；
- WSL 的 GPU 情况不直接相关（浏览器在 Windows 侧），但远程桌面/虚拟机可能没有硬件加速；
- 必须实现 WebGL2 fallback，否则“跨平台通用性”不达标。

**建议**：不要首版直接 WebGPU-only。先定一个抽象 `RendererBackend`，同一切片/二进制协议，WebGL2 先落地，WebGPU 后接。

### 2.3 JavaFX（Canvas/Prism）

**优点**：

- Java 团队上手成本低，和 core/AgentLib 同 JVM；
- 有 GPU 加速的 Prism 渲染管线；
- 不需要 JS/浏览器/端口转发；
- 适合桌面小工具/内部调试。

**缺点**：

- WSL 下要 WSLg/X11；Windows 用户不能像浏览器那样直接打开一个 localhost 页面；
- JavaFX 的 GPU 路径/驱动兼容性不如浏览器矩阵透明；
- 低层 GPU buffer/instancing 控制有限，10k+ polygon 的大地图需要自己做 culling/LOD，天花板低于 WebGL2/原生；
- 打包（jlink/jpackage）和多平台 native 依赖要额外维护。

**适用**：如果最终坚持“纯 JVM 桌面查看器”，且 WSLg 已验证可用。

### 2.4 LWJGL + Dear ImGui

**优点**：

- Java 侧直接使用 OpenGL/Vulkan + GLFW，控制力强；
- Dear ImGui 非常适合调试面板、树形信息、时间线控制、Agent 开发工具；
- 原生桌面性能好，跨 Windows/Linux/macOS；
- 可以把地图渲染和工具 UI 放同一窗口。

**缺点**：

- WSL 下仍要 WSLg/X11，且要处理 OpenGL/Vulkan 驱动；
- Dear ImGui 是即时模式 UI，适合工具，不是“精致只读 Viewer”的默认选择；
- 地图 polygon 渲染仍需自己写 shader/batching/LOD/picking；
- Java native 库分发、GLFW 窗口、字体/DPI/输入法要自己处理；
- 跨平台“通用性”是 native 二进制级，不是“浏览器打开即用”。

**适用**：未来的内部地图编辑器、大规模地图调试工具；不建议作为首版人类查看器。

### 2.5 Rust/wgpu + egui（或其他 native UI）

**优点**：

- wgpu 统一 Vulkan/Metal/DX12/WebGPU，性能高；
- egui 适合工具 UI，Rust 生态对二进制/几何/并行处理友好；
- 同一套 wgpu 渲染思路可延伸出 native 与 wasm/web 版本；
- 可以编译 Windows 原生 viewer，直接跑在宿主 Windows 上，不必 WSLg。

**缺点**：

- 多语言/多进程：Java core ↔ Rust viewer 需要 IPC/MCP/HTTP；
- 首版开发成本高，调试链更长；
- native 分发/签名/崩溃收集要自己管；
- WSL 内运行 Rust GUI 仍要走 WSLg；要避开 WSLg 就需要 Windows 构建/发布链。

**适用**：未来高负载地图查看器/编辑器；不是当前核心存储 POC 的前置条件。

### 2.6 JavaFX/LWJGL 之外的 Skia

- Skia 的 2D 质量高，适合 polygon 填充/描边/文本；
- GPU 后端和 Java 绑定（如 Skija）可用性/维护性需单独核；
- 对只读 viewer 而言，WebGL2 或 JavaFX 的更简单路径通常更划算；
- 除非明确需要“高质量矢量印刷级地图”，不纳入首版。

### 2.7 服务端预渲染 tile / 纯图片

- 服务端把当前节点渲染成 PNG/WebP/SVG tile；浏览器只显示图片 + 点击映射；
- 客户端极其简单，适合“查看”而非编辑；
- 交互（缩放/高亮/单位切换）需要重新请求 tile，延迟高；
- 适合 MCP artifact、报告、缩略图；不适合实时地图交互。

**可作为 fallback**：只读查看器首版甚至可以先做“缩略图 + 实体列表”，后接 WebGL2。

---

## 3. WSL 的关键现实

### 3.1 推荐路径：WSL 服务 + Windows 浏览器

```text
WSL2 Ubuntu
  Java core / HTTP server 127.0.0.1:xxxxx
          ▲
          │ localhost port forwarding
          ▼
Windows Chrome/Edge/Firefox
  WebGL2/WebGPU viewer
```

- Windows 浏览器使用 Windows GPU，不需要 WSLg/X11；
- WSL2 的 localhost 转发通常开箱可用；若不行，用 `netsh interface portproxy` 或显式绑定 `0.0.0.0`（注意安全/防火墙）；
- 只读 viewer 不需要复杂 Web 编辑器，前端可以很小；
- 后续如果 HTTP 服务不变，viewer 可从浏览器换到 native viewer。

### 3.2 WSLg/原生 GUI

- WSLg 支持 Linux GUI，但需要 WSLg、Wayland/X、Mesa 驱动栈；
- GPU 加速可用不等于所有 OpenGL/Vulkan 扩展/驱动行为一致；
- 对本项目“人类主要在 Windows 看”不是首选；
- 若要原生 GUI，建议直接在 Windows 侧构建/运行 native viewer，而不是在 WSL 里跑 Linux GUI。

### 3.3 跨平台建议

优先级：

1. **浏览器 WebGL2 viewer**（跨 OS/浏览器最稳）；
2. **WebGPU 可选后端**（有 fallback）；
3. **Rust/wgpu native viewer**（以后需要高性能/离线时）；
4. JavaFX/LWJGL 作为 JVM 内部工具/编辑器，不作为首版通用查看器。

---

## 4. 建议的查看器数据协议

无论选 WebGL2/WebGPU/native，都建议同一套逻辑：

```text
ViewerTile
  nodeRef          tree1-360
  tileId
  bounds
  layers[]
  vertices         Float32 binary
  indices          Uint32 binary
  entityIds        Uint32 binary
  entityMeta       JSON/Arrow（名称、类型、displayPoint、样式）
  unitOutlines     可选二进制
  checksum
```

规则：

- 服务端做 polygon 简化、视口裁剪、LOD；
- 二进制 payload 用 `ArrayBuffer`/压缩（gzip/zstd）传输；
- 客户端只渲染 `displayPoint`/单位标记/选中轮廓，除非用户主动打开 polygon 叠加；
- 实体 ID 用稳定 ID，不用数组下标做身份；
- 所有 tile 带 `nodeRef` + revision/hash，避免跨时间线节点误用；
- 大图/完整导出走 artifact，不塞入 MCP 文本结果。

---

## 5. 推荐决策

**首版：不做任何 Viewer UI。**

先完成：

- 严格 `NodeAddress`；
- 原生 polygon 静态状态；
- SQLite 时间线 + checkpoint + delta；
- working copy / save / discard / fork；
- CLI/JSON 查询与回放。

**第二版：只读 WebGL2 Viewer。**

- 从 WSL 提供 HTTP；Windows 浏览器打开；
- 先只显示省/州/单位 `displayPoint`、选中轮廓、时间线节点切换、ORBAT/INFO 只读详情；
- 不实现复杂地图编辑；
- 预留 WebGPU backend。

**第三版：按瓶颈升级。**

- 如果 200–500 省 viewer 很轻松：保持 WebGL2；
- 如果 10k+ polygon/大量单位出现瓶颈：先做 LOD/裁剪/二进制 tile，再考虑 WebGPU；
- 如果仍需离线/原生高性能：做 Rust/wgpu native viewer，复用同一套二进制协议，不重写 core。

> 结论：**在 WSL + 人类只读查看 + 跨平台优先的组合下，WebGL2 + Worker + 二进制 buffer 的浏览器 Viewer 是最平衡的方案；WebGPU 是增强，不是首版前提；LWJGL/Dear ImGui 更适合未来内部编辑器，不是当前查看器。**

---

## 6. 参考

- 用户原话与设计：[`../specs/2026-11-17-worldsim-architecture-design-source.md`](../specs/2026-11-17-worldsim-architecture-design-source.md) §7
- 专题设计：[`../specs/2026-11-17-map-orbat-info-timeline-design.md`](../specs/2026-11-17-map-orbat-info-timeline-design.md)
- WebGPU 浏览器支持（2026 社区汇总）：<https://github.com/localmode-ai/localmode/blob/be2cc09d8e265cc550966999d5cdd179c7ae3445/apps/docs/content/blog/compatibility/webgpu-support.mdx>
- Khronos WebGL/WebGPU 资料：<https://www.khronos.org/assets/uploads/developers/presentations/WebGL%2BWebGPU_-_SIGGRAPH_Jul26.pdf>
- WSL GUI 运行参考：<https://global.php.cn/faq/1797011268.html>
- ImGui Java/LWJGL 参考：<https://libgdx.com/wiki/graphics/2d/imgui>、<https://aicoding.csdn.net/6a5e24e9662f9a54cb91c7e4.html>
