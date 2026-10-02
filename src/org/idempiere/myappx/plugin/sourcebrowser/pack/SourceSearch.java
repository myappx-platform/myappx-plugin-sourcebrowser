package org.idempiere.myappx.plugin.sourcebrowser.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human grep over the assembled pack. Same sandbox as {@link SourcePack}.
 */
public final class SourceSearch {

	public static final int DEFAULT_MAX_HITS = 50;
	private static final int MAX_HITS_CAP = 100;
	private static final int MAX_HITS_PER_FILE = 8;
	private static final int MAX_FILES = 20_000;
	private static final long MAX_FILE_BYTES = 512 * 1024L;

	private SourceSearch() {}

	public static List<Hit> search(String query, String glob, int maxHits) throws IOException {
		if (query == null || query.isBlank()) {
			throw new IOException("Missing query.");
		}
		if (query.length() > 200) {
			query = query.substring(0, 200);
		}
		int cap = Math.max(1, Math.min(maxHits > 0 ? maxHits : DEFAULT_MAX_HITS, MAX_HITS_CAP));
		Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
		GlobFilter globFilter = GlobFilter.compile(glob);
		List<Hit> hits = new ArrayList<>();
		int filesScanned = 0;
		for (Path file : SourcePack.listIndexedFiles()) {
			if (hits.size() >= cap || filesScanned >= MAX_FILES) {
				break;
			}
			if (!globFilter.matches(file)) {
				continue;
			}
			try {
				if (Files.size(file) > MAX_FILE_BYTES) {
					continue;
				}
			} catch (IOException e) {
				continue;
			}
			filesScanned++;
			try {
				searchFile(file, pattern, cap, hits);
			} catch (Exception ignore) {
				// skip unreadable file
			}
		}
		return hits;
	}

	private static void searchFile(Path file, Pattern pattern, int maxHits, List<Hit> hits) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		String rel = SourcePack.relativeToRoots(file);
		int fileHits = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (hits.size() >= maxHits || fileHits >= MAX_HITS_PER_FILE) {
				return;
			}
			String line = lines.get(i);
			Matcher m = pattern.matcher(line);
			if (!m.find()) {
				continue;
			}
			fileHits++;
			String text = line.length() > 240 ? line.substring(0, 240) + "…" : line;
			hits.add(new Hit(rel, i + 1, text.trim()));
		}
	}

	public static final class Hit {
		public final String path;
		public final int line;
		public final String text;

		Hit(String path, int line, String text) {
			this.path = path;
			this.line = line;
			this.text = text;
		}
	}

	static final class GlobFilter {
		final Pattern pattern;

		private GlobFilter(Pattern pattern) {
			this.pattern = pattern;
		}

		static GlobFilter compile(String glob) {
			if (glob == null || glob.isBlank() || "**/*".equals(glob) || "*".equals(glob)) {
				return new GlobFilter(null);
			}
			String g = glob.trim().replace('\\', '/');
			StringBuilder re = new StringBuilder();
			re.append("(?i)");
			for (int i = 0; i < g.length(); i++) {
				char c = g.charAt(i);
				if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
					re.append(".*");
					i++;
					if (i + 1 < g.length() && g.charAt(i + 1) == '/') {
						i++;
						re.append("/?");
					}
				} else if (c == '*') {
					re.append("[^/]*");
				} else if (c == '?') {
					re.append("[^/]");
				} else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
					re.append('\\').append(c);
				} else {
					re.append(c);
				}
			}
			return new GlobFilter(Pattern.compile(re.toString()));
		}

		boolean matches(Path file) {
			if (pattern == null) {
				return true;
			}
			String name = file.getFileName() != null ? file.getFileName().toString() : "";
			String rel = SourcePack.relativeToRoots(file);
			return pattern.matcher(name).matches() || pattern.matcher(rel).matches()
					|| pattern.matcher(rel.toLowerCase(Locale.ROOT)).find();
		}
	}
}
