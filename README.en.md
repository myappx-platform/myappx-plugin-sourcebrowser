# MyAppx Plugin : Source Browser

**Language:** [中文](readme.md) | English

[![License](https://img.shields.io/badge/license-GPL--2.0-brightgreen.svg)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)

Read-only browser for an **assembled** OSGi source pack (tree + search + Monaco). **Not** a git working tree. No decompiler. Independent plugin: only honors `MYAPPX_SOURCE_*`. On start, `ODT2PackActivator` installs the dictionary (Form / menu / SysConfig) by version.

| Item | Value |
|------|--------|
| Bundle | `org.idempiere.myappx.plugin.sourcebrowser` (`singleton:=true`) |
| Version | `14.0.0.qualifier` |
| Java / iDempiere | 17+ / 14 |
| Requires | `org.idempiere.myappx.plugin.odt` |
| Entity Type | `MYAPPX.SRB` |
| Menu | **Source Browser** |
| Web | `/sourcebrowser` (`Eclipse-BundleShape: dir`) |
| License | [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) |
| Vendor | Ken Longnan \<ken.longnan@gmail.com\> |

---

## 1. Features

- Expand OSGi bundles in the assembled pack; open a file in **read-only** Monaco on the right.
- Search by file name / content; reveal a hit in the tree.
- Other plugins may open this form via OSGi `ISourceBrowser.open(path, line[, symbol])` (this plugin does not depend on them).

---

## 2. Enable

1. Deploy **MyAppx Plugin ODT** first, then start this bundle (OSGi **Resolved / Active**).
2. On activation it Packs In `META-INF/ODTPackage_MYAPPX.SRB.xml`.
3. Assemble the source pack, set the enable flag, Reset Cache, and grant the Form (Role Access Update if needed).

Leave `MYAPPX_SOURCE_ROOTS` **empty**. Env wins over SysConfig. Portable `bin\assemble\assemble-sources.bat` writes `{portable}/workspace/sources` (same pack as Copilot).

| Role | Empty SysConfig (product zip / Docker mount) | Portable env (`bin\env.bat`) |
| :--- | :--- | :--- |
| Source pack | `{IDEMPIERE_HOME}/data/workspace/sources` | `IDEMPIERE_MYAPPX_SOURCE_ROOTS` → `{portable}/workspace/sources` |

Portable `workspace/` (this plugin only reads `sources/`):

```text
{portable}/workspace/
├── sources/     ← read-only assembled pack (index.json + OSGi modules)
├── kb/          ← Copilot KB (unused here)
└── scratch/     ← Copilot session scratch (unused here)
```

Do **not** use the old `{portable}/ai/…` tree. If Docker mounts the pack at `{IDEMPIERE_HOME}/data/workspace/sources`, leave `IDEMPIERE_MYAPPX_SOURCE_ROOTS` unset. Do not point at git checkouts.

| | Standard | Portable |
|--|----------|----------|
| `MYAPPX_SOURCE_ROOTS` | Empty → default above | **Leave empty** (`bin\env.bat` already sets env) |
| `MYAPPX_SOURCE_ENABLED` | `Y` | `Y` |

Env `IDEMPIERE_MYAPPX_SOURCE_ENABLED` / `IDEMPIERE_MYAPPX_SOURCE_ROOTS` override SysConfig. No SQL migration.

---

## 3. Build

Tycho must resolve ODT (same reactor or already in the target platform). Pass `-Drevision=14.0.0-SNAPSHOT` and set `IDEMPIERE_CORE_REPOSITORY_URL` to the iDempiere P2.

```bat
cd myappx-plugins

REM ODT not yet in the target platform: same reactor
mvn "-Drevision=14.0.0-SNAPSHOT" clean verify ^
  -pl myappx-plugin-odt/org.idempiere.myappx.plugin.odt,myappx-plugin-sourcebrowser

REM Target platform already contains ODT
mvn -f myappx-plugin-sourcebrowser/pom.xml "-Drevision=14.0.0-SNAPSHOT" clean verify
```

Artifact: `target/org.idempiere.myappx.plugin.sourcebrowser-*.jar` (drop into iDempiere `plugins/` or Extension Management).

Maven unpacks Monaco `min/vs` into `viewer/vs/` (gitignored). **Use this Tycho JAR**: Eclipse without an iDempiere target emits stub classes, and Jetty then reports `is not a javax.servlet.Servlet`.

---

## 4. Layout

```text
myappx-plugin-sourcebrowser/
├── META-INF/ODTPackage_MYAPPX.SRB.xml
├── OSGI-INF/                          # Form scan + ISourceBrowser
├── WEB-INF/web.xml                    # /api/* + viewer
├── viewer/                            # index.html, viewer.js; vs/ unpacked by Maven
└── src/.../sourcebrowser/
    ├── Activator.java                 # extends ODT2PackActivator
    ├── api/ISourceBrowser.java
    ├── form/                          # ZK tree + search + Monaco iframe
    ├── pack/                          # pack roots, sandbox, search
    └── web/                           # ticket + servlet
```

`SourceBrowserForm` (west tree) calls `/sourcebrowser/api/*` with a ticket (~8 hours; header `X-Source-Ticket` or `?ticket=`). The center iframe loads `viewer/`. `SourcePack` is read-only: skips `.git` / `target` / `node_modules`, rejects typical secrets; human cap about **1MB / 8000** lines; index cached ~60 seconds.

ODT: `MYAPPX.SRB`, `MYAPPX_SOURCE_ENABLED` (default `N`), `MYAPPX_SOURCE_ROOTS` (default empty), Form/menu (plus zh_CN), main menu tree node.

| GET | Purpose |
|-----|---------|
| `/api/status` | Ready flag, roots, bundle count |
| `/api/tree` | Bundle list or children |
| `/api/file?path=` | File content |
| `/api/search?query=` | Hits (default 50, cap 100) |

This API is for the Form iframe only.

---

## 5. License

Same as [iDempiere](https://www.idempiere.org/): [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html). Copyright headers in source files take precedence.

Contributor: ken.longnan@gmail.com
