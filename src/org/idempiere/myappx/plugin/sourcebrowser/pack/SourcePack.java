package org.idempiere.myappx.plugin.sourcebrowser.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.compiere.Adempiere;
import org.compiere.util.CLogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Sandboxed assembled source pack.
 * Empty {@code MYAPPX_SOURCE_ROOTS} → {@code {IDEMPIERE_HOME}/data/workspace/sources};
 * portable env {@code IDEMPIERE_MYAPPX_SOURCE_ROOTS} → {@code {portable}/workspace/sources}.
 * Never a live git checkout.
 */
public final class SourcePack {

	private static final CLogger log = CLogger.getCLogger(SourcePack.class);

	static final Set<String> SKIP_DIRS = Set.of(
			".git", "target", "bin", "node_modules", ".settings", ".idea", ".metadata",
			"classes", "work", "cache", "pgdata");
	private static final Set<String> TEXT_EXT = Set.of(
			"java", "xml", "properties", "md", "sql", "txt", "json", "yml", "yaml", "html",
			"js", "ts", "css", "gradle", "mf", "bnd", "product", "inf", "csv", "sh", "bat",
			"cmd", "py", "rb", "go", "c", "h", "cpp", "hpp");
	private static final Set<String> SECRET_NAMES = Set.of(
			".env", "idempiereenv.properties", "credentials.json", "secrets.json",
			"id_rsa", "id_ed25519");
	private static final Set<String> SECRET_EXT = Set.of(
			"jks", "p12", "pfx", "keystore", "pem", "key", "crt");

	public static final int MAX_HUMAN_LINES = 8000;
	public static final long MAX_HUMAN_BYTES = 1_000_000L;
	private static final long INDEX_TTL_MS = 60_000L;

	private static volatile long indexLoadedAt;
	private static volatile Map<String, BundleSource> cachedIndex = Map.of();
	private static volatile long filesLoadedAt;
	private static volatile List<Path> cachedTextFiles = List.of();

	private SourcePack() {}

	public static String notReadyReason() {
		if (!SourceConfig.isEnabled()) {
			return "Source browser is disabled. Set MYAPPX_SOURCE_ENABLED=Y "
					+ "(or env IDEMPIERE_MYAPPX_SOURCE_ENABLED).";
		}
		if (listRoots().isEmpty()) {
			return "No readable source pack. Assemble sources into {IDEMPIERE_HOME}/data/workspace/sources "
					+ "(portable: workspace/sources via IDEMPIERE_MYAPPX_SOURCE_ROOTS).";
		}
		return null;
	}

	public static List<Path> listRoots() {
		String csv = SourceConfig.getRootsConfigured();
		List<Path> roots = new ArrayList<>();
		if (csv == null || csv.isBlank()) {
			Path pack = defaultPackRoot();
			if (pack != null) {
				roots.add(pack);
			}
			return roots;
		}
		for (String part : csv.split("[;]")) {
			String p = part.trim();
			if (p.isEmpty()) {
				continue;
			}
			try {
				Path path = Paths.get(p).toAbsolutePath().normalize();
				if (Files.isDirectory(path)) {
					roots.add(real(path));
				}
			} catch (Exception e) {
				log.fine("Skip source root " + p + ": " + e.getMessage());
			}
		}
		return roots;
	}

	/** {@code {IDEMPIERE_HOME}/data/workspace/sources} when that directory exists (product default). */
	static Path defaultPackRoot() {
		String home = Adempiere.getAdempiereHome();
		if (home == null || home.isBlank()) {
			home = System.getProperty("user.dir", ".");
		}
		try {
			Path path = Paths.get(home, "data", "workspace", "sources").toAbsolutePath().normalize();
			if (Files.isDirectory(path)) {
				return real(path);
			}
		} catch (Exception e) {
			log.fine("Default sources pack skipped: " + e.getMessage());
		}
		return null;
	}

	public static Path resolveExisting(String rawPath) throws IOException {
		if (rawPath == null || rawPath.isBlank()) {
			throw new IOException("Missing path.");
		}
		String rel = rawPath.trim().replace('\\', '/');
		if (rel.contains("\0")) {
			throw new IOException("Invalid path.");
		}
		List<Path> roots = listRoots();
		if (roots.isEmpty()) {
			throw new IOException("No readable source pack ({IDEMPIERE_HOME}/data/workspace/sources or IDEMPIERE_MYAPPX_SOURCE_ROOTS).");
		}
		Path asPath = Paths.get(rel);
		List<Path> candidates = new ArrayList<>();
		if (asPath.isAbsolute()) {
			candidates.add(asPath);
		} else {
			for (Path root : roots) {
				candidates.add(root.resolve(rel));
				if (rel.startsWith("data/workspace/sources/")) {
					candidates.add(root.resolve(rel.substring("data/workspace/sources/".length())));
				} else if (rel.startsWith("data/ai/sources/")) {
					candidates.add(root.resolve(rel.substring("data/ai/sources/".length())));
				} else if (rel.startsWith("data/sources/")) {
					candidates.add(root.resolve(rel.substring("data/sources/".length())));
				} else if (rel.startsWith("workspace/sources/")) {
					candidates.add(root.resolve(rel.substring("workspace/sources/".length())));
				} else if (rel.startsWith("ai/sources/")) {
					candidates.add(root.resolve(rel.substring("ai/sources/".length())));
				}
				if (rel.startsWith(root.getFileName().toString() + "/")) {
					candidates.add(root.resolve(rel.substring(root.getFileName().toString().length() + 1)));
				}
			}
		}
		for (Path candidate : candidates) {
			Path real = tryReal(candidate);
			if (real != null && Files.exists(real) && isUnderRoots(real, roots)) {
				return real;
			}
		}
		throw new IOException("Path not found under source roots: " + rawPath);
	}

	/**
	 * Resolve a pack-relative path: full path, or a short
	 * {@code MSysConfig.java} / {@code MSysConfig} file name.
	 *
	 * @return preferred hit, or {@code null} if none
	 */
	public static OpenTarget resolveForOpen(String rawPath) {
		if (rawPath == null || rawPath.isBlank()) {
			return null;
		}
		String rel = rawPath.trim().replace('\\', '/');
		if (rel.contains("/") || rel.contains("..")) {
			try {
				Path existing = resolveExisting(rel);
				return new OpenTarget(relativeToRoots(existing), 0);
			} catch (IOException e) {
				int slash = rel.lastIndexOf('/');
				String fn = slash >= 0 ? rel.substring(slash + 1) : rel;
				List<String> hits = findByFileName(fn);
				return openTarget(hits);
			}
		}
		return openTarget(findByFileName(rel));
	}

	/** Case-insensitive file-name search over the assembled pack (not a live git tree). */
	public static List<String> findByFileName(String name) {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		String want = name.trim().replace('\\', '/');
		int slash = want.lastIndexOf('/');
		if (slash >= 0) {
			want = want.substring(slash + 1);
		}
		if (!want.contains(".")) {
			want = want + ".java";
		}
		String needle = want.toLowerCase(Locale.ROOT);
		List<String> hits = new ArrayList<>();
		for (Path file : listIndexedFiles()) {
			Path fn = file.getFileName();
			if (fn != null && needle.equals(fn.toString().toLowerCase(Locale.ROOT))) {
				hits.add(relativeToRoots(file));
			}
		}
		hits.sort(SourcePack::compareOpenHits);
		return hits;
	}

	/**
	 * 1-based first line containing {@code symbol}, or {@code 0} if none.
	 * Scans at most {@link #MAX_HUMAN_LINES} of the assembled pack file.
	 */
	public static int findLineContaining(String rawPath, String symbol) {
		if (rawPath == null || rawPath.isBlank() || symbol == null || symbol.isBlank()) {
			return 0;
		}
		String needle = symbol.trim();
		if (needle.length() < 2 || needle.length() > 120) {
			return 0;
		}
		try {
			Path file = resolveExisting(rawPath);
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int end = Math.min(lines.size(), MAX_HUMAN_LINES);
			int quoted = -1;
			int any = -1;
			for (int i = 0; i < end; i++) {
				String line = lines.get(i);
				if (line == null || !line.contains(needle)) {
					continue;
				}
				if (any < 0) {
					any = i + 1;
				}
				if (line.contains("\"" + needle + "\"") || line.contains("'" + needle + "'")
						|| line.contains(needle + " =") || line.contains(needle + "=")) {
					quoted = i + 1;
					break;
				}
			}
			return quoted > 0 ? quoted : (any > 0 ? any : 0);
		} catch (IOException e) {
			return 0;
		}
	}

