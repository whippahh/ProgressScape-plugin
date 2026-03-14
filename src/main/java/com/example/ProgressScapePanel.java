package com.example;

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
    private final ExamplePlugin plugin;

    public ProgressScapePanel(ExamplePlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Title
        JLabel title = new JLabel("ProgressScape");
        title.setFont(new Font("RuneScape Bold", Font.BOLD, 16));
        title.setForeground(new Color(200, 150, 50));
        add(title, BorderLayout.NORTH);

        // Collection log sync button
        JButton syncButton = new JButton("Sync Collection Log");
        syncButton.setToolTipText("Open your Collection Log in-game, then click this");
        syncButton.addActionListener(e -> plugin.syncNow(true));
        add(syncButton, BorderLayout.CENTER);

        // Status label
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        statusLabel.setForeground(Color.GRAY);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Updates the status label text — always called on the EDT.
     */
    public void setStatus(String message)
    {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }
}