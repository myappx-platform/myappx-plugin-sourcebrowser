package org.idempiere.myappx.plugin.sourcebrowser.form;

import org.adempiere.webui.factory.IMappedFormFactory;
import org.osgi.framework.BundleContext;

/**
 * Registers {@code @Form} classes with {@link IMappedFormFactory} at bundle start.
 */
public class SourceBrowserFormRegistrar {

	private volatile IMappedFormFactory mappedFormFactory;

	protected void setMappedFormFactory(IMappedFormFactory mappedFormFactory) {
		this.mappedFormFactory = mappedFormFactory;
	}

	protected void unsetMappedFormFactory(IMappedFormFactory mappedFormFactory) {
		if (this.mappedFormFactory == mappedFormFactory) {
			this.mappedFormFactory = null;
		}
	}

	protected void activate(BundleContext context) {
		if (mappedFormFactory != null) {
			mappedFormFactory.scan(context, "org.idempiere.myappx.plugin.sourcebrowser.form");
		}
	}
}
