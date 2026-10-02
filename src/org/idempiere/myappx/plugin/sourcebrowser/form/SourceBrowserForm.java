package org.idempiere.myappx.plugin.sourcebrowser.form;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.adempiere.webui.component.Borderlayout;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.theme.ThemeManager;
import org.adempiere.webui.util.Icon;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.idempiere.myappx.plugin.sourcebrowser.pack.SourcePack;
import org.idempiere.myappx.plugin.sourcebrowser.pack.SourceSearch;
import org.idempiere.myappx.plugin.sourcebrowser.web.BrowseTicketStore;
import org.idempiere.ui.zk.annotation.Form;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.OpenEvent;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Button;
import org.zkoss.zul.Center;
import org.zkoss.zul.Div;
import org.zkoss.zul.Iframe;
import org.zkoss.zul.Label;
import org.zkoss.zul.Listbox;
import org.zkoss.zul.Listcell;
import org.zkoss.zul.Listitem;
import org.zkoss.zul.South;
import org.zkoss.zul.Style;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Tree;
import org.zkoss.zul.Treecell;
import org.zkoss.zul.Treechildren;
import org.zkoss.zul.Treeitem;
import org.zkoss.zul.Treerow;
import org.zkoss.zul.West;

/**
 * Read-only assembled source pack browser: bundle tree, search, Monaco iframe.
 */
@Form
public class SourceBrowserForm extends ADForm {

	private static final long serialVersionUID = 1L;
	private static final CLogger log = CLogger.getCLogger(SourceBrowserForm.class);
	private static final String TOOL_GAP = "4px";
	private static final String ICON_BTN_STYLE =
			"min-width:26px;width:26px;height:26px;padding:0;margin:0;line-height:26px;text-align:center;";
	private static final String TREE_SCLASS = "sb-src-tree";

	private String ticket;
	private Iframe iframe;
	private Label status;
	private Tree tree;
	private Textbox searchBox;
	private Button revealBtn;
	private Button clearBtn;
	private Button backToHits;
	private Button collapseBtn;
	private Button locateBtn;
	private Listbox hitList;
	private String lastSearchMeta;
	private String pendingPath;
	private int pendingLine = 1;
	private boolean iframeReady;
	private boolean syncingTree;

	@Override
	protected void initForm() {
		int roleId = Env.getAD_Role_ID(Env.getCtx());
		ticket = BrowseTicketStore.issue(Env.getAD_User_ID(Env.getCtx()), roleId,
				Env.getAD_Client_ID(Env.getCtx()));
		bindDesktop();

		Borderlayout layout = new Borderlayout();
		ZKUpdateUtil.setWidth(layout, "100%");
		ZKUpdateUtil.setHeight(layout, "100%");
		appendChild(layout);

		String blocked = SourcePack.notReadyReason();
		if (blocked != null) {
			Center c = new Center();
			Label msg = new Label(blocked);
			msg.setStyle("display:block;padding:1.5rem;font-size:14px;");
			c.appendChild(msg);
			layout.appendChild(c);
			return;
		}

		West west = new West();
		west.setTitle(zh() ? "源码" : "Sources");
		west.setWidth("340px");
		west.setSplittable(true);
		west.setCollapsible(true);
		layout.appendChild(west);

		org.zkoss.zul.Vlayout westBox = new org.zkoss.zul.Vlayout();
		westBox.setVflex("1");
		westBox.setHflex("1");
		westBox.setStyle("padding:" + TOOL_GAP + ";box-sizing:border-box;");
		west.appendChild(westBox);

		Div row = new Div();
		row.setStyle("display:flex;gap:" + TOOL_GAP + ";align-items:center;");
		searchBox = new Textbox();
		searchBox.setPlaceholder(zh() ? "过滤文件，或搜索代码" : "Filter files or search code");
		searchBox.setTooltiptext(zh()
				? "回车：文件名/路径优先，没有再搜内容。空格当作内容搜索。"
				: "Enter: file name/path first, then file contents. A space forces content search.");
		ZKUpdateUtil.setHflex(searchBox, "1");
		searchBox.setStyle("height:26px;");
		searchBox.addEventListener(Events.ON_OK, e -> runQuery(false));
		searchBox.addEventListener(Events.ON_CANCEL, e -> showBrowseView(true));
		Button grep = iconButton(Icon.FIND, zh() ? "内容" : "Text",
				zh() ? "在文件中搜索" : "Search in files");
		grep.addEventListener(Events.ON_CLICK, e -> runQuery(true));
		revealBtn = iconButton(Icon.FOLDER, zh() ? "树" : "Tree",
				zh() ? "在目录树中显示当前文件" : "Reveal the current file in the tree");
		revealBtn.setVisible(false);
		revealBtn.addEventListener(Events.ON_CLICK, e -> showInTree());
		clearBtn = iconButton(Icon.RESET, zh() ? "清除" : "Clear",
				zh() ? "清除搜索，回到目录树" : "Clear search and return to the tree");
		clearBtn.setVisible(false);
		clearBtn.addEventListener(Events.ON_CLICK, e -> showBrowseView(true));
		backToHits = iconButton(Icon.TH_LIST, zh() ? "结果" : "Hits",
				zh() ? "返回上一轮搜索结果" : "Return to the last search results");
		backToHits.setVisible(false);
		backToHits.addEventListener(Events.ON_CLICK, e -> showHitsAgain());
		collapseBtn = iconButton(Icon.COLLAPSING, zh() ? "全部折叠" : "Collapse all",
				zh() ? "收起所有已展开的目录" : "Collapse every expanded folder");
		collapseBtn.addEventListener(Events.ON_CLICK, e -> collapseAll());
		locateBtn = iconButton(Icon.ZOOM, zh() ? "定位" : "Locate",
				zh() ? "在树中定位当前文件" : "Reveal the current file in the tree");
		locateBtn.addEventListener(Events.ON_CLICK, e -> showBrowseView(false));
		row.appendChild(searchBox);
		row.appendChild(grep);
		row.appendChild(revealBtn);
		row.appendChild(clearBtn);
		row.appendChild(backToHits);
		row.appendChild(collapseBtn);
		row.appendChild(locateBtn);
		westBox.appendChild(row);

		hitList = new Listbox();
		ZKUpdateUtil.setVflex(hitList, "1");
		hitList.setHflex("1");
		hitList.setVisible(false);
		hitList.addEventListener(Events.ON_SELECT, e -> {
			openHit(hitList.getSelectedItem());
		});
		hitList.addEventListener(Events.ON_DOUBLE_CLICK, e -> revealSelectedHit());
		westBox.appendChild(hitList);

		tree = new Tree();
		tree.setSclass(TREE_SCLASS);
		ZKUpdateUtil.setVflex(tree, "1");
		ZKUpdateUtil.setHflex(tree, "1");
		tree.setSizedByContent(false);
		tree.setStyle("overflow-x:hidden;overflow-y:auto;");
		tree.setWidgetOverride("bind_",
				"function(){this.$supers('bind_',arguments);"
						+ "var n=this.$n();if(!n||n._sbNoSel)return;n._sbNoSel=true;"
						+ "n.addEventListener('mousedown',function(e){if(e.detail>1)e.preventDefault();});}");
		tree.addEventListener(Events.ON_SELECT, e -> {
			if (syncingTree) {
				return;
			}
			Treeitem it = tree.getSelectedItem();
			if (it == null || Boolean.TRUE.equals(it.getAttribute("dir"))) {
				return;
			}
			String p = packPath(it);
			if (!Util.isEmpty(p, true) && !samePackPath(p, pendingPath)) {
				openFile(p, 1);
			}
		});
		tree.addEventListener("onRevealInTree", e -> revealInTree(pendingPath));
		westBox.appendChild(tree);
		fillBundleTree();

		Center center = new Center();
		layout.appendChild(center);
		iframe = new Iframe();
		// Absolute URL: ZK would turn "/sourcebrowser/..." into "/webui/sourcebrowser/...".
		iframe.setSrc(viewerHrefWithTicket());
		iframe.setStyle("border:0;width:100%;height:100%;");
		ZKUpdateUtil.setVflex(iframe, "1");
		ZKUpdateUtil.setHflex(iframe, "1");
		iframe.addEventListener(Events.ON_CREATE, e -> {
			iframeReady = true;
			syncViewer(pendingPath, pendingLine);
		});
		center.appendChild(iframe);

		org.zkoss.zul.Timer ready = new org.zkoss.zul.Timer();
		ready.setDelay(600);
		ready.setRepeats(false);
		ready.addEventListener(Events.ON_TIMER, e -> {
			iframeReady = true;
			syncViewer(pendingPath, pendingLine);
			revealInTree(pendingPath);
			ready.detach();
		});
		appendChild(ready);
		appendChild(treeEllipsisStyle());

		South south = new South();
		south.setHeight("28px");
		status = new Label(zh() ? "只读装配包" : "Read-only assembled pack");
		status.setStyle("padding:4px 8px;font-size:12px;color:#555;");
		south.appendChild(status);
		layout.appendChild(south);
	}

	private static Style treeEllipsisStyle() {
		Style css = new Style();
		css.setContent("." + TREE_SCLASS + " .z-tree-body{overflow-x:hidden!important;}"
				+ "." + TREE_SCLASS + " .z-tree-body table{width:100%!important;table-layout:fixed!important;}"
				+ "." + TREE_SCLASS + " .z-treerow,"
				+ "." + TREE_SCLASS + " .z-treecell,"
				+ "." + TREE_SCLASS + " .z-treecell-content{"
				+ "-webkit-user-select:none;user-select:none;}"
				+ "." + TREE_SCLASS + " .z-treerow{white-space:nowrap;}"
				+ "." + TREE_SCLASS + " .z-treecell{overflow:hidden;}"
				+ "." + TREE_SCLASS + " .z-treecell-content{display:block!important;overflow:hidden;"
				+ "text-overflow:ellipsis;white-space:nowrap;}");
		return css;
	}

	private static void setNodeTip(Treeitem item, String tip) {
		item.setTooltiptext(tip);
		Treerow row = item.getTreerow();
		if (row == null) {
			return;
		}
		for (org.zkoss.zk.ui.Component child : row.getChildren()) {
			if (child instanceof Treecell cell) {
				cell.setTooltiptext(tip);
			}
		}
	}

	private static Button iconButton(String icon, String fallbackLabel, String tooltip) {
		Button btn = new Button();
		btn.setTooltiptext(tooltip);
		if (ThemeManager.isUseFontIconForImage()) {
			btn.setLabel("");
			btn.setIconSclass(Icon.getIconSclass(icon));
			btn.setStyle(ICON_BTN_STYLE);
		} else {
			btn.setLabel(fallbackLabel);
			btn.setStyle("min-width:26px;height:26px;padding:0 6px;margin:0;line-height:26px;");
		}
		return btn;
	}

	private void collapseAll() {
		if (tree == null) {
			return;
		}
		syncingTree = true;
		try {
			collapseItems(tree.getTreechildren());
			tree.clearSelection();
		} finally {
			syncingTree = false;
		}
	}

	private static void collapseItems(Treechildren kids) {
		for (Treeitem item : childItems(kids)) {
			collapseItems(item.getTreechildren());
			if (item.isOpen()) {
				item.setOpen(false);
			}
		}
	}

	public void openFile(String path, int line) {
		if (Util.isEmpty(path, true)) {
			return;
		}
		int n = line < 1 ? 1 : line;
		pendingPath = path;
		pendingLine = n;
		refreshStatus();
		if (iframeReady) {
			syncViewer(path, n);
		}
		scheduleReveal();
	}

	private void bindDesktop() {
		Desktop zk = getDesktop();
		if (zk != null) {
			zk.setAttribute(SourceBrowserLauncher.DESKTOP_ATTR, this);
		}
	}

	/** Expand after the current AU so the client already has the tree. */
	private void scheduleReveal() {
		if (tree == null || Util.isEmpty(pendingPath, true)) {
			return;
		}
		if (tree.getDesktop() != null) {
			Events.echoEvent("onRevealInTree", tree, null);
		}
	}

	/** Expand lazy folders along {@code path} and select the file node. */
	private void revealInTree(String path) {
		if (tree == null || Util.isEmpty(path, true) || syncingTree) {
			return;
		}
		String target = stripSourcesPrefix(path);
		if (target.isEmpty()) {
			return;
		}
		Treeitem bundle = findBundleItem(target);
		if (bundle == null) {
			log.warning("revealInTree: no bundle node for " + target);
			return;
		}
		String bundlePath = stripSourcesPrefix(packPath(bundle));
		String rest = "";
		if (!target.equals(bundlePath)) {
			if (!target.startsWith(bundlePath + "/")) {
				return;
			}
			rest = target.substring(bundlePath.length() + 1);
		}
		syncingTree = true;
		try {
			Treeitem node = bundle;
			ensureLoaded(node);
			node.setOpen(true);
			if (!rest.isEmpty()) {
				String acc = bundlePath;
				for (String seg : rest.split("/")) {
					if (seg.isEmpty()) {
						continue;
					}
					acc = acc + "/" + seg;
					ensureLoaded(node);
					node.setOpen(true);
					Treeitem child = findChild(node, acc, seg);
					if (child == null) {
						log.warning("revealInTree: missing " + acc);
						return;
					}
					node = child;
				}
			}
			if (Boolean.TRUE.equals(node.getAttribute("dir"))) {
				ensureLoaded(node);
				node.setOpen(true);
			}
			tree.setSelectedItem(node);
			node.setSelected(true);
			Treerow row = node.getTreerow();
			Clients.scrollIntoView(row != null ? row : node);
		} finally {
			syncingTree = false;
		}
	}

	private Treeitem findBundleItem(String target) {
		Treechildren rootKids = tree.getTreechildren();
		if (rootKids == null) {
			return null;
		}
		Treeitem best = null;
		int bestLen = -1;
		for (Treeitem item : childItems(rootKids)) {
			String p = stripSourcesPrefix(packPath(item));
			if (p.isEmpty()) {
				continue;
			}
			if (target.equals(p) || target.startsWith(p + "/")) {
				if (p.length() > bestLen) {
					best = item;
					bestLen = p.length();
				}
			}
		}
		return best;
	}

	private static Treeitem findChild(Treeitem parent, String wantPath, String segment) {
		String want = stripSourcesPrefix(wantPath);
		for (Treeitem child : childItems(parent.getTreechildren())) {
			String p = stripSourcesPrefix(packPath(child));
			if (want.equals(p)) {
				return child;
			}
			String label = child.getLabel();
			if (segment.equals(label) || (label != null && label.startsWith(segment + " "))) {
				return child;
			}
		}
		return null;
	}

	private void ensureLoaded(Treeitem item) {
		if (item == null || !Boolean.TRUE.equals(item.getAttribute("dir"))) {
			return;
		}
		if (item.getAttribute("loaded") != null) {
			return;
		}
		loadChildren(item, packPath(item));
	}

	private static List<Treeitem> childItems(Treechildren kids) {
		List<Treeitem> out = new ArrayList<>();
		if (kids == null) {
			return out;
		}
		for (Object o : kids.getChildren()) {
			if (o instanceof Treeitem t && !t.isDisabled()) {
				out.add(t);
			}
		}
		return out;
	}

	private static String packPath(Treeitem item) {
		if (item == null) {
			return "";
		}
		Object attr = item.getAttribute("packPath");
		if (attr instanceof String s && !s.isBlank()) {
			return s;
		}
		if (item.getValue() != null) {
			return String.valueOf(item.getValue());
		}
		return "";
	}

	private static boolean samePackPath(String a, String b) {
		if (Util.isEmpty(a, true) || Util.isEmpty(b, true)) {
			return false;
		}
		return stripSourcesPrefix(a).equals(stripSourcesPrefix(b));
	}

	private static String stripSourcesPrefix(String path) {
		if (path == null) {
			return "";
		}
		String p = path.replace('\\', '/').trim();
		while (p.startsWith("./")) {
			p = p.substring(2);
		}
		if (p.startsWith("data/workspace/sources/")) {
			p = p.substring("data/workspace/sources/".length());
		} else if (p.startsWith("data/ai/sources/")) {
			p = p.substring("data/ai/sources/".length());
		} else if (p.startsWith("data/sources/")) {
			p = p.substring("data/sources/".length());
		} else if (p.startsWith("workspace/sources/")) {
			p = p.substring("workspace/sources/".length());
		} else if (p.startsWith("ai/sources/")) {
			p = p.substring("ai/sources/".length());
		} else if (p.startsWith("sources/")) {
			p = p.substring("sources/".length());
		}
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		return p;
	}

	private void fillBundleTree() {
		Treechildren rootKids = new Treechildren();
		tree.appendChild(rootKids);
		Map<String, SourcePack.BundleSource> index = SourcePack.bundleIndex();
		if (index.isEmpty()) {
			Treeitem empty = new Treeitem();
			empty.setLabel(zh() ? "（无 index.json）" : "(no index.json)");
			empty.setDisabled(true);
			rootKids.appendChild(empty);
			return;
		}
		for (SourcePack.BundleSource b : index.values()) {
			Treeitem item = dirItem(b.symbolicName, b.relativePath, true);
			if (!Util.isEmpty(b.version, true)) {
				setNodeTip(item, b.symbolicName + " " + b.version);
			}
			rootKids.appendChild(item);
		}
	}

	private Treeitem dirItem(String label, String path, boolean directory) {
		Treeitem item = new Treeitem();
		item.setLabel(label);
		item.setValue(path);
		item.setAttribute("packPath", path);
		item.setOpen(false);
		item.setAttribute("dir", Boolean.valueOf(directory));
		if (directory) {
			Treechildren kids = new Treechildren();
			Treeitem dummy = new Treeitem();
			dummy.setLabel("…");
			dummy.setDisabled(true);
			kids.appendChild(dummy);
			item.appendChild(kids);
			item.addEventListener(Events.ON_OPEN, e -> {
				if (e instanceof OpenEvent oe && oe.isOpen()) {
					loadChildren(item, path);
				}
			});
		} else {
			item.addEventListener(Events.ON_CLICK, e -> {
				if (!syncingTree && !samePackPath(path, pendingPath)) {
					openFile(path, 1);
				}
			});
		}
		bindTreeClick(item);
		String tip = stripSourcesPrefix(path);
		setNodeTip(item, Util.isEmpty(tip, true) ? label : tip);
		return item;
	}

	private void bindTreeClick(Treeitem item) {
		Treerow row = item.getTreerow();
		if (row == null) {
			row = new Treerow();
			item.appendChild(row);
			Treecell cell = new Treecell(item.getLabel());
			cell.setStyle("overflow:hidden;text-overflow:ellipsis;white-space:nowrap;");
			row.appendChild(cell);
		} else {
			for (org.zkoss.zk.ui.Component child : row.getChildren()) {
				if (child instanceof Treecell cell) {
					cell.setStyle("overflow:hidden;text-overflow:ellipsis;white-space:nowrap;");
				}
			}
		}
		if (!Boolean.TRUE.equals(item.getAttribute("dir"))) {
			return;
		}
		// Icon already toggles in ZK; do not also send onClick to the server.
		row.setWidgetListener(Events.ON_CLICK,
				"var t=event.domTarget;"
						+ "if(t&&jq(t).closest('.z-tree-icon').length)event.auStopped=true;");
		row.addEventListener(Events.ON_CLICK, e -> onTreeFolderClick(item));
	}

	private void onTreeFolderClick(Treeitem it) {
		if (syncingTree || it == null || it.isDisabled()) {
			return;
		}
		if (!Boolean.TRUE.equals(it.getAttribute("dir"))) {
			return;
		}
		boolean open = !it.isOpen();
		if (open) {
			ensureLoaded(it);
		}
		it.setOpen(open);
		if (open) {
			expandJavaSourceRoot(it);
		}
		Clients.evalJavaScript(
				"if(window.getSelection)window.getSelection().removeAllRanges();");
	}

	private void loadChildren(Treeitem item, String path) {
		Treechildren kids = item.getTreechildren();
		if (kids == null) {
			return;
		}
		if (item.getAttribute("loaded") != null) {
			return;
		}
		kids.getChildren().clear();
		try {
			List<SourcePack.DirEntry> entries = SourcePack.listChildren(path);
			if (entries.isEmpty()) {
				Treeitem empty = new Treeitem();
				empty.setLabel(zh() ? "（空）" : "(empty)");
				empty.setDisabled(true);
				kids.appendChild(empty);
			} else {
				for (SourcePack.DirEntry d : entries) {
					kids.appendChild(dirItem(d.name, d.path, d.directory));
				}
			}
			item.setAttribute("loaded", Boolean.TRUE);
			expandJavaSourceRoot(item);
		} catch (Exception ex) {
			Treeitem err = new Treeitem();
			err.setLabel(ex.getMessage());
			err.setDisabled(true);
			kids.appendChild(err);
		}
	}

