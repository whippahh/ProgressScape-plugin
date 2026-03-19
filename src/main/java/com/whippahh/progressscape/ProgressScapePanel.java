package com.whippahh.progressscape;

import net.runelite.client.ui.PluginPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

public class ProgressScapePanel extends PluginPanel
{
    private final JLabel statusLabel;
    private final ProgressScapePlugin plugin;

    public ProgressScapePanel(ProgressScapePlugin plugin)
    {
        this.plugin = plugin;
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("ProgressScape");
        title.setFont(new Font("RuneScape Bold", Font.BOLD, 16));
        title.setForeground(new Color(200, 150, 50));
        add(title, BorderLayout.NORTH);

        JButton syncButton = new JButton("Sync Collection Log");
        syncButton.setToolTipText("Open your Collection Log in-game, then click this");
        syncButton.addActionListener(e -> plugin.syncNow(true));
        add(syncButton, BorderLayout.CENTER);

        statusLabel = new JLabel("Ready");
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        statusLabel.setForeground(Color.GRAY);
        add(statusLabel, BorderLayout.SOUTH);
    }

    public void setStatus(String message)
    {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }
}
