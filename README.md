# MyAppx Plugin : Source Browser

**语言 / Language:** 中文 | [English](README.en.md)

[![License](https://img.shields.io/badge/license-GPL--2.0-brightgreen.svg)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)

只读浏览**已装配**的 OSGi 源码包（目录树 + 搜索 + Monaco）。**不是** git 工作区，不反编译。独立插件，只认 `MYAPPX_SOURCE_*`。启动时由 `ODT2PackActivator` 按版本安装字典（Form / 菜单 / SysConfig）。

| 项 | 值 |
|----|----|
| Bundle | `org.idempiere.myappx.plugin.sourcebrowser`（`singleton:=true`） |
| Version | `14.0.0.qualifier` |
| Java / iDempiere | 17+ / 14 |
| 必选 | `org.idempiere.myappx.plugin.odt` |
| Entity Type | `MYAPPX.SRB` |
| 菜单 | **Source Browser** / **源码浏览** |
| Web | `/sourcebrowser`（`Eclipse-BundleShape: dir`） |
| License | [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) |
| Vendor | Ken Longnan \<ken.longnan@gmail.com\> |

---

## 1. 功能

- 按装配包中的 OSGi bundle 展开目录，点文件在右侧 Monaco **只读**打开。
- 按文件名 / 内容搜索；命中后可回到树中定位。
- 其它插件可通过 OSGi 服务 `ISourceBrowser.open(path, line[, symbol])` 打开本 Form（本插件不依赖对方）。

---

## 2. 启用

1. 先部署 **MyAppx Plugin ODT**，再启动本 Bundle（OSGi **Resolved / Active**）。
2. 激活后自动 PackIn `META-INF/ODTPackage_MYAPPX.SRB.xml`。
3. 装配源码包，打开开关，Reset Cache，给角色授予 Form（必要时 Role Access Update）。

`MYAPPX_SOURCE_ROOTS` **保持空**。环境变量优先于 SysConfig。便携版用 `bin\assemble\assemble-sources.bat` 写入 `{portable}/workspace/sources`（与 Copilot 同一份包）。

| 角色 | SysConfig 为空（产品 zip / Docker 挂载） | 便携版 env（`bin\env.bat`） |
| :--- | :--- | :--- |
| 源码包 | `{IDEMPIERE_HOME}/data/workspace/sources` | `IDEMPIERE_MYAPPX_SOURCE_ROOTS` → `{portable}/workspace/sources` |

便携版 `workspace/`（本插件只用 `sources/`）：

```text
{portable}/workspace/
├── sources/     ← 只读装配包（index.json + OSGi 模块）
├── kb/          ← Copilot KB（本插件不读）
└── scratch/     ← Copilot 会话草稿纸（本插件不读）
```

不要用旧路径 `{portable}/ai/…`。Docker 把装配包挂到 `{IDEMPIERE_HOME}/data/workspace/sources` 时不必设 `IDEMPIERE_MYAPPX_SOURCE_ROOTS`。不要指到 git 检出。

| | 标准安装 | 便携版 |
|--|----------|--------|
| `MYAPPX_SOURCE_ROOTS` | 留空即用上表默认 | **保持空**（`bin\env.bat` 已设环境变量） |
| `MYAPPX_SOURCE_ENABLED` | `Y` | `Y` |

环境变量 `IDEMPIERE_MYAPPX_SOURCE_ENABLED` / `IDEMPIERE_MYAPPX_SOURCE_ROOTS` 优先于 SysConfig。无手工 SQL migration。

---

## 3. 构建

Tycho 需能解析 ODT（同一 reactor 或已在目标平台）。传入 `-Drevision=14.0.0-SNAPSHOT`，`IDEMPIERE_CORE_REPOSITORY_URL` 指向 iDempiere P2。

```bat
cd myappx-plugins

REM ODT 尚未进入目标平台时，与 ODT 同 reactor
mvn "-Drevision=14.0.0-SNAPSHOT" clean verify ^
  -pl myappx-plugin-odt/org.idempiere.myappx.plugin.odt,myappx-plugin-sourcebrowser

REM 目标平台已含 ODT
mvn -f myappx-plugin-sourcebrowser/pom.xml "-Drevision=14.0.0-SNAPSHOT" clean verify
```

产物：`target/org.idempiere.myappx.plugin.sourcebrowser-*.jar`（放入 iDempiere `plugins/` 或 Extension Management）。

Maven 会把 Monaco `min/vs` 解到 `viewer/vs/`（gitignore）。**必须用这次 Tycho 产物**：Eclipse 在未解析 iDempiere 目标平台时会打出桩 class，Jetty 会报 `is not a javax.servlet.Servlet`。

---

## 4. 结构

```text
myappx-plugin-sourcebrowser/
├── META-INF/ODTPackage_MYAPPX.SRB.xml
├── OSGI-INF/                          # Form 扫描 + ISourceBrowser
├── WEB-INF/web.xml                    # /api/* + viewer
├── viewer/                            # index.html、viewer.js；vs/ 由 Maven 解压
└── src/.../sourcebrowser/
    ├── Activator.java                 # extends ODT2PackActivator
    ├── api/ISourceBrowser.java
    ├── form/                          # ZK 树 + 搜索 + Monaco iframe
    ├── pack/                          # 装配根、沙箱、搜索
    └── web/                           # ticket + servlet
```

`SourceBrowserForm`（西侧树）经 ticket（约 8 小时，头 `X-Source-Ticket` 或 `?ticket=`）调用 `/sourcebrowser/api/*`，中央 iframe 加载 `viewer/`。`SourcePack` 只读装配树：跳过 `.git` / `target` / `node_modules` 等，拒绝典型密钥文件；人机上限约 **1MB / 8000** 行；索引约 60 秒缓存。

ODT：`MYAPPX.SRB`、`MYAPPX_SOURCE_ENABLED`（默认 `N`）、`MYAPPX_SOURCE_ROOTS`（默认空）、Form/菜单（含 zh_CN）、主菜单树节点。

| GET | 作用 |
|-----|------|
| `/api/status` | 是否就绪、roots、bundle 数 |
| `/api/tree` | bundle 列表或子目录 |
| `/api/file?path=` | 文件内容 |
| `/api/search?query=` | 搜索（默认 50 条，上限 100） |

该 API 仅供 Form iframe 使用。

---

## 5. 许可证

与 [iDempiere](https://www.idempiere.org/) 相同，[GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)。源文件头版权声明以各文件为准。

贡献者：ken.longnan@gmail.com
