package org.idempiere.myappx.plugin.sourcebrowser.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.compiere.util.CLogger;
import org.idempiere.myappx.plugin.sourcebrowser.pack.SourcePack;
import org.idempiere.myappx.plugin.sourcebrowser.pack.SourceSearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Ticket-gated JSON API for the Monaco iframe in Source Browser Form.
 */
public class SourceBrowseServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(SourceBrowseServlet.class);
	public static final String TICKET_HEADER = "X-Source-Ticket";

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
		resp.setHeader("Cache-Control", "no-store");
		String pathInfo = req.getPathInfo() == null ? "" : req.getPathInfo();
		BrowseTicketStore.Ticket ticket = BrowseTicketStore.get(ticketId(req));
		if (ticket == null) {
			writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Missing or expired source ticket.");
			return;
		}
		String blocked = SourcePack.notReadyReason();
		if (blocked != null && !"/status".equals(pathInfo)) {
			writeError(resp, HttpServletResponse.SC_FORBIDDEN, blocked);
			return;
		}
		try {
			switch (pathInfo) {
			case "/status" -> writeJson(resp, status(ticket, blocked));
			case "/tree" -> writeJson(resp, tree(req.getParameter("path")));
			case "/file" -> writeJson(resp, file(req.getParameter("path")));
			case "/search" -> writeJson(resp, search(req));
			default -> writeError(resp, HttpServletResponse.SC_NOT_FOUND, "Not Found");
			}
		} catch (IOException e) {
			log.fine("source browse: " + e.getMessage());
			writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
					e.getMessage() != null ? e.getMessage() : "browse failed");
		} catch (Exception e) {
			log.warning("source browse failed: " + e.getMessage());
			writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					e.getMessage() != null ? e.getMessage() : "browse failed");
		}
	}

	private static JsonObject status(BrowseTicketStore.Ticket ticket, String blocked) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", blocked == null);
		if (blocked != null) {
			o.addProperty("error", blocked);
		}
		o.addProperty("roleId", ticket.roleId);
		JsonArray roots = new JsonArray();
		for (var root : SourcePack.listRoots()) {
			roots.add(root.toString());
		}
		o.add("roots", roots);
		o.addProperty("bundles", SourcePack.bundleIndex().size());
		return o;
	}

	private static JsonObject tree(String path) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		if (path == null || path.isBlank()) {
			JsonArray bundles = new JsonArray();
			for (Map.Entry<String, SourcePack.BundleSource> e : SourcePack.bundleIndex().entrySet()) {
				JsonObject b = new JsonObject();
				b.addProperty("symbolicName", e.getKey());
				b.addProperty("version", e.getValue().version);
				b.addProperty("path", e.getValue().relativePath);
				b.addProperty("javaFiles", e.getValue().javaFiles);
				b.addProperty("directory", true);
				bundles.add(b);
			}
			o.add("bundles", bundles);
			return o;
		}
		JsonArray entries = new JsonArray();
		for (SourcePack.DirEntry child : SourcePack.listChildren(path)) {
			JsonObject e = new JsonObject();
			e.addProperty("name", child.name);
			e.addProperty("path", child.path);
			e.addProperty("directory", child.directory);
			entries.add(e);
		}
		o.addProperty("path", path);
		o.add("entries", entries);
		return o;
	}

	private static JsonObject file(String path) throws IOException {
		SourcePack.HumanFile f = SourcePack.readForHuman(path);
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("path", f.path);
		o.addProperty("content", f.content);
		o.addProperty("totalLines", f.totalLines);
		o.addProperty("truncated", f.truncated);
		o.addProperty("language", f.language);
		return o;
	}

	private static JsonObject search(HttpServletRequest req) throws IOException {
		String query = req.getParameter("query");
		String glob = req.getParameter("glob");
		int maxHits = SourceSearch.DEFAULT_MAX_HITS;
		try {
			if (req.getParameter("maxHits") != null) {
				maxHits = Integer.parseInt(req.getParameter("maxHits"));
			}
		} catch (NumberFormatException ignore) {
			// default
		}
		List<SourceSearch.Hit> hits = SourceSearch.search(query, glob, maxHits);
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("query", query);
		o.addProperty("hitCount", hits.size());
		JsonArray arr = new JsonArray();
		for (SourceSearch.Hit hit : hits) {
			JsonObject h = new JsonObject();
			h.addProperty("path", hit.path);
			h.addProperty("line", hit.line);
			h.addProperty("text", hit.text);
			arr.add(h);
		}
		o.add("hits", arr);
		return o;
	}

	private static String ticketId(HttpServletRequest req) {
		String h = req.getHeader(TICKET_HEADER);
		if (h != null && !h.isBlank()) {
			return h.trim();
		}
		String q = req.getParameter("ticket");
		return q != null ? q.trim() : null;
	}

	private static void writeJson(HttpServletResponse resp, JsonObject obj) throws IOException {
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("application/json; charset=UTF-8");
		resp.getWriter().write(obj.toString());
	}

	private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
		resp.setStatus(status);
		resp.setContentType("application/json; charset=UTF-8");
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("error", message);
		resp.getWriter().write(o.toString());
	}
}
