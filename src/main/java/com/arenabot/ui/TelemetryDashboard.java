package com.arenabot.ui;

import com.arenabot.bot.BotOrchestrator;
import com.arenabot.config.AppConfig;
import com.arenabot.memory.MemorySnapshot;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Toolkit;

/**
 * Top-level Swing dashboard: holds the {@link RoomCodePanel}, the
 * {@link GridPanel} and the {@link AnalyticsPanel}. Owns the Swing EDT
 * repaint loop but never blocks it — the bot thread reads via
 * {@link BotOrchestrator#lastState()} and {@code MapMemory.snapshot()}.
 */
public final class TelemetryDashboard {

    private final JFrame frame;
    private final RoomCodePanel header;
    private final GridPanel grid;
    private final AnalyticsPanel analytics;
    private final BotOrchestrator orchestrator;
    private final AppConfig config;

    public TelemetryDashboard(AppConfig config, BotOrchestrator orch, TelemetryBus bus) {
        this.config = config;
        this.orchestrator = orch;
        this.frame = new JFrame("Arena Bot (" + config.roomCode() + ")");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(Toolkit.getDefaultToolkit().getScreenSize().width * 3 / 4,
                      Toolkit.getDefaultToolkit().getScreenSize().height * 3 / 4);

        header = new RoomCodePanel(config.roomCode());
        header.onStart = this::startBot;
        header.onStop  = () -> {
            orchestrator.stop();
            header.setStatus("stopped");
        };
        grid = new GridPanel(config.ui().gridPixelSize(), config.ui().gridXMax(), config.ui().gridYMax());
        analytics = new AnalyticsPanel(bus);

        JPanel north = new JPanel(new BorderLayout());
        north.add(header, BorderLayout.CENTER);
        frame.add(north, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, grid, analytics);
        split.setResizeWeight(0.6);
        frame.add(split, BorderLayout.CENTER);

        launchUpdater();
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    private void launchUpdater() {
        javax.swing.Timer t = new javax.swing.Timer(200, e -> SwingUtilities.invokeLater(this::paint));
        t.setRepeats(true);
        t.start();
    }

    private void paint() {
        var s = orchestrator.lastState();
        if (s != null) {
            // Reflect current tile imperatively — UI sees whatever orchestrator
            // knows at the start of this EDT tick.
            grid.update(currentSnapshot(), s.meuEstado());
            header.setStatus(s.hasGameStarted() ? "running · game started" : "waiting for game start");
        } else {
            grid.update(new MemorySnapshot(java.util.List.of(), java.util.List.of(),
                            java.util.Map.of(), java.util.List.of(),
                            new com.arenabot.model.MeuEstado(), 0L),
                    null);
        }
    }

    private MemorySnapshot currentSnapshot() {
        // The orchestrator already calls memory.observe on every tick; this
        // is a thin facade for the UI thread that just reads through.
        return orchestrator.stateSnapshotForUi();
    }

    private void startBot(String roomCode) {
        config.getClass(); // keep parameter used; orchestrator uses config-bound room below.
        orchestrator.startWithRoomCode(roomCode);
        header.setStatus("running");
    }
}
