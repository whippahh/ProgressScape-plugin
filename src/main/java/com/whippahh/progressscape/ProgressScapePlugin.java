package com.whippahh.progressscape;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
		name = "ProgressScape",
		description = "Syncs your quests, diaries, boss KC and collection log to ProgressScape",
		tags = {"progressscape", "quests", "diaries", "bosses", "sync"}
)
public class ProgressScapePlugin extends Plugin
{
	private static final Pattern KC_PATTERN =
			Pattern.compile("Your (.+) kill count is: (\\d+)\\.");

	// ── Collection log full-scan trick ──────────────────────────────────
	// Borrowed from the (open-source) WikiSync plugin: the game only ever
	// builds a collection log item slot's widget for the category currently
	// on screen, one at a time — UNLESS you trigger the log's own built-in
	// search-all feature, which briefly renders every obtained item across
	// every category in one burst. clientscript 4100 fires once per item as
	// it's built during that burst (and only for items actually obtained —
	// locked slots use a different, simpler build path that never invokes
	// it), so listening for it during the burst gives us the full log in
	// one click instead of requiring the user to open every category.
	private static final int COLLECTION_LOG_GROUP_ID = 621;
	private static final int COLLECTION_LOG_SEARCH_TOGGLE_CHILD = 76;
	private static final int COLLECTION_LOG_SEARCH_SCRIPT_ID = 2240;
	private static final int COLLECTION_LOG_ITEM_SCRIPT_ID = 4100;
	private static final int COLLECTION_LOG_SYNC_DELAY_TICKS = 5;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SyncService syncService;

	@Inject
	private ClientThread clientThread;

	private ProgressScapePanel panel;
	private NavigationButton navButton;
	private boolean pendingLoginSync = false;
	private int collectionLogSyncCountdown = -1;

	@Override
	protected void startUp() throws Exception
	{
		panel = new ProgressScapePanel(this);
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		navButton = NavigationButton.builder()
				.tooltip("ProgressScape")
				.icon(icon)
				.priority(5)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
		log.debug("ProgressScape started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		log.debug("ProgressScape stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			pendingLoginSync = true;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			syncNow(false);
			syncService.clearKCs();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (pendingLoginSync)
		{
			pendingLoginSync = false;
			syncNow(false);
		}

		if (collectionLogSyncCountdown > 0)
		{
			collectionLogSyncCountdown--;
			if (collectionLogSyncCountdown == 0)
			{
				syncNow(true);
			}
		}
	}

	/**
	 * Fires once per collection log item slot as the game builds it. Only
	 * obtained items take this script path, so anything seen here is a
	 * confirmed obtained item — this is what actually populates the synced
	 * list, whether triggered by {@link #startCollectionLogSync()}'s search
	 * burst or just by the player naturally browsing their log.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != COLLECTION_LOG_ITEM_SCRIPT_ID) return;
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 2 || !(args[1] instanceof Integer)) return;
		int itemId = (Integer) args[1];
		ItemComposition comp = client.getItemDefinition(itemId);
		if (comp == null) return;
		syncService.recordCollectionLogItem(comp.getName());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE) return;
		String message = event.getMessage();
		Matcher matcher = KC_PATTERN.matcher(message);
		if (matcher.matches())
		{
			String bossName = matcher.group(1);
			int kc = Integer.parseInt(matcher.group(2));
			syncService.updateBossKC(bossName, kc);
			log.debug("KC captured: {} = {}", bossName, kc);
		}
	}

	/**
	 * Entry point for syncs triggered from outside the client thread (e.g. a Swing
	 * button click on the plugin panel). RuneLite requires Client/Widget access to
	 * happen on the client thread, so this hands the work off via ClientThread
	 * instead of touching client state directly from the calling (EDT) thread.
	 */
	public void syncNowOnClientThread(boolean includeCollectionLog)
	{
		clientThread.invoke(() -> {
			if (includeCollectionLog)
			{
				startCollectionLogSync();
			}
			else
			{
				syncNow(false);
			}
		});
	}

	/**
	 * Forces the collection log's built-in search-all to fire (see the block
	 * comment above the COLLECTION_LOG_* constants), then waits a few ticks
	 * for the resulting render burst — and the item captures it triggers —
	 * to finish before actually sending the sync via {@link #syncNow}.
	 */
	private void startCollectionLogSync()
	{
		Widget frame = client.getWidget(COLLECTION_LOG_GROUP_ID, 0);
		if (frame == null || frame.isHidden())
		{
			panel.setStatus("Open your Collection Log first!");
			return;
		}
		panel.setStatus("Scanning collection log...");
		int searchToggleWidgetId = (COLLECTION_LOG_GROUP_ID << 16) | COLLECTION_LOG_SEARCH_TOGGLE_CHILD;
		client.menuAction(-1, searchToggleWidgetId, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(COLLECTION_LOG_SEARCH_SCRIPT_ID);
		collectionLogSyncCountdown = COLLECTION_LOG_SYNC_DELAY_TICKS;
	}

	public void syncNow(boolean includeCollectionLog)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.setStatus("Not logged in");
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null) return;
		String username = local.getName();
		if (username == null || username.isEmpty()) return;
		panel.setStatus("Syncing...");
		syncService.sync(username, includeCollectionLog, client, panel);
	}
}