	private static OpenTarget openTarget(List<String> hits) {
		if (hits == null || hits.isEmpty()) {
			return null;
		}
		return new OpenTarget(hits.get(0), hits.size() - 1);
	}

	/** Prefer {@code org.adempiere.base}, then ui.zk, then shorter path. */
	static int compareOpenHits(String a, String b) {
		int ra = openRank(a);
		int rb = openRank(b);
		if (ra != rb) {
			return Integer.compare(ra, rb);
		}
		int len = Integer.compare(a.length(), b.length());
		return len != 0 ? len : a.compareToIgnoreCase(b);
	}

	private static int openRank(String rel) {
		String n = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
		if (n.contains("/org.adempiere.base/") || n.contains("org.adempiere.base/")) {
			return 0;
		}
		if (n.contains("/org.adempiere.ui.zk/") || n.contains("org.adempiere.ui.zk/")) {
			return 1;
		}
		return 2;
	}

	public static String relativeToRoots(Path file) {
		Path real = tryReal(file);
		if (real == null) {
			return file.toString().replace('\\', '/');
		}
		for (Path root : listRoots()) {
			if (real.startsWith(root)) {
				String rel = root.relativize(real).toString().replace('\\', '/');
				return root.getFileName() + "/" + rel;
			}
		}
		return real.toString().replace('\\', '/');
	}

