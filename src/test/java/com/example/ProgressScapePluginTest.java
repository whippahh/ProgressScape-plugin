package com.whippahh.progressscape;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ProgressScapePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ProgressScapePlugin.class);
		RuneLite.main(args);
	}
}
