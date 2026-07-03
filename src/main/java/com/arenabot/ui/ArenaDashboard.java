package com.arenabot.ui;

import com.arenabot.bot.BotOrchestrator;
import com.arenabot.config.AppConfig;
import com.arenabot.config.ModelConfig;
import com.arenabot.model.GameState;
import com.arenabot.model.MeuEstado;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

/**
 * Painel de controlo do Arena Bot — visual portado do exemplo em
 * {@code OLD UI EXAMPLE/ui/PainelMapaCalor.java}:
 *
 * <ul>
 *   <li><b>Barra topo</b> — campo do código da sala (SEM valor por omissão:
 *       o operador digita a sala em cada arranque), botão Ligar/Parar,
 *       robot id e estado da ligação;</li>
 *   <li><b>Centro</b> — {@link HeatMapPanel}: fog-of-war + mapa de calor;</li>
 *   <li><b>Barra lateral</b> — uso de IA (Ollama) em tempo real: modelos por
 *       papel, nº de chamadas, última latência/tokens, cofres tentados,
 *       e o registo do ciclo Sense-Think-Act;</li>
 *   <li><b>Rodapé</b> — telemetria: HP, posição, modo, turno, tempo.</li>
 * </ul>
 *
 * <p>O agente corre nas suas próprias threads; esta classe só lê estado via
 * {@link BotOrchestrator} e {@link TelemetryBus} num Swing Timer de 500 ms.
 * Ligar/parar disparam em threads de fundo — nunca na EDT (o registo na
 * arena tem backoff bloqueante).</p>
 */
public final class ArenaDashboard {

    private static final Color VERDE    = new Color(0x1B7F3B);
    private static final Color VERMELHO = new Color(0xB00020);

    private final JFrame frame = new JFrame("Arena Bot — Agente Explorador (dual-brain LLM)");

    // Controlo (barra topo)
    private final JTextField campoSala = new JTextField(10); // sempre vazio no arranque
    private final JButton botaoLigar = new JButton("Ligar agente");
    private final JLabel lblRobot = new JLabel("Robot: —");
    private final JLabel estadoLigacao = new JLabel("Desligado");

    // Dashboard (rodapé)
    private final JLabel lblHp = etiqueta("HP: —");
    private final JLabel lblPos = etiqueta("Pos: —");
    private final JLabel lblModo = etiqueta("Modo: —");
    private final JLabel lblTurno = etiqueta("Turno: 0");
    private final JLabel lblTempo = etiqueta("Tempo: 0s");

    // Monitor IA (barra lateral)
    private final JLabel lblModelos = etiqueta("Modelos: —");
    private final JLabel lblLlm = etiqueta("Chamadas LLM: 0");
    private final JLabel lblLatencia = etiqueta("Latência última: 0 ms");
    private final JLabel lblTokens = etiqueta("Tokens in/out: 0 / 0");
    private final JLabel lblPapel = etiqueta("Último papel: —");
    private final JLabel lblCofres = etiqueta("Cofres tentados: 0");

    private final JTextArea log = new JTextArea();
    private final HeatMapPanel mapa = new HeatMapPanel();

    private final AppConfig config;
    private final BotOrchestrator orchestrator;
    private final TelemetryBus bus;
    private volatile long inicioMs;

