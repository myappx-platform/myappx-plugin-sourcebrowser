package org.idempiere.myappx.plugin.sourcebrowser.form;

import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.desktop.IDesktop;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.part.WindowContainer;
import org.adempiere.webui.session.SessionManager;
import org.compiere.model.MForm;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.idempiere.myappx.plugin.sourcebrowser.api.ISourceBrowser;
import org.idempiere.myappx.plugin.sourcebrowser.pack.SourceConfig;
import org.idempiere.myappx.plugin.sourcebrowser.pack.SourcePack;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Tabbox;
import org.zkoss.zul.Tabpanel;

/**
 * Opens or focuses the Source Browser tab. Registered as {@link ISourceBrowser}.
 */
public final class SourceBrowserLauncher implements ISourceBrowser {

	public static final String FORM_CLASSNAME =
			"org.idempiere.myappx.plugin.sourcebrowser.form.SourceBrowserForm";
	public static final String DESKTOP_ATTR = "myappx.SourceBrowserForm";

	private static final CLogger log = CLogger.getCLogger(SourceBrowserLauncher.class);

	@Override
	public boolean isAvailable() {
		return SourceConfig.isEnabled() && !SourcePack.listRoots().isEmpty();
	}

	@Override
	public void open(String path, Integer line) {
		open(path, line, null);
	}

	@Override
	public void open(String path, Integer line, String symbol) {
		if (Executions.getCurrent() != null) {
			openOnUi(path, line, symbol);
		} else {
			AEnv.executeAsyncDesktopTask(() -> openOnUi(path, line, symbol));
		}
	}

	public static void openOnUi(String path, Integer line) {
		openOnUi(path, line, null);
	}

	public static void openOnUi(String path, Integer line, String symbol) {
		int lineNo = line == null || line.intValue() < 1 ? 1 : line.intValue();
		String resolved = path;
		if (!Util.isEmpty(path, true)) {
			SourcePack.OpenTarget target = SourcePack.resolveForOpen(path);
			if (target == null || Util.isEmpty(target.path, true)) {
				notify(Env.getAD_Language(Env.getCtx()),
						"Not in the assembled source pack: " + path,
						"装配包中没有该文件: " + path);
				return;
			}
			resolved = target.path;
			if (target.alternatives > 0) {
				notify(Env.getAD_Language(Env.getCtx()),
						"Opened " + resolved + " (" + target.alternatives + " other same-name file(s)).",
						"已打开 " + resolved + "（另有 " + target.alternatives + " 处同名）");
			}
			if (lineNo <= 1 && !Util.isEmpty(symbol, true)) {
				int found = SourcePack.findLineContaining(resolved, symbol);
				if (found > 0) {
					lineNo = found;
				}
			}
		}
		SourceBrowserForm existing = currentForm();
		if (existing != null && existing.getPage() != null) {
			selectTab(existing);
			if (!Util.isEmpty(resolved, true)) {
				existing.openFile(resolved, lineNo);
			}
			return;
		}
		int formId = findFormId();
		if (formId <= 0) {
			log.warning("AD_Form not found for " + FORM_CLASSNAME);
			return;
		}
		IDesktop desktop = SessionManager.getAppDesktop();
		if (desktop == null) {
			return;
		}
		ADForm form = desktop.openForm(formId);
		if (form instanceof SourceBrowserForm src && !Util.isEmpty(resolved, true)) {
			src.openFile(resolved, lineNo);
		}
	}

	private static void notify(String adLanguage, String en, String zh) {
		boolean chinese = adLanguage != null && adLanguage.toLowerCase().startsWith("zh");
		Clients.showNotification(chinese ? zh : en, "info", null, "middle_center", 4000, true);
	}

	public static SourceBrowserForm currentForm() {
		Desktop zk = Executions.getCurrent() != null ? Executions.getCurrent().getDesktop() : null;
		if (zk == null) {
			return null;
		}
		Object attr = zk.getAttribute(DESKTOP_ATTR);
		return attr instanceof SourceBrowserForm f && f.getPage() != null ? f : null;
	}

	public static int findFormId() {
		MForm form = new Query(Env.getCtx(), MForm.Table_Name, "Classname=? AND IsActive='Y'", null)
				.setParameters(FORM_CLASSNAME)
				.setOnlyActiveRecords(true)
				.first();
		return form != null ? form.getAD_Form_ID() : 0;
	}

	private static void selectTab(ADForm form) {
		try {
			form.focus();
			Component parent = form.getParent();
			while (parent != null && !(parent instanceof Tabpanel)) {
				parent = parent.getParent();
			}
			if (parent instanceof Tabpanel panel) {
				var tab = panel.getLinkedTab();
				if (tab != null && panel.getTabbox() != null) {
					Tabbox box = panel.getTabbox();
					box.setSelectedTab(tab);
					Events.postEvent(WindowContainer.ON_WINDOW_CONTAINER_SELECTION_CHANGED_EVENT, form, null);
				}
			}
		} catch (Exception e) {
			log.fine("select tab: " + e.getMessage());
		}
	}
}
