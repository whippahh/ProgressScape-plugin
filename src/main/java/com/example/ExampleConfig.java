package com.example;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("progressscape")
public interface ExampleConfig extends Config
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
		return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhiZm52aWpmamJveGhhbWptbGhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM0NjcwNDYsImV4cCI6MjA4OTA0MzA0Nn0.wg9Ho_rZBXqH7ulFkT4p1pAamC5bpBDTRXI75_rCPAY";
	}
}