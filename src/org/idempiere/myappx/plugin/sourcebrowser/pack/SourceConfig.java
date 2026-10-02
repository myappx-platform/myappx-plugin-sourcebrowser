package org.idempiere.myappx.plugin.sourcebrowser.pack;

import org.compiere.model.MSysConfig;
import org.compiere.util.Util;

/**
 * SysConfig / env for the Source Browser. This plugin is independent: it only
 * reads {@link #ENABLED_KEY} and {@link #ROOTS_KEY} (and the matching
 * {@code IDEMPIERE_*} environment variables). Environment variables win over
 * SysConfig so portable {@code env.bat} can leave {@link #ROOTS_KEY} empty
 * and point at {@code {portable}/workspace/sources}.
 */
public final class SourceConfig {

	public static final String ENABLED_KEY = "MYAPPX_SOURCE_ENABLED";
	public static final String ROOTS_KEY = "MYAPPX_SOURCE_ROOTS";

	private SourceConfig() {}

	public static boolean isEnabled() {
		return flag(ENABLED_KEY, "IDEMPIERE_MYAPPX_SOURCE_ENABLED");
	}

	public static String getRootsConfigured() {
		return first(ROOTS_KEY, "IDEMPIERE_MYAPPX_SOURCE_ROOTS");
	}

	private static boolean flag(String key, String env) {
		String v = System.getenv(env);
		if (v != null && ("Y".equalsIgnoreCase(v.trim()) || "true".equalsIgnoreCase(v.trim()))) {
			return true;
		}
		return "Y".equalsIgnoreCase(MSysConfig.getValue(key, "N"));
	}

	private static String first(String key, String env) {
		String v = System.getenv(env);
		if (!Util.isEmpty(v, true)) {
			return v.trim();
		}
		String cfg = MSysConfig.getValue(key, "");
		return cfg != null ? cfg.trim() : "";
	}
}