	private void expandJavaSourceRoot(Treeitem item) {
		Treeitem webInf = namedChild(item, "WEB-INF");
		if (webInf != null && Boolean.TRUE.equals(webInf.getAttribute("dir"))) {
			ensureLoaded(webInf);
			webInf.setOpen(true);
			Treeitem src = namedChild(webInf, "src");
			if (src != null && Boolean.TRUE.equals(src.getAttribute("dir"))) {
				ensureLoaded(src);
				src.setOpen(true);
			}
			return;
		}
		Treeitem src = namedChild(item, "src");
		if (src != null && Boolean.TRUE.equals(src.getAttribute("dir"))) {
			ensureLoaded(src);
			src.setOpen(true);
		}
	}

	private static Treeitem namedChild(Treeitem parent, String name) {
		if (parent == null || name == null) {
			return null;
		}
		for (Treeitem child : childItems(parent.getTreechildren())) {
			if (name.equals(child.getLabel())) {
				return child;
			}
		}
		return null;
	}

	private void runQuery(boolean forceContent) {
		String q = searchBox != null ? searchBox.getValue() : null;
		if (Util.isEmpty(q, true)) {
			showBrowseView(false);
			return;
		}
		q = q.trim();
		if (!forceContent && !isContentQuery(q)) {
			List<String> files = findFiles(q);
			if (files.isEmpty() && (q.contains("/") || q.contains("."))) {
				files = findFilesByPath(q);
			}
			if (!files.isEmpty()) {
				showFileHits(files);
				if (files.size() == 1) {
					openFile(files.get(0), 1);
				}
				return;
			}
		}
		runContentSearch(q);
	}

	private static boolean isContentQuery(String q) {
		return q.indexOf(' ') >= 0 || q.indexOf('\t') >= 0;
	}

	private static List<String> findFiles(String query) {
		return SourcePack.findByFileName(query);
	}

	private static List<String> findFilesByPath(String query) {
		String needle = stripSourcesPrefix(query).toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		if (needle.length() < 2) {
			return out;
		}
		for (Path file : SourcePack.listIndexedFiles()) {
			String rel = SourcePack.relativeToRoots(file);
			if (stripSourcesPrefix(rel).toLowerCase(Locale.ROOT).contains(needle)) {
				out.add(rel);
				if (out.size() >= SourceSearch.DEFAULT_MAX_HITS) {
					break;
				}
			}
		}
		return out;
	}

	private void runContentSearch(String q) {
		try {
			List<SourceSearch.Hit> hits = SourceSearch.search(q, "**/*.java", SourceSearch.DEFAULT_MAX_HITS);
			hitList.getItems().clear();
			if (hits.isEmpty()) {
				hitList.appendChild(messageItem(zh() ? "无命中" : "No hits"));
				showSearchView(zh() ? "无命中" : "No hits");
				return;
			}
			for (SourceSearch.Hit hit : hits) {
				hitList.appendChild(hitItem(hit.path, hit.line, hit.text));
			}
			showSearchView((zh() ? "命中 " : "Hits ") + hits.size());
		} catch (Exception e) {
			status.setValue(e.getMessage());
		}
	}

	private void showFileHits(List<String> files) {
		hitList.getItems().clear();
		for (String path : files) {
			hitList.appendChild(hitItem(path, 1, bundleHint(path)));
		}
		showSearchView((zh() ? "文件 " : "Files ") + files.size());
	}

	private void showSearchView(String meta) {
		lastSearchMeta = meta;
		if (tree != null) {
			tree.setVisible(false);
		}
		if (hitList != null) {
			hitList.setVisible(true);
		}
		setSearchMode(true);
		refreshStatus();
	}

	private void showBrowseView(boolean clearQuery) {
		if (clearQuery && searchBox != null) {
			searchBox.setValue("");
		}
		if (hitList != null) {
			if (clearQuery) {
				hitList.getItems().clear();
				lastSearchMeta = null;
			}
			hitList.setVisible(false);
		}
		if (tree != null) {
			tree.setVisible(true);
		}
		setSearchMode(false);
		if (backToHits != null) {
			backToHits.setVisible(!clearQuery && hasHits());
		}
		refreshStatus();
		scheduleReveal();
	}

