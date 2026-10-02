package org.idempiere.myappx.plugin.sourcebrowser.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived tickets minted by the ZK form (already authenticated) so the
 * Monaco iframe can call {@code /sourcebrowser/api/*} without REST/JWT.
 */
public final class BrowseTicketStore {

	private static final long TTL_MS = TimeUnit.HOURS.toMillis(8);

	public static final class Ticket {
		public final String id;
		public final int userId;
		public final int roleId;
		public final int clientId;
		public final long expiresAtMs;

		Ticket(String id, int userId, int roleId, int clientId, long expiresAtMs) {
			this.id = id;
			this.userId = userId;
			this.roleId = roleId;
			this.clientId = clientId;
			this.expiresAtMs = expiresAtMs;
		}

		boolean expired() {
			return System.currentTimeMillis() > expiresAtMs;
		}
	}

	private static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();

	private BrowseTicketStore() {}

	public static String issue(int userId, int roleId, int clientId) {
		purge();
		String id = UUID.randomUUID().toString();
		TICKETS.put(id, new Ticket(id, userId, roleId, clientId, System.currentTimeMillis() + TTL_MS));
		return id;
	}

	public static Ticket get(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		Ticket t = TICKETS.get(id.trim());
		if (t == null || t.expired()) {
			if (t != null) {
				TICKETS.remove(id);
			}
			return null;
		}
		return t;
	}

	public static void revoke(String id) {
		if (id != null) {
			TICKETS.remove(id);
		}
	}

	private static void purge() {
		long now = System.currentTimeMillis();
		TICKETS.entrySet().removeIf(e -> e.getValue().expiresAtMs < now);
	}
}
