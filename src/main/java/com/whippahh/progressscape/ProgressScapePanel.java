package com.whippahh.progressscape;

import net.runelite.client.ui.PluginPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridLayout;
import java.net.URI;

public class ProgressScapePanel extends PluginPanel
{
    private final JLabel statusLabel;
    private final ProgressScapePlugin plugin;

    public ProgressScapePanel(ProgressScapePlugin plugin)
    {
        this.plugin = plugin;
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Title
        JLabel title = new JLabel("ProgressScape");
        title.setFont(new Font("RuneScape Bold", Font.BOLD, 16));
        title.setForeground(new Color(200, 150, 50));
        add(title, BorderLayout.NORTH);

        // Centre panel — button + link stacked
        JPanel centrePanel = new JPanel(new GridLayout(2, 1, 0, 6));
        centrePanel.setOpaque(false);

        JButton syncButton = new JButton("Sync Collection Log");
        syncButton.setToolTipText("Open your Collection Log in-game, then click this");
        syncButton.addActionListener(e -> plugin.syncNow(true));
        centrePanel.add(syncButton);

        JLabel websiteLink = new JLabel("<html><a href=''>progressscape.net</a></html>");
        websiteLink.setFont(new Font("Arial", Font.PLAIN, 11));
        websiteLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
        websiteLink.setToolTipText("Open ProgressScape website");
        websiteLink.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                try
                {
                    Desktop.getDesktop().browse(new URI("https://progressscape.net"));
                }
                catch (Exception ex)
                {
                    // ignore
                }
            }
        });
        centrePanel.add(websiteLink);

        add(centrePanel, BorderLayout.CENTER);

        // Status label
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