	private void setSearchMode(boolean search) {
		if (revealBtn != null) {
			revealBtn.setVisible(search);
		}
		if (clearBtn != null) {
			clearBtn.setVisible(search);
		}
		if (collapseBtn != null) {
			collapseBtn.setVisible(!search);
		}
		if (locateBtn != null) {
			locateBtn.setVisible(!search);
		}
		if (backToHits != null && search) {
			backToHits.setVisible(false);
		}
	}

	private void refreshStatus() {
		if (status == null) {
			return;
		}
		String file = Util.isEmpty(pendingPath, true) ? null : pendingPath + ":" + pendingLine;
		if (lastSearchMeta != null && file != null) {
			status.setValue(lastSearchMeta + "  ·  " + file);
		} else if (lastSearchMeta != null) {
			status.setValue(lastSearchMeta);
		} else if (file != null) {
			status.setValue(file);
		} else {
			status.setValue(zh() ? "只读装配包" : "Read-only assembled pack");
		}
	}

	/** Switch to the tree and reveal the already-open file, or the selected hit. */
	private void showInTree() {
		if (Util.isEmpty(pendingPath, true)) {
			applyHit(hitList != null ? hitList.getSelectedItem() : null);
		}
		showBrowseView(false);
	}

	private void revealSelectedHit() {
		Listitem it = hitList != null ? hitList.getSelectedItem() : null;
		if (it == null) {
			showInTree();
			return;
		}
		showHitInTree(it);
	}

	private void showHitsAgain() {
		if (!hasHits()) {
			return;
		}
		showSearchView(lastSearchMeta != null ? lastSearchMeta : (zh() ? "搜索结果" : "Results"));
	}

	private boolean hasHits() {
		if (hitList == null) {
			return false;
		}
		for (Listitem it : hitList.getItems()) {
			if (!it.isDisabled() && it.getValue() instanceof String) {
				return true;
			}
		}
		return false;
	}

	private void openHit(Listitem it) {
		applyHit(it);
	}

	private void showHitInTree(Listitem it) {
		applyHit(it);
		showBrowseView(false);
	}

	private void applyHit(Listitem it) {
		if (it == null || !(it.getValue() instanceof String spec)) {
			return;
		}
		int bar = spec.lastIndexOf('|');
		if (bar <= 0) {
			return;
		}
		openFile(spec.substring(0, bar), parseLine(spec.substring(bar + 1)));
	}

	private Listitem hitItem(String path, int line, String snippet) {
		Listitem it = new Listitem();
		it.setValue(path + "|" + line);
		it.setStyle("white-space:normal;");
		Listcell cell = new Listcell();
		cell.setStyle("white-space:normal;line-height:1.25;");
		Div row = new Div();
		row.setStyle("display:flex;align-items:flex-start;justify-content:space-between;gap:" + TOOL_GAP + ";padding:2px 0;");
		Div box = new Div();
		box.setStyle("display:flex;flex-direction:column;gap:2px;min-width:0;flex:1;");
		Label title = new Label(shortName(path) + " : " + line);
		title.setStyle("font-weight:600;font-size:12px;");
		String sub = bundleHint(path);
		if (!Util.isEmpty(snippet, true) && !snippet.equals(sub)) {
			String clip = snippet.length() > 100 ? snippet.substring(0, 100) + "…" : snippet;
			sub = sub + " · " + clip;
		}
		Label detail = new Label(sub);
		detail.setStyle("font-size:11px;color:#666;");
		box.appendChild(title);
		box.appendChild(detail);
		Button reveal = iconButton(Icon.FOLDER, zh() ? "树" : "Tree",
				zh() ? "在目录树中显示此文件" : "Reveal this file in the tree");
		reveal.addEventListener(Events.ON_CLICK, e -> {
			e.stopPropagation();
			openFile(path, line);
			showBrowseView(false);
		});
		row.appendChild(box);
		row.appendChild(reveal);
		cell.appendChild(row);
		it.appendChild(cell);
		return it;
	}