    public ArenaDashboard(AppConfig config, BotOrchestrator orchestrator, TelemetryBus bus) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.bus = bus;
        construir();
    }

    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    /** Pré-preenche a sala (usado apenas pelo override -Room do launcher). */
    public void overrideRoomCode(String roomCode) {
        SwingUtilities.invokeLater(() -> campoSala.setText(roomCode));
    }

    // ======================================================================
    // Montagem
    // ======================================================================

    private void construir() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.setSize(1100, 720);
        frame.setLocationRelativeTo(null);

        frame.add(barraTopo(), BorderLayout.NORTH);
        frame.add(mapa, BorderLayout.CENTER);
        frame.add(painelLateral(), BorderLayout.EAST);
        frame.add(dashboard(), BorderLayout.SOUTH);

        botaoLigar.addActionListener(e -> alternarAgente());

        lblModelos.setText(formatarModelos());

        // Atualização periódica das métricas (independente das threads do bot).
        new Timer(500, e -> atualizar()).start();
    }

    private JPanel barraTopo() {
        JPanel p = new JPanel();
        p.add(new JLabel("Sala:"));
        campoSala.setToolTipText("Código da sala — obrigatório em cada arranque (sem valor por omissão).");
        p.add(campoSala);
        p.add(botaoLigar);
        p.add(new JLabel("  "));
        p.add(lblRobot);
        p.add(new JLabel("  Estado:"));
        estadoLigacao.setForeground(VERMELHO);
        p.add(estadoLigacao);
        return p;
    }

    private JPanel painelLateral() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setPreferredSize(new Dimension(380, 0));
        p.setBorder(BorderFactory.createTitledBorder("Uso de IA (Ollama) — tempo real"));

        JPanel ia = new JPanel();
        ia.setLayout(new BoxLayout(ia, BoxLayout.Y_AXIS));
        for (JLabel l : new JLabel[]{lblModelos, lblLlm, lblLatencia, lblTokens, lblPapel}) {
            ia.add(l);
        }
        ia.add(Box.createVerticalStrut(8));
        ia.add(new JLabel("--- Cofres (Tier 3) ---"));
        ia.add(lblCofres);
        ia.add(Box.createVerticalGlue());
        p.add(ia, BorderLayout.NORTH);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane sc = new JScrollPane(log);
        sc.setBorder(BorderFactory.createTitledBorder("Registo do ciclo Sense-Think-Act"));
        p.add(sc, BorderLayout.CENTER);
        return p;
    }

    private JPanel dashboard() {
        JPanel p = new JPanel(new GridLayout(1, 5, 8, 0));
        p.setBorder(BorderFactory.createTitledBorder("Telemetria"));
        for (JLabel l : new JLabel[]{lblHp, lblPos, lblModo, lblTurno, lblTempo}) p.add(l);
        return p;
    }

    private static JLabel etiqueta(String txt) {
        JLabel l = new JLabel(txt);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        return l;
    }

    private String formatarModelos() {
        StringBuilder sb = new StringBuilder("<html>Modelos:");
        for (String role : new String[]{"commander", "bot_move", "chest", "embedding"}) {
            ModelConfig mc = config.models().get(role);
            if (mc != null) {
                sb.append("<br>").append(role).append('=').append(mc.modelName())
                  .append(" (t=").append(mc.temperature()).append(')');
            }
        }
        return sb.append("</html>").toString();
    }

    // ======================================================================
    // Controlo do agente
    // ======================================================================

    private void alternarAgente() {
        if (orchestrator.isRunning()) {
            botaoLigar.setEnabled(false);
            estado("A parar...", VERMELHO);
            Thread t = new Thread(() -> {
                orchestrator.stop();
                SwingUtilities.invokeLater(() -> {
                    botaoLigar.setText("Ligar agente");
                    botaoLigar.setEnabled(true);
                    estado("Desligado", VERMELHO);
                    lblRobot.setText("Robot: —");
                });
            }, "bot-stop");
            t.setDaemon(true);
            t.start();
            return;
        }

        String sala = campoSala.getText().trim();
        if (sala.isEmpty()) {
            estado("Preencha o código da sala.", VERMELHO);
            campoSala.requestFocusInWindow();
            return;
        }

        mapa.limpar();
        inicioMs = System.currentTimeMillis();
        botaoLigar.setEnabled(false);
        estado("A ligar a " + sala + "...", VERMELHO);
        Thread t = new Thread(() -> {
            orchestrator.startWithRoomCode(sala);
            SwingUtilities.invokeLater(() -> {
                botaoLigar.setText("Parar agente");
                botaoLigar.setEnabled(true);
                estado("Ligado a " + sala, VERDE);
            });
        }, "bot-start");
        t.setDaemon(true);
        t.start();
    }

    private void estado(String texto, Color cor) {
        estadoLigacao.setText(texto);
        estadoLigacao.setForeground(cor);
    }

    // ======================================================================
    // Refresh periódico (EDT)
    // ======================================================================

    private void atualizar() {
        GameState s = orchestrator.lastState();
        if (s != null) {
            mapa.atualizar(orchestrator.stateSnapshotForUi(), s.meuEstado());
            MeuEstado eu = s.meuEstado();
            lblHp.setText("HP: " + eu.energia());
            lblPos.setText(String.format("Pos: (%d,%d)",
                    Math.round(eu.x()), Math.round(eu.y())));
            lblModo.setText("Modo: " + (s.hasGameOver() ? "terminado"
                    : s.hasGameStarted() ? "a decorrer" : "lobby"));
        }
        if (orchestrator.isRunning()) {
            lblTurno.setText("Turno: " + orchestrator.tickCount());
            lblTempo.setText("Tempo: " + ((System.currentTimeMillis() - inicioMs) / 1000) + "s");
            String id = orchestrator.robotId();
            if (id != null) lblRobot.setText("Robot: " + id);
        }

        // Monitor IA.
        TelemetryBus.Sample amostra = bus.latest();
        lblLlm.setText("Chamadas LLM: " + bus.totalCalls());
        lblLatencia.setText("Latência última: " + amostra.elapsedMs() + " ms");
        lblTokens.setText("Tokens in/out: " + amostra.tokensIn() + " / " + amostra.tokensOut());
        lblPapel.setText("Último papel: " + amostra.label());
        lblCofres.setText("Cofres tentados: " + orchestrator.vaultLedger().trackedVaultCount());

        // Log tail (substituição integral evita duplicados; auto-scroll).
        String linhas = String.join("\n", bus.recentLinesSnapshot());
        if (!linhas.equals(log.getText())) {
            log.setText(linhas);
            log.setCaretPosition(log.getDocument().getLength());
        }
    }
}