	public static boolean skipDirectory(Path dir) {
		String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
		return SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT));
	}

	public static boolean isSecret(Path file) {
		String name = file.getFileName() != null ? file.getFileName().toString() : "";
		String lower = name.toLowerCase(Locale.ROOT);
		if (SECRET_NAMES.contains(lower)) {
			return true;
		}
		int dot = lower.lastIndexOf('.');
		if (dot >= 0 && SECRET_EXT.contains(lower.substring(dot + 1))) {
			return true;
		}
		return lower.endsWith(".env") || (lower.contains("password") && lower.endsWith(".properties"));
	}

	public static boolean isTextFile(Path file) {
		String name = file.getFileName() != null ? file.getFileName().toString() : "";
		String lower = name.toLowerCase(Locale.ROOT);
		if ("makefile".equals(lower) || "dockerfile".equals(lower) || lower.startsWith("readme")) {
			return true;
		}
		int dot = lower.lastIndexOf('.');
		if (dot < 0) {
			return "manifest.mf".equals(lower);
		}
		return TEXT_EXT.contains(lower.substring(dot + 1));
	}

	public static boolean looksBinary(Path file) throws IOException {
		long size = Files.size(file);
		if (size > MAX_HUMAN_BYTES) {
			return true;
		}
		int n = (int) Math.min(size, 800L);
		if (n <= 0) {
			return false;
		}
		byte[] probe = new byte[n];
		try (java.io.InputStream in = Files.newInputStream(file)) {
			int read = in.read(probe);
			if (read <= 0) {
				return false;
			}
			for (int i = 0; i < read; i++) {
				if (probe[i] == 0) {
					return true;
				}
			}
		}
		return false;
	}

	public static List<DirEntry> listChildren(String rawPath) throws IOException {
		Path resolved = (rawPath == null || rawPath.isBlank())
				? firstRoot()
				: resolveExisting(rawPath);
		if (!Files.isDirectory(resolved)) {
			throw new IOException("Not a directory: " + rawPath);
		}
		List<DirEntry> out = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(resolved)) {
			for (Path child : stream) {
				String name = child.getFileName().toString();
				if (Files.isDirectory(child)) {
					if (skipDirectory(child)) {
						continue;
					}
					out.add(new DirEntry(name, relativeToRoots(child), true));
				} else if (!isSecret(child)) {
					out.add(new DirEntry(name, relativeToRoots(child), false));
				}
			}
		}
		out.sort((a, b) -> {
			if (a.directory != b.directory) {
				return a.directory ? -1 : 1;
			}
			return a.name.compareToIgnoreCase(b.name);
		});
		return out;
	}

	private static Path firstRoot() throws IOException {
		List<Path> roots = listRoots();
		if (roots.isEmpty()) {
			throw new IOException("No readable source pack.");
		}
		return roots.get(0);
	}

	public static HumanFile readForHuman(String rawPath) throws IOException {
		Path resolved = resolveExisting(rawPath);
		if (Files.isDirectory(resolved)) {
			throw new IOException("Path is a directory.");
		}
		if (isSecret(resolved)) {
			throw new IOException("That file is blocked (secret / credential).");
		}
		if (!isTextFile(resolved) || looksBinary(resolved)) {
			throw new IOException("Not a readable text source file: " + rawPath);
		}
		long size = Files.size(resolved);
		List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
		int total = lines.size();
		int end = Math.min(total, MAX_HUMAN_LINES);
		boolean truncated = end < total || size > MAX_HUMAN_BYTES;
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < end; i++) {
			if (i > 0) {
				body.append('\n');
			}
			body.append(lines.get(i));
		}
		String rel = relativeToRoots(resolved);
		return new HumanFile(rel, body.toString(), total, truncated, languageFor(rel));
	}

	public static String languageFor(String path) {
		if (path == null) {
			return "plaintext";
		}
		String lower = path.toLowerCase(Locale.ROOT);
		int dot = lower.lastIndexOf('.');
		String ext = dot >= 0 ? lower.substring(dot + 1) : "";
		return switch (ext) {
			case "java" -> "java";
			case "xml", "html" -> "xml";
			case "js" -> "javascript";
			case "ts" -> "typescript";
			case "css" -> "css";
			case "json" -> "json";
			case "md" -> "markdown";
			case "sql" -> "sql";
			case "yml", "yaml" -> "yaml";
			case "properties", "mf" -> "ini";
			case "py" -> "python";
			case "sh" -> "shell";
			case "bat", "cmd" -> "bat";
			default -> "plaintext";
		};
	}

	public static List<Path> listIndexedFiles() {
		long now = System.currentTimeMillis();
		if (now - filesLoadedAt < INDEX_TTL_MS && !cachedTextFiles.isEmpty()) {
			return cachedTextFiles;
		}
		List<Path> files = new ArrayList<>();
		for (Path root : listRoots()) {
			try {
				final Path searchRoot = root;
				Files.walkFileTree(searchRoot, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
						if (dir.equals(searchRoot)) {
							return FileVisitResult.CONTINUE;
						}
						return skipDirectory(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						if (attrs.isRegularFile() && !isSecret(file) && isTextFile(file)) {
							files.add(file);
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) {
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (Exception e) {
				log.fine("index files skip " + root + ": " + e.getMessage());
			}
		}
		cachedTextFiles = List.copyOf(files);
		filesLoadedAt = now;
		return cachedTextFiles;
	}

	public static Map<String, BundleSource> bundleIndex() {
		long now = System.currentTimeMillis();
		if (now - indexLoadedAt < INDEX_TTL_MS && !cachedIndex.isEmpty()) {
			return cachedIndex;
		}
		Map<String, BundleSource> map = new LinkedHashMap<>();
		for (Path root : listRoots()) {
			Path indexFile = root.resolve("index.json");
			if (Files.isRegularFile(indexFile)) {
				loadIndexJson(indexFile, root, map);
			}
			scanManifests(root, root, map, 0);
		}
		cachedIndex = map;
		indexLoadedAt = now;
		return map;
	}

	private static void loadIndexJson(Path file, Path root, Map<String, BundleSource> map) {
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			JsonObject bundles = obj.has("bundles") && obj.get("bundles").isJsonObject()
					? obj.getAsJsonObject("bundles")
					: obj;
			for (Map.Entry<String, JsonElement> e : bundles.entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				JsonObject b = e.getValue().getAsJsonObject();
				String path = b.has("path") ? b.get("path").getAsString() : "";
				String version = b.has("version") ? b.get("version").getAsString() : "";
				int javaFiles = b.has("javaFiles") && b.get("javaFiles").isJsonPrimitive()
						? b.get("javaFiles").getAsInt() : 0;
				if (path == null || path.isBlank()) {
					continue;
				}
				Path abs = root.resolve(path).normalize();
				map.put(e.getKey(), new BundleSource(e.getKey(), version, abs, path, javaFiles));
			}
		} catch (Exception e) {
			log.fine("Could not read " + file + ": " + e.getMessage());
		}
	}

	private static void scanManifests(Path root, Path dir, Map<String, BundleSource> map, int depth) {
		if (depth > 8 || map.size() > 400) {
			return;
		}
		Path mf = dir.resolve("META-INF").resolve("MANIFEST.MF");
		if (Files.isRegularFile(mf)) {
			try {
				BundleSource src = parseManifest(root, dir, mf);
				if (src != null && !map.containsKey(src.symbolicName)) {
					map.put(src.symbolicName, src);
				}
			} catch (Exception e) {
				log.fine("MANIFEST skip " + mf + ": " + e.getMessage());
			}
		}
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path child : stream) {
				if (!Files.isDirectory(child) || skipDirectory(child)) {
					continue;
				}
				scanManifests(root, child, map, depth + 1);
			}
		} catch (IOException e) {
			log.fine("scan " + dir + ": " + e.getMessage());
		}
	}

	private static BundleSource parseManifest(Path root, Path bundleDir, Path mf) throws IOException {
		String symbolic = null;
		String version = "";
		for (String line : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
			if (line.startsWith("Bundle-SymbolicName:")) {
				symbolic = line.substring("Bundle-SymbolicName:".length()).trim();
				int sc = symbolic.indexOf(';');
				if (sc > 0) {
					symbolic = symbolic.substring(0, sc).trim();
				}
			} else if (line.startsWith("Bundle-Version:")) {
				version = line.substring("Bundle-Version:".length()).trim();
			}
		}
		if (symbolic == null || symbolic.isBlank()) {
			return null;
		}
		String rel = root.relativize(bundleDir).toString().replace('\\', '/');
		return new BundleSource(symbolic, version, bundleDir, rel, 0);
	}

	private static boolean isUnderRoots(Path real, List<Path> roots) {
		for (Path root : roots) {
			if (real.startsWith(root)) {
				return true;
			}
		}
		return false;
	}

	private static Path real(Path path) throws IOException {
		return path.toRealPath();
	}

	private static Path tryReal(Path path) {
		try {
			if (Files.exists(path)) {
				return path.toRealPath();
			}
			return path.toAbsolutePath().normalize();
		} catch (IOException e) {
			return null;
		}
	}

	public static final class BundleSource {
		public final String symbolicName;
		public final String version;
		public final Path directory;
		public final String relativePath;
		public final int javaFiles;

		BundleSource(String symbolicName, String version, Path directory, String relativePath, int javaFiles) {
			this.symbolicName = symbolicName;
			this.version = version != null ? version : "";
			this.directory = directory;
			this.relativePath = relativePath != null ? relativePath : "";
			this.javaFiles = javaFiles;
		}
	}

	public static final class DirEntry {
		public final String name;
		public final String path;
		public final boolean directory;

		DirEntry(String name, String path, boolean directory) {
			this.name = name;
			this.path = path;
			this.directory = directory;
		}
	}

	public static final class HumanFile {
		public final String path;
		public final String content;
		public final int totalLines;
		public final boolean truncated;
		public final String language;

		HumanFile(String path, String content, int totalLines, boolean truncated, String language) {
			this.path = path;
			this.content = content;
			this.totalLines = totalLines;
			this.truncated = truncated;
			this.language = language;
		}
	}

	/** Result of resolving a cite (short name or pack path) for Source Browser. */
	public static final class OpenTarget {
		public final String path;
		/** Other same-name files not opened. */
		public final int alternatives;

		public OpenTarget(String path, int alternatives) {
			this.path = path;
			this.alternatives = alternatives;
		}
	}
}
