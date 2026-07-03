package com.arenabot.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridLayout;

/**
 * Live AI-analytics area: latest token counts / latency and a tail of recent
 * telemetry bus lines. Pulls from {@link TelemetryBus} on the EDT every
 * {@code REFRESH_MS} via {@link javax.swing.Timer} (constructed lazily by
 * {@link TelemetryDashboard}).
 */
public final class AnalyticsPanel extends JPanel {

    private static final int REFRESH_MS = 250;

    private final TelemetryBus bus;
    private final JLabel role  = new JLabel("role: -");
    private final JLabel inOut = new JLabel("tok in/out: 0 / 0");
    private final JLabel latency = new JLabel("elapsed: 0ms");
    private final JLabel detail = new JLabel("-");
    private final JTextArea tail = new JTextArea(8, 30);

    public AnalyticsPanel(TelemetryBus bus) {
        this.bus = bus;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.BG);

        JPanel summary = new JPanel(new GridLayout(4, 1));
        summary.setBackground(ColorScheme.BG);
        for (JLabel lbl : new JLabel[]{ role, inOut, latency, detail }) {
            lbl.setForeground(ColorScheme.FG_TEXT);
            summary.add(lbl);
        }
        add(summary, BorderLayout.NORTH);

        tail.setEditable(false);
        tail.setBackground(ColorScheme.BG);
        tail.setForeground(ColorScheme.FG_TEXT);
        add(new JScrollPane(tail), BorderLayout.CENTER);

        scheduleRefresh();
    }

    private void scheduleRefresh() {
        javax.swing.Timer t = new javax.swing.Timer(REFRESH_MS, e -> SwingUtilities.invokeLater(this::refresh));
        t.setRepeats(true);
        t.start();
    }

    private void refresh() {
        TelemetryBus.Sample s = bus.latest();
        role.setText("role: " + s.label());
        inOut.setText("tok in/out: " + s.tokensIn() + " / " + s.tokensOut());
        latency.setText("elapsed: " + s.elapsedMs() + "ms");
        detail.setText(s.text());
        tail.setText(String.join("\n", bus.recentLinesSnapshot()));
    }
}
