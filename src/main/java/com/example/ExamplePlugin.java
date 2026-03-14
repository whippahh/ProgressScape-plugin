package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
		name = "ProgressScape",
		description = "Syncs your quests, diaries, boss KC and collection log to ProgressScape",
		tags = {"progressscape", "quests", "diaries", "bosses", "sync"}
)
public class ExamplePlugin extends Plugin
{
	// Matches: "Your Zulrah kill count is: 441."
	private static final Pattern KC_PATTERN =
			Pattern.compile("Your (.+) kill count is: (\\d+)\\.");

	@Inject
	private Client client;

	@Inject
	private ExampleConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SyncService syncService;

	private ProgressScapePanel panel;
	private NavigationButton navButton;

	// Tracks whether we need to fire the login sync on the next tick
	private boolean pendingLoginSync = false;

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
			// Don't sync immediately — wait for the next tick so game data is ready
			pendingLoginSync = true;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Logged out — fire final sync then clear KC cache
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
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Only care about game messages
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
	 * Called by the panel button (includeCollectionLog=true)
	 * and internally on login/logout (includeCollectionLog=false).
	 */
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
		syncService.sync(username, includeCollectionLog, client, config, panel);
	}

	@Provides
	ExampleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExampleConfig.class);
	}
}