package org.idempiere.myappx.plugin.sourcebrowser.api;

/**
 * Optional OSGi service so other plugins can open the read-only Source Browser
 * at a pack-relative path and line.
 */
public interface ISourceBrowser {

	/** @return true when enabled and the assembled pack is readable */
	boolean isAvailable();

	/**
	 * Open or focus the Source Browser form and show {@code path} around {@code line}.
	 *
	 * @param path pack-relative path (e.g. {@code org.adempiere.base/src/org/compiere/model/MOrder.java})
	 *            or a short file name ({@code MSysConfig.java} / {@code MSysConfig})
	 * @param line 1-based line, or {@code null}/{@code <=0} for line 1
	 */
	void open(String path, Integer line);

	/**
	 * Like {@link #open(String, Integer)}; when {@code line} is missing/{@code 1} and
	 * {@code symbol} is a Java identifier (e.g. {@code ALogin_ShowDate}), jump to the
	 * first line in that file that contains the symbol.
	 */
	default void open(String path, Integer line, String symbol) {
		open(path, line);
	}
}
