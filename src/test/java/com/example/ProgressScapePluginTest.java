package com.whippahh.progressscape;

import net.runelite.client.externalplugins.ExternalPluginManager;
import org.junit.Test;

public class ProgressScapePluginTest
{
    @Test
    public void testPlugin()
    {
        ExternalPluginManager.loadBuiltin(ProgressScapePlugin.class);
    }
}