	private static Listitem messageItem(String text) {
		Listitem it = new Listitem();
		it.setDisabled(true);
		Listcell cell = new Listcell(text);
		cell.setStyle("color:#888;font-size:12px;");
		it.appendChild(cell);
		return it;
	}

	private static String bundleHint(String path) {
		String p = stripSourcesPrefix(path);
		int slash = p.indexOf('/');
		return slash > 0 ? p.substring(0, slash) : p;
	}

	/** Same host as the desktop, outside the /webui context path. */
	private String viewerHrefWithTicket() {
		return viewerBase() + "?ticket=" + urlEnc(ticket) + "&lang=" + (zh() ? "zh" : "en");
	}

	private static String viewerBase() {
		Execution exec = Executions.getCurrent();
		if (exec == null) {
			return "/sourcebrowser/viewer/index.html";
		}
		String host = exec.getHeader("Host");
		if (Util.isEmpty(host, true)) {
			host = exec.getServerName();
			int port = exec.getServerPort();
			if (port > 0 && port != 80 && port != 443) {
				host = host + ":" + port;
			}
		}
		return exec.getScheme() + "://" + host + "/sourcebrowser/viewer/index.html";
	}

	/**
	 * Point the iframe at {@code location.origin} (avoids Host vs localhost mismatch)
	 * and pass ticket + path via query/hash so opening a file does not depend on postMessage.
	 */
	private void syncViewer(String path, int line) {
		if (iframe == null) {
			return;
		}
		String uuid = iframe.getUuid();
		StringBuilder js = new StringBuilder();
		js.append("(function(){");
		js.append("var w=zk.Widget.$('").append(uuid).append("');");
		js.append("var f=w&&w.$n?w.$n():document.getElementById('").append(uuid).append("');");
		js.append("if(!f)return;");
		js.append("if(f.tagName!=='IFRAME'&&f.querySelector){f=f.querySelector('iframe')||f;}");
		js.append("var ticket=").append(jsString(ticket)).append(";");
		js.append("var origin=location.origin;");
		js.append("var lang=").append(jsString(zh() ? "zh" : "en")).append(";");
		js.append("var base=origin+'/sourcebrowser/viewer/index.html?ticket='+encodeURIComponent(ticket)+'&lang='+encodeURIComponent(lang);");
		js.append("var path=").append(path == null ? "null" : jsString(path)).append(";");
		js.append("var line=").append(Math.max(1, line)).append(";");
		js.append("var hash=path?('#p='+encodeURIComponent(path)+'&l='+line):'';");
		js.append("var src=f.src||'';");
		js.append("if(src.indexOf('/sourcebrowser/viewer/index.html')<0||src.indexOf(origin)!==0){f.src=base+hash;return;}");
		js.append("if(!path)return;");
		js.append("try{var cw=f.contentWindow;");
		js.append("if(cw&&cw.location&&cw.location.origin===origin){");
		js.append("if((cw.location.hash||'')!==hash){cw.location.hash=hash;}");
		js.append("cw.postMessage({type:'open',ticket:ticket,path:path,line:line},origin);return;}}catch(e){}");
		js.append("f.src=base+hash;");
		js.append("})();");
		Clients.evalJavaScript(js.toString());
	}

	private static String urlEnc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	private static String jsString(String s) {
		if (s == null) {
			return "''";
		}
		return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'";
	}

	private static String shortName(String path) {
		if (path == null) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	private static int parseLine(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static boolean zh() {
		String lang = Env.getAD_Language(Env.getCtx());
		return lang != null && lang.toLowerCase().startsWith("zh");
	}

	@Override
	public void onPageAttached(org.zkoss.zk.ui.Page newpage, org.zkoss.zk.ui.Page oldpage) {
		super.onPageAttached(newpage, oldpage);
		bindDesktop();
		scheduleReveal();
	}

	@Override
	public void onPageDetached(org.zkoss.zk.ui.Page page) {
		BrowseTicketStore.revoke(ticket);
		Desktop zk = getDesktop();
		if (zk != null && zk.getAttribute(SourceBrowserLauncher.DESKTOP_ATTR) == this) {
			zk.removeAttribute(SourceBrowserLauncher.DESKTOP_ATTR);
		}
		super.onPageDetached(page);
	}
}
