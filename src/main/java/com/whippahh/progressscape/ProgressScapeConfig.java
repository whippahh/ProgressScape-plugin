package com.whippahh.progressscape;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("progressscape")
public interface ProgressScapeConfig extends Config
{
	@ConfigItem(
			keyName = "supabaseUrl",
			name = "Supabase URL",
			description = "Your Supabase project URL",
			position = 1
	)
	default String supabaseUrl()
	{
		return "https://hbfnvijfjboxhamjmlhm.supabase.co";
	}

	@ConfigItem(
			keyName = "supabaseKey",
			name = "Supabase Anon Key",
			description = "Your Supabase anon public key",
			position = 2,
			secret = true
	)
	default String supabaseKey()
	{
		return "";
	}
}
