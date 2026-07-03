package com.arenabot.ui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.function.Consumer;

/**
 * Top-row JPanel: a room code text field + start/stop button + status label.
 * Callbacks wired via {@link #onStart} and {@link #onStop} so the panel never
 * knows about the orchestrator directly.
 */
public final class RoomCodePanel extends JPanel {

    private final JTextField roomCode = new JTextField(8);
    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn  = new JButton("Stop");
    private final JLabel status = new JLabel("idle");

    public Consumer<String> onStart = code -> {};
    public Runnable onStop = () -> {};

    public RoomCodePanel(String initialRoomCode) {
        setLayout(new GridBagLayout());
        setBackground(ColorScheme.BG);
        roomCode.setText(initialRoomCode);

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; add(new JLabel("Room code:"), c);
        c.gridx = 1; add(roomCode, c);
        c.gridx = 2; add(startBtn, c);
        c.gridx = 3; add(stopBtn, c);
        c.gridx = 4; add(status, c);

        startBtn.addActionListener(e -> onStart.accept(roomCode.getText()));
        stopBtn.addActionListener(e -> onStop.run());
    }

    public void setStatus(String text) {
        status.setText(text);
    }

    public String currentRoomCode() {
        return roomCode.getText();
    }
}
