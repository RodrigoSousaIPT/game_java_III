package com.arena.ui;

import com.arena.agente.AgenteExplorador;
import com.arena.agente.CloneBunshin;
import com.arena.agente.JutsuKageBunshin;
import com.arena.agente.MapaIntel;
import com.arena.agente.MonitorTelemetria;
import com.arena.agente.MotorConselheiro;
import com.arena.agente.ObservadorAgente;
import com.arena.cheats.Cheat;
import com.arena.cheats.CheatPrefs;
import com.arena.cheats.CheatServerHttp;
import com.arena.config.Config;
import com.arena.modelo.Cofre;
import com.arena.modelo.Coordenada;
import com.arena.modelo.EstadoRobot;
import com.arena.modelo.Percecao;
import com.arena.modelo.Recurso;
import com.arena.modelo.RespostaServidor;
import com.arena.modelo.RobotRival;
import com.arena.rag.MotorRag;
import com.arena.rede.OllamaClient;
import com.arena.ui.PainelCheats;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Painel gráfico (Java Swing / Graphics2D) — dashboard de telemetria, mapa de calor e
 * monitor de uso de IA em tempo real.
 *
 * <p>Classe visual exigida pelo enunciado. Implementa {@link ObservadorAgente}: o
 * {@link AgenteExplorador} corre numa thread de fundo e empurra eventos para cá, que são
 * desenhados na <em>Event Dispatch Thread</em> do Swing. Inclui:</p>
 * <ul>
 *   <li>Campo para inserir o <b>código da sala</b> + ligar/parar o agente;</li>
 *   <li><b>Mapa</b> tipo grelha com fog-of-war: muros, recursos, cofres, rivais, o robô e o
 *       mapa de calor (intensidade das visitas);</li>
 *   <li><b>Uso de IA</b> ao vivo: modelos, nº de embeddings, chamadas LLM, latência, último
 *       enigma/chunk/score/chave e resultado do desbloqueio.</li>
 * </ul>
 */
public final class PainelMapaCalor implements ObservadorAgente {

    private final JFrame frame = new JFrame("Arena 3D RAG — Agente Explorador");

    // Controlo
    private final JTextField campoSala = new JTextField(10);
    private final JTextField campoRobot = new JTextField(10);
    private final JButton botaoLigar = new JButton("Ligar agente");
    private final JButton botaoMonitor = new JButton("Monitorar (read-only)");
    private final JLabel estadoLigacao = new JLabel("Desligado");

    // Jutsu Kage Bunshin (multiplicação por clones das sombras)
    private final JButton botaoJutsuInvocar = new JButton("Kage Bunshin ×"
            + Config.KAGE_BUNSHIN_INCREMENTO_BOTAO);
    private final JButton botaoJutsuDispersar = new JButton("Dispersar (kai!)");
    private final JLabel lblJutsuEstado = etiqueta("Clones: 0");

    // Dashboard
    private final JLabel lblHp = etiqueta("HP: —");
    private final JLabel lblPos = etiqueta("Pos: —");
    private final JLabel lblModo = etiqueta("Modo: —");
    private final JLabel lblTurno = etiqueta("Turno: 0");
    private final JLabel lblTempo = etiqueta("Tempo: 0s");

    // Monitor IA
    private final JLabel lblModelos = etiqueta("Modelos: —");
    private final JLabel lblEmbeddings = etiqueta("Embeddings: 0");
    private final JLabel lblLlm = etiqueta("Chamadas LLM: 0");
    private final JLabel lblLatencia = etiqueta("Latência: 0 ms");
    private final JLabel lblEnigma = etiqueta("Enigma: —");
    private final JLabel lblChave = etiqueta("Chave: —");
    private final JLabel lblUnlock = etiqueta("Unlock: —");

    // Conselheiro (Gemma E4B)
    private final JButton botaoConselheiro = new JButton("Conselheiro: ON");
    private final JLabel lblConTotal = etiqueta("Consultas: 0");
    private final JLabel lblConOver = etiqueta("Overrides: 0");
    private final JLabel lblConAnalise = etiqueta("Análise: —");

    // Cheater Control Panel (CCP) — §8 do doc
    private final JButton botaoPainelCheats = new JButton("Painel Cheats");
    private PainelCheats painelCheatsRef; // referência ao painel depois de o agente ligar

    // --- Compact cheat controls (sidebar telemetry) ---
    // Os 10 toggles mais usados: 7 powers legítimos (§4 priority matrix) +
    // 3 cheats de baixo risco (C5, C7, C13). Cycle OFF → ARMED → ON em cada clique.
    private static final Cheat[] CHEATS_RAPIDOS = new Cheat[] {
            Cheat.P05_ECO_MARCH, Cheat.P09_REGEN_MAX, Cheat.P10_EVADE_NOW,
            Cheat.P12_MAP_RESET, Cheat.P13_LINK_UP,  Cheat.P08_GROUND_0,
            Cheat.P15_NITRO_START,
            Cheat.C5_RUN_AHEAD, Cheat.C7_PATH_WARP, Cheat.C13_CURVE_FITTED
    };
    /** Botões toggle por cheat (LinkedHashMap para preservar ordem dos CHEATS_RAPIDOS). */
    private final Map<Cheat, JToggleButton> togglesCheat = new LinkedHashMap<>();
    /** Labels dinâmicas com intensidade do cheat (lidas do CheatPrefs). */
    private final Map<Cheat, JLabel> labelsIntensidade = new LinkedHashMap<>();
    // Flags auxiliares — boolean toggles que afetam o comportamento do PainelCheats.
    private final JCheckBox chkStormAware = new JCheckBox("Storm-aware gate (§8.6)");
    private final JCheckBox chkTournamentLock = new JCheckBox("Tournament lock (C31233)");
    // Banner dinâmico do estado HIGH-risk.
    private final JLabel lblBannerHighRisk = new JLabel(" ");
    // Botões de ação.
    private final JButton btnConfessarHigh = new JButton("Confirmar HIGH-risk");
    private final JButton btnLockShipRapido = new JButton("Lock & Ship (§8.5)");
    /** Caminho do prefs file (referência para display). */
    private final JLabel lblPrefsPath = new JLabel(
            "<html><body style='color:#888888;font-size:10px'>prefs: "
                    + Config.CHEAT_PREFS_PATH + "</body></html>");

    private final JTextArea log = new JTextArea();
    private final MapaPanel mapa = new MapaPanel();

    // Estado partilhado (lido na EDT / Timer)
    private AgenteExplorador agente;
    private MonitorTelemetria monitor;
    private Thread threadAgente;
    private Thread threadMonitor;
    private OllamaClient ollama;
    private MotorConselheiro conselheiro;
    private long inicioMs;

    public PainelMapaCalor(String salaOmissao) {
        campoSala.setText(salaOmissao);
        campoRobot.setText("Explorador_" + (System.currentTimeMillis() % 100000));
        construir();
    }

    // ======================================================================
    // Montagem da UI
    // ======================================================================

    public void mostrar() {
        frame.setVisible(true);
    }

    private void construir() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.setSize(1100, 720);
        frame.setLocationRelativeTo(null);

        // ---- Compacts cheat controls (sidebar) ----
        // IMPORTANTE: inicializar ANTES do painelLateral() ser construído,
        // porque este último chama construirPainelCheatsRapido() que acede
        // a togglesCheat/labelsIntensidade/lblPrefsPath (NPE se não populados).
        setupControlesCheat();

        frame.add(barraTopo(), BorderLayout.NORTH);
        frame.add(mapa, BorderLayout.CENTER);
        frame.add(painelLateral(), BorderLayout.EAST);
        frame.add(dashboard(), BorderLayout.SOUTH);

        botaoLigar.addActionListener(e -> alternarAgente());
        botaoMonitor.addActionListener(e -> alternarMonitor());
        botaoConselheiro.addActionListener(e -> alternarConselheiro());
        botaoJutsuInvocar.addActionListener(e -> invocarKageBunshin());
        botaoJutsuDispersar.addActionListener(e -> dispersarKageBunshin());
        botaoPainelCheats.addActionListener(e -> abrirPainelCheats());
        botaoPainelCheats.setBackground(new Color(0xFFF3E0));
        botaoPainelCheats.setForeground(new Color(0xE65100));
        botaoPainelCheats.setToolTipText("Cheater Control Panel — operadores ONLY (localhost)");

        // Estado inicial: jutsu indisponível enquanto o agente não está ligado.
        botaoJutsuInvocar.setEnabled(false);
        botaoJutsuDispersar.setEnabled(false);
        botaoPainelCheats.setEnabled(false); // só ativo após agente ligado

        // Atualização periódica das métricas de IA (independente da thread do agente).
        new Timer(500, e -> atualizarMetricasIA()).start();
    }

    private JPanel barraTopo() {
        JPanel p = new JPanel();
        p.add(new JLabel("Sala:"));
        p.add(campoSala);
        p.add(new JLabel("Robot:"));
        p.add(campoRobot);
        p.add(botaoLigar);
        p.add(botaoMonitor);
        p.add(botaoConselheiro);
        p.add(new JLabel("  Jutsu:"));
        // Bot\u00f5es do Kage Bunshin \u2014 invocam/dispersam clones manualmente
        botaoJutsuInvocar.setBackground(new Color(0xEDE7F6)); // roxo Naruto
        botaoJutsuInvocar.setForeground(new Color(0x4527A0));
        botaoJutsuDispersar.setBackground(new Color(0xFFEBEE));
        botaoJutsuDispersar.setForeground(new Color(0xB71C1C));
        p.add(botaoJutsuInvocar);
        p.add(botaoJutsuDispersar);
        p.add(lblJutsuEstado);
        p.add(new JLabel("  Cheats:"));
        p.add(botaoPainelCheats);
        p.add(new JLabel("  Estado:"));
        estadoLigacao.setForeground(new Color(0xB00020));
        p.add(estadoLigacao);
        return p;
    }

    private JPanel painelLateral() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setPreferredSize(new Dimension(380, 0));
        p.setBorder(BorderFactory.createTitledBorder("Uso de IA (Ollama) + Cheats — tempo real"));

        JPanel ia = new JPanel();
        ia.setLayout(new BoxLayout(ia, BoxLayout.Y_AXIS));
        for (JLabel l : new JLabel[]{lblModelos, lblEmbeddings, lblLlm, lblLatencia,
                lblEnigma, lblChave, lblUnlock}) {
            ia.add(l);
        }
        // Separador visual para a secção do Conselheiro
        ia.add(Box.createVerticalStrut(8));
        ia.add(new JLabel("--- Conselheiro (Gemma E4B) ---"));
        for (JLabel l : new JLabel[]{lblConTotal, lblConOver, lblConAnalise}) {
            ia.add(l);
        }
        ia.add(Box.createVerticalStrut(8));
        ia.add(new JLabel("--- Cheats (rápido) ---"));
        ia.add(construirPainelCheatsRapido());
        ia.add(Box.createVerticalStrut(4));
        ia.add(lblBannerHighRisk);
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

    // ======================================================================
    // Controlo do agente
    // ======================================================================

    private void alternarAgente() {
        if (agente != null && agente.estaAtivo()) {
            agente.parar();
            botaoLigar.setText("Ligar agente");
            estado("A parar...");
            this.conselheiro = null;
            return;
        }
        String sala = campoSala.getText().trim();
        String robot = campoRobot.getText().trim();
        if (sala.isEmpty() || robot.isEmpty()) { estado("Preencha sala e robot."); return; }

        mapa.limpar();
        inicioMs = System.currentTimeMillis();
        agente = new AgenteExplorador(sala, robot, this);
        this.conselheiro = agente.getConselheiro();
        threadAgente = new Thread(() -> agente.correr(), "agente-loop");
        threadAgente.setDaemon(true);
        threadAgente.start();
        botaoLigar.setText("Parar agente");
        // Com agente ligado, os botões do Kage Bunshin ficam disponíveis
        // (mas respeitam Config.KAGE_BUNSHIN_ATIVO — gate geral do jutsu).
        botaoJutsuInvocar.setEnabled(Config.KAGE_BUNSHIN_ATIVO);
        botaoJutsuDispersar.setEnabled(false); // só ativa após a 1ª invocação
        botaoPainelCheats.setEnabled(true);
        // Liga a referência ao PainelCheats e arranca o servidor HTTP local.
        painelCheatsRef = agente.getPainelCheats();
        try {
            agente.getCheatServerHttp().iniciar();
            aoLog("[CCP] Servidor HTTP local em http://127.0.0.1:" + Config.CHEAT_HTTP_PORT + "/cheats");
        } catch (Exception ex) {
            aoLog("[CCP] Falha a iniciar servidor HTTP: " + ex.getMessage());
        }
        estado("Ligado a " + sala);
        estadoLigacao.setForeground(new Color(0x1B7F3B));
    }

    /**
     * Abre o Cheater Control Panel (CCP) — chama o PainelCheats interno do agente.
     * O painel é independente desta JFrame (frame separado no PainelCheats.mostrar()).
     */
    private void abrirPainelCheats() {
        if (painelCheatsRef == null) {
            aoLog("[CCP] Nenhum agente ligado — não há painel de cheats para abrir.");
            return;
        }
        painelCheatsRef.mostrar();
    }

    private void alternarMonitor() {
        if (monitor != null && monitor.estaAtivo()) {
            monitor.parar();
            botaoMonitor.setText("Monitorar (read-only)");
            estado("Monitor parado.");
            return;
        }
        String sala = campoSala.getText().trim();
        if (sala.isEmpty()) { estado("Preencha a sala."); return; }

        mapa.limpar();
        inicioMs = System.currentTimeMillis();
        monitor = new MonitorTelemetria(sala, "Monitor_" + (System.currentTimeMillis() % 100000), true, this);
        threadMonitor = new Thread(monitor::correr, "monitor-loop");
        threadMonitor.setDaemon(true);
        threadMonitor.start();
        botaoMonitor.setText("Parar monitor");
        estado("Monitor ligado a " + sala);
        estadoLigacao.setForeground(new Color(0x1B6FA8));
    }

    /** Liga/desliga o Conselheiro (Gemma E4B) em tempo real. */
    private void alternarConselheiro() {
        Config.CONSELHEIRO_ATIVO = !Config.CONSELHEIRO_ATIVO;
        if (Config.CONSELHEIRO_ATIVO) {
            botaoConselheiro.setText("Conselheiro: ON");
            botaoConselheiro.setForeground(new Color(0x1B6FA8));
            aoLog("[CONSELHEIRO] Ativado — Gemma E4B a supervisionar.");
        } else {
            botaoConselheiro.setText("Conselheiro: OFF");
            botaoConselheiro.setForeground(new Color(0xB00020));
            aoLog("[CONSELHEIRO] Desativado — MotorHeuristico a pensar sozinho.");
        }
    }

    /**
     * Invocação MANUAL do Kage Bunshin no Jutsu — chamada pelo botão na barra topo.
     * Cada clique adiciona {@code Config.KAGE_BUNSHIN_INCREMENTO_BOTAO} clones (default 10)
     * ao mesmo tempo que respeita o limite máximo total.
     */
    private void invocarKageBunshin() {
        if (agente == null || !agente.estaAtivo()) {
            estado("Ligue o agente antes de invocar o jutsu.");
            return;
        }
        JutsuKageBunshin jutsu = agente.getJutsu();
        int jaInvocados = jutsu.getNumeroClones();
        int restantes = Config.KAGE_BUNSHIN_MAX_CLONES - jaInvocados;
        int n = Math.min(Config.KAGE_BUNSHIN_INCREMENTO_BOTAO, restantes);
        if (n <= 0) {
            estado("Limite de clones atingido (" + Config.KAGE_BUNSHIN_MAX_CLONES
                    + ") — dispersa antes de invocar mais.");
            return;
        }
        if (!Config.KAGE_BUNSHIN_ATIVO) {
            estado("Kage Bunshin está desativado em Config.KAGE_BUNSHIN_ATIVO.");
            return;
        }
        // A sala+robot são os mesmos do TextField; os novos clones recebem
        // IDs derivados (<robot>_KageBunshin_<i>) pelo próprio JutsuKageBunshin.
        jutsu.invocar(campoSala.getText().trim(), campoRobot.getText().trim(), n, this);
        botaoJutsuDispersar.setEnabled(true);
        aoLog("[UI] 影分身の術! Invocação manual de +" + n + " clones (total=" + (jaInvocados + n) + ").");
    }

    /** Dispersa TODOS os clones invocados (kai! 解除). */
    private void dispersarKageBunshin() {
        if (agente == null) {
            estado("Sem agente ativo.");
            return;
        }
        JutsuKageBunshin jutsu = agente.getJutsu();
        int anteriores = jutsu.getNumeroClones();
        jutsu.dispersar();
        // Já estamos na EDT (ActionListener). Sem invokeLater extra.
        mapa.atualizarClones(null);
        aoLog("[UI] 解除 (Kai!) — " + anteriores + " clones dispersaram.");
    }

    /** Gate de Config: se desativado, botão "invocar" fica indisponível. */
    private boolean jutsuDisponivel() {
        return Config.KAGE_BUNSHIN_ATIVO && agente != null && agente.estaAtivo();
    }

    private void estado(String s) {
        SwingUtilities.invokeLater(() -> estadoLigacao.setText(s));
    }

    private void atualizarMetricasIA() {
        if (inicioMs > 0) {
            lblTempo.setText("Tempo: " + ((System.currentTimeMillis() - inicioMs) / 1000) + "s / 600s");
        }
        if (ollama != null) {
            lblModelos.setText("<html>Modelos:<br>emb=" + ollama.getModeloEmbeddings()
                    + "<br>llm=" + ollama.getModeloLlm() + "</html>");
            lblEmbeddings.setText("Embeddings: " + ollama.getEmbeddingsGerados());
            lblLlm.setText("Chamadas LLM: " + ollama.getChamadasLlm());
            lblLatencia.setText("Latência última: " + ollama.getUltimaLatenciaMs() + " ms");
        }
        if (conselheiro != null) {
            lblConTotal.setText("Consultas: " + conselheiro.getTotalConsultas());
            lblConOver.setText("Overrides: " + conselheiro.getTotalOverrides());
            String a = conselheiro.getUltimaAnalise();
            if (a != null && a.length() > 40) a = a.substring(0, 37) + "...";
            lblConAnalise.setText("Análise: " + (a != null && !a.isEmpty() ? a : "—"));
        }
        // Atualiza o mapa com as posições dos clones invocados.
        if (agente != null && agente.estaAtivo()) {
            JutsuKageBunshin j = agente.getJutsu();
            int n = j != null ? j.getNumeroClones() : 0;
            lblJutsuEstado.setText("Clones: " + n + "/" + Config.KAGE_BUNSHIN_MAX_CLONES);
            if (j != null && j.foiInvocado()) mapa.atualizarClones(j.getMapaIntel());
            if (n == 0) botaoJutsuDispersar.setEnabled(false);
        }
        // Refresca visual dos toggles de cheat (cor + label) — se o agente parou,
        // também desativa os toggles para não permitir alteração.
        atualizarControlesCheat();
    }

    /**
     * Refresca o visual dos toggles de cheat (cor + label) lendo o estado atual
     * dos {@link CheatPrefs} do agente. Garante que mudanças feitas pelo painel
     * cheio ou pelo HTTP local apareçam imediatamente aqui.
     */
    private void atualizarControlesCheat() {
        CheatPrefs prefs = (agente != null) ? agente.getCheatPrefs() : null;
        if (prefs == null) {
            // Sem agente — desativar tudo para não haver enganos.
            for (JToggleButton t : togglesCheat.values()) {
                t.setEnabled(false);
                t.setText(t.getName() + "  [no agent]");
                t.setBackground(new Color(0xEEEEEE));
            }
            chkStormAware.setEnabled(false);
            chkTournamentLock.setEnabled(false);
            btnConfessarHigh.setEnabled(false);
            btnLockShipRapido.setEnabled(false);
            lblBannerHighRisk.setText(" ");
            return;
        }
        // Atualiza toggles.
        for (Map.Entry<Cheat, JToggleButton> e : togglesCheat.entrySet()) {
            Cheat c = e.getKey();
            JToggleButton t = e.getValue();
            CheatPrefs.Mode m = prefs.getModo(c);
            recolorarToggle(t, c, m);
            JLabel lbl = labelsIntensidade.get(c);
            if (lbl != null) {
                int intensidade = prefs.getIntensidade(c);
                String intTxt = c.temIntensidade() ? (" | int=" + intensidade) : "";
                lbl.setText("<html><body style='color:#666666;font-size:10px'>"
                        + (c.getRisco() == Cheat.Risk.HIGH ? "HIGH-risk" : c.getRisco() == Cheat.Risk.MEDIUM ? "MED-risk" : "LOW-risk")
                        + intTxt + "</body></html>");
            }
        }
        // Flags auxiliares.
        chkStormAware.setSelected(prefs.getFlag("storm_aware_gate"));
        chkTournamentLock.setSelected(prefs.getFlag("tournament_lock"));
        // Banner HIGH-risk.
        boolean algumHighArmed = false;
        for (Cheat c : Cheat.values()) {
            if (c.getRisco() == Cheat.Risk.HIGH && prefs.getModo(c) != CheatPrefs.Mode.OFF) {
                algumHighArmed = true; break;
            }
        }
        if (algumHighArmed) {
            boolean ack = prefs.getFlag("tos_ack_highrisk");
            lblBannerHighRisk.setText(ack ? "⚠ HIGH-risk armed — [ACK]" : "⚠ HIGH-risk armado! Confirmar (§8.6)");
            lblBannerHighRisk.setForeground(ack ? new Color(0xFFA000) : new Color(0xB00020));
        } else {
            lblBannerHighRisk.setText(" ");
        }
        chkStormAware.setEnabled(true);
        chkTournamentLock.setEnabled(true);
        btnConfessarHigh.setEnabled(true);
        btnLockShipRapido.setEnabled(true);
    }

    /**
     * Colore o toggle de acordo com o modo (OFF=cinza, ARMED=laranja, ON=verde)
     * e atualiza o texto "{id} … {mode}".
     */
    private void recolorarToggle(JToggleButton t, Cheat c, CheatPrefs.Mode m) {
        String label = (c.isAutoPower() ? "⚡ " : "🎯 ") + c.getId()
                + "  " + (m == CheatPrefs.Mode.OFF ? "OFF"
                        : m == CheatPrefs.Mode.ARMED ? "ARMED" : "ON");
        t.setText(label);
        t.setSelected(m != CheatPrefs.Mode.OFF);
        switch (m) {
            case ON:
                t.setBackground(new Color(0x4CAF50)); // verde
                t.setForeground(Color.WHITE);
                break;
            case ARMED:
                t.setBackground(c.getRisco() == Cheat.Risk.HIGH
                        ? new Color(0xFFCDD2) // rosa-pálido para HIGH-risk
                        : new Color(0xFFE082)); // amarelo-laranja para ARMED normal
                t.setForeground(Color.BLACK);
                break;
            default:
                t.setBackground(new Color(0xEEEEEE)); // cinza
                t.setForeground(Color.DARK_GRAY);
                break;
        }
    }

    // ======================================================================
    // Callbacks do ObservadorAgente (chegam na thread do agente → marshal p/ EDT)
    // ======================================================================

    @Override
    public void aoIniciar(OllamaClient ollama, String sala, String robot) {
        this.ollama = ollama;
        aoLog("=== Sessão iniciada — sala " + sala + " robot " + robot + " ===");
    }

    @Override
    public void aoLog(String linha) {
        SwingUtilities.invokeLater(() -> {
            log.append(linha + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    @Override
    public void aoTelemetria(Percecao p, int turno) {
        SwingUtilities.invokeLater(() -> {
            EstadoRobot eu = p.getEstado();
            if (eu != null) {
                lblHp.setText("HP: " + eu.getEnergia() + "/" + Config.HP_MAXIMO);
                lblPos.setText("Pos: " + eu.getPosicao());
            }
            lblModo.setText("Modo: " + (p.getTipoJogo().isEmpty() ? "?" : p.getTipoJogo())
                    + (p.isJogoIniciado() ? " [a decorrer]" : " [lobby]"));
            lblTurno.setText("Turno: " + turno);
            mapa.atualizar(p);
        });
    }

    @Override
    public void aoEventoRag(MotorRag.ResultadoRag r, RespostaServidor unlock) {
            SwingUtilities.invokeLater(() -> {
                if (r != null) {
                    lblEnigma.setText("<html>Enigma: " + abreviar(r.getEnigma(), 38)
                            + "<br>score=" + String.format("%.3f", r.getScore()) + "</html>");
                    lblChave.setText("Chave: " + r.getChave());
                }
                boolean ok = unlock != null && unlock.sucesso();
                lblUnlock.setText("Unlock: " + (ok ? "SUCESSO (+100 HP)" : "falha"));
                lblUnlock.setForeground(ok ? new Color(0x1B7F3B) : new Color(0xB00020));
            });
    }

    @Override
    public void aoConselho(MotorConselheiro.Conselho c) {
            SwingUtilities.invokeLater(() -> {
                if (c != null && c.analise != null) {
                    String a = c.analise.length() > 40 ? c.analise.substring(0, 37) + "..." : c.analise;
                    lblConAnalise.setText("Análise: " + a);
                }
        });
    }

    @Override
    public void aoFim(String motivo) {
        aoLog("=== FIM: " + motivo + " ===");
        SwingUtilities.invokeLater(() -> {
            botaoLigar.setText("Ligar agente");
            botaoMonitor.setText("Monitorar (read-only)");
            estadoLigacao.setText("Desligado (" + motivo + ")");
            estadoLigacao.setForeground(new Color(0xB00020));
            this.conselheiro = null;
            botaoPainelCheats.setEnabled(false);
            painelCheatsRef = null;
        });
    }

    // ======================================================================
    // Cheats: wiring, ciclo, flags e ações rápidas
    // ======================================================================

    /**
     * Constrói e regista todos os controlos da secção "<i>Cheats (rápido)</i>"
     * da barra lateral. Cada toggle é um botão cíclico OFF → ARMED → ON. Os
     * listeners escrevem no {@link CheatPrefs} do agente imediatamente.
     */
    private void setupControlesCheat() {
        togglesCheat.clear();
        labelsIntensidade.clear();
        for (Cheat c : CHEATS_RAPIDOS) {
            JToggleButton t = new JToggleButton(c.getId() + "  " + c.getNome());
            t.setName(c.getId() + " " + c.getNome());
            t.setFocusPainted(false);
            t.setMargin(new java.awt.Insets(2, 6, 2, 6));
            t.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            t.addActionListener(e -> cycleCheat(c));
            togglesCheat.put(c, t);
            JLabel lbl = new JLabel(" ");
            lbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            labelsIntensidade.put(c, lbl);
        }

        // Listeners das flags.
        chkStormAware.addActionListener(e -> aplicarFlag("storm_aware_gate", chkStormAware.isSelected()));
        chkTournamentLock.addActionListener(e -> aplicarFlag("tournament_lock", chkTournamentLock.isSelected()));

        // Listener dos botões de ação.
        btnConfessarHigh.addActionListener(e -> confessarHighRisk());
        btnLockShipRapido.addActionListener(e -> lockAndShipRapido());

        // lblPrefsPath já está inicializado como campo final — nada a fazer aqui.
    }

    /** Constrói o JPanel dos controlos de cheat (a inserir no painelLateral). */
    private JPanel construirPainelCheatsRapido() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // Linha 1: Toggle buttons (10 cheat toggles em grelha vertical compacta).
        JPanel linhas = new JPanel();
        linhas.setLayout(new BoxLayout(linhas, BoxLayout.Y_AXIS));
        for (Cheat c : CHEATS_RAPIDOS) {
            JPanel row = new JPanel(new BorderLayout(2, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            JToggleButton t = togglesCheat.get(c);
            row.add(t, BorderLayout.CENTER);
            JLabel lbl = labelsIntensidade.get(c);
            lbl.setPreferredSize(new Dimension(110, 28));
            lbl.setHorizontalAlignment(JLabel.RIGHT);
            row.add(lbl, BorderLayout.EAST);
            linhas.add(row);
        }
        p.add(linhas);
        p.add(Box.createVerticalStrut(6));

        // Linha 2: Flags (checkboxes booleanos).
        JPanel flagsPanel = new JPanel();
        flagsPanel.setLayout(new BoxLayout(flagsPanel, BoxLayout.Y_AXIS));
        flagsPanel.setBorder(BorderFactory.createTitledBorder("Flags (boolean)"));
        for (JCheckBox cb : new JCheckBox[]{chkStormAware, chkTournamentLock}) {
            cb.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            cb.setMargin(new java.awt.Insets(2, 6, 2, 6));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            flagsPanel.add(cb);
        }
        p.add(flagsPanel);
        p.add(Box.createVerticalStrut(6));

        // Linha 3: Botões de ação.
        JPanel accoes = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btnConfessarHigh.setBackground(new Color(0xFFEBEE));
        btnConfessarHigh.setMargin(new java.awt.Insets(2, 6, 2, 6));
        btnConfessarHigh.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        btnLockShipRapido.setBackground(new Color(0xE0F7FA));
        btnLockShipRapido.setMargin(new java.awt.Insets(2, 6, 2, 6));
        btnLockShipRapido.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        accoes.add(btnConfessarHigh);
        accoes.add(btnLockShipRapido);
        accoes.add(botaoPainelCheats);
        p.add(accoes);
        p.add(Box.createVerticalStrut(2));
        p.add(lblPrefsPath);
        lblBannerHighRisk.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        // Render inicial (será atualizado pelo Timer).
        recolorarToggleInicial();
        return p;
    }

    /** Recolore os toggles com o modo OFF (serão atualizados quando agente ligar). */
    private void recolorarToggleInicial() {
        for (Map.Entry<Cheat, JToggleButton> e : togglesCheat.entrySet()) {
            recolorarToggle(e.getValue(), e.getKey(), CheatPrefs.Mode.OFF);
            e.getValue().setEnabled(false);
        }
    }

    /**
     * Cicla o modo de um cheat (OFF → ARMED → ON → OFF) e persiste. HIGH-risk
     * requer acknowledgement (banner vermelho se não confessado).
     */
    private void cycleCheat(Cheat c) {
        if (agente == null) return;
        CheatPrefs prefs = agente.getCheatPrefs();
        prefs.recarregar();
        CheatPrefs.Mode atual = prefs.getModo(c);
        CheatPrefs.Mode proximo;
        switch (atual) {
            case OFF:   proximo = CheatPrefs.Mode.ARMED; break;
            case ARMED: proximo = CheatPrefs.Mode.ON;    break;
            case ON:    proximo = CheatPrefs.Mode.OFF;   break;
            default:    proximo = CheatPrefs.Mode.OFF;
        }
        // Bloqueio HIGH-risk sem ack (§8.6).
        if (c.getRisco() == Cheat.Risk.HIGH && proximo != CheatPrefs.Mode.OFF
                && !prefs.getFlag("tos_ack_highrisk")) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Este cheat é HIGH-risk (ToS risco elevado).\n" +
                    "Antes de o armar é necessário clicar em «Confirmar HIGH-risk»\n" +
                    "e digitar «CONFIRMO» (§8.6 do doc).",
                    "Ack HIGH-risk necessário", javax.swing.JOptionPane.WARNING_MESSAGE);
            // Reverter toggle visualmente.
            recolorarToggle(togglesCheat.get(c), c, atual);
            return;
        }
        prefs.setModo(c, proximo);
        prefs.atomicEscrever();
        recolorarToggle(togglesCheat.get(c), c, proximo);
        // Limpa storms-gate subscribed pelo controller (§8.6).
        if (agente.getCheatController() != null) agente.getCheatController().atualizarEstadoFlag();
        // Confirma banner se virar HIGH-risk on.
        atualizarControlesCheat();
    }

    /** Aplica uma flag booleana aos prefs do agente e persiste. */
    private void aplicarFlag(String nome, boolean valor) {
        if (agente == null) return;
        CheatPrefs prefs = agente.getCheatPrefs();
        prefs.setFlag(nome, valor);
        prefs.atomicEscrever();
        aoLog("[FLAG] " + nome + " = " + valor);
    }

    /** Pede confirmação textual CONFIRMO (§8.6 do doc) e regista tos_ack_highrisk. */
    private void confessarHighRisk() {
        if (agente == null) return;
        CheatPrefs prefs = agente.getCheatPrefs();
        javax.swing.JOptionPane.showMessageDialog(frame,
                "<html>Confirma que leu a §3 do doc <i>Powers &amp; Cheats</i> e entende que<br>" +
                "HIGH-risk cheats podem violar o ToS do professor?<br><br>" +
                "Para activar ack digite «CONFIRMO» na janela seguinte.</html>",
                "Ack HIGH-risk (passo 1/2)", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        String input = javax.swing.JOptionPane.showInputDialog(frame,
                "Digite CONFIRMO abaixo (literal, sem aspas):",
                "Ack HIGH-risk (passo 2/2)", javax.swing.JOptionPane.PLAIN_MESSAGE);
        if ("CONFIRMO".equalsIgnoreCase(input == null ? "" : input.trim())) {
            prefs.setFlag("tos_ack_highrisk", true);
            prefs.atomicEscrever();
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Acknowledgement registado: " + java.time.Instant.now()
                            + "\nOs cheats HIGH-risk podem agora ser armados/ON.",
                    "OK", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            aoLog("[§8.6] HIGH-risk acknowledgement registado pelo operador.");
        } else {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Texto incorreto — acknowledgement não registado.",
                    "Cancelado", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        atualizarControlesCheat();
    }

    /** Lock & Ship rápido (§8.5) — desliga tudo, esconde a barra de cheats da sidebar. */
    private void lockAndShipRapido() {
        if (agente == null) return;
        int ok = javax.swing.JOptionPane.showConfirmDialog(frame,
                "Lock & Ship (§8.5):\n" +
                "  • Todos os cheats OFF\n" +
                "  • Painel Cheats escondido\n" +
                "  • Servidor HTTP parado\n" +
                "  • Linhas de log com menção a cheats são limpas\n\n" +
                "Continuar?",
                "Confirmar Lock & Ship",
                javax.swing.JOptionPane.YES_NO_OPTION);
        if (ok != javax.swing.JOptionPane.YES_OPTION) return;
        CheatPrefs prefs = agente.getCheatPrefs();
        for (Cheat c : Cheat.values()) prefs.setModo(c, CheatPrefs.Mode.OFF);
        prefs.atomicEscrever();
        if (painelCheatsRef != null) painelCheatsRef.parar();
        if (agente.getCheatServerHttp() != null) agente.getCheatServerHttp().parar();
        // Limpa linhas do log que mencionam cheats (best-effort).
        try {
            StringBuilder novoLog = new StringBuilder();
            String conteudo = log.getText();
            for (String linha : conteudo.split("\n")) {
                if (linha == null) continue;
                String t = linha.toUpperCase();
                if (t.contains("[CHEATS]") || t.contains("[C13]") || t.contains("[C17]")
                        || t.contains("[C18]") || t.contains("[P08]")
                        || t.contains("[P12]") || t.contains("[§8.6]")) continue;
                novoLog.append(linha).append('\n');
            }
            log.setText(novoLog.toString());
            log.setCaretPosition(log.getDocument().getLength());
        } catch (Exception ex) { /* best-effort */ }
        aoLog("[§8.5 Lock & Ship] Cheats OFF, painel fechado, servidor parado.");
        atualizarControlesCheat();
    }

    private static String abreviar(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ======================================================================
    // Mapa (Graphics2D) — fog-of-war acumulado + mapa de calor
    // ======================================================================

    private static final class MapaPanel extends JPanel {

        private final Set<Coordenada> muros = new HashSet<>();
        private final Set<Coordenada> cofres = new HashSet<>();
        private final Map<Coordenada, Integer> visitas = new HashMap<>();
        private final List<Coordenada> recursos = new CopyOnWriteArrayList<>();
        private final List<RobotRival> rivais = new CopyOnWriteArrayList<>();
        private volatile Coordenada eu;
        // Snapshot concorrente das posições dos clones (cloneId → pos). Atualizada
        // periodicamente via atualizarClones(MapaIntel) — lida só no paintComponent.
        private volatile Map<String, Coordenada> posicoesClones =
                java.util.Collections.emptyMap();

        MapaPanel() {
            setBackground(new Color(0x101418));
        }

        void limpar() {
            muros.clear(); cofres.clear(); visitas.clear();
            recursos.clear(); rivais.clear(); eu = null;
            posicoesClones = java.util.Collections.emptyMap();
            repaint();
        }

        /** Atualiza (snapshot) o conjunto de posições dos clones para pintura. */
        void atualizarClones(MapaIntel intel) {
            if (intel == null || intel.posicoesClones.isEmpty()) {
                posicoesClones = java.util.Collections.emptyMap();
            } else {
                posicoesClones = new HashMap<>(intel.posicoesClones);
            }
            repaint();
        }

        void atualizar(Percecao p) {
            for (Coordenada c : p.getObjetosFixos()) muros.add(c);
            for (Cofre c : p.getCofres()) cofres.add(c.getPosicao());
            recursos.clear();
            for (Recurso r : p.getRecursos()) recursos.add(r.getPosicao());
            rivais.clear();
            rivais.addAll(p.getRivais());
            EstadoRobot e = p.getEstado();
            if (e != null && e.getPosicao() != null) {
                eu = e.getPosicao();
                visitas.merge(eu, 1, Integer::sum);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // limites conhecidos do mundo
            Integer minX = null, minY = null, maxX = null, maxY = null;
            for (Coordenada c : todas()) {
                if (minX == null || c.getX() < minX) minX = c.getX();
                if (maxX == null || c.getX() > maxX) maxX = c.getX();
                if (minY == null || c.getY() < minY) minY = c.getY();
                if (maxY == null || c.getY() > maxY) maxY = c.getY();
            }
            if (minX == null) {
                g.setColor(Color.GRAY);
                g.drawString("Sem telemetria ainda — liga o agente.", 20, 30);
                return;
            }
            // margem de 1 célula
            minX--; minY--; maxX++; maxY++;
            int cols = maxX - minX + 1, rows = maxY - minY + 1;
            int cell = Math.max(6, Math.min((getWidth() - 20) / cols, (getHeight() - 20) / rows));
            int ox = (getWidth() - cols * cell) / 2;
            int oy = (getHeight() - rows * cell) / 2;

            int maxVisitas = visitas.values().stream().mapToInt(Integer::intValue).max().orElse(1);

            // grelha + mapa de calor
            for (int gx = 0; gx < cols; gx++) {
                for (int gy = 0; gy < rows; gy++) {
                    int px = ox + gx * cell, py = oy + gy * cell;
                    Coordenada c = new Coordenada(minX + gx, minY + gy);
                    Integer v = visitas.get(c);
                    if (v != null && v > 0) {
                        float t = Math.min(1f, v / (float) maxVisitas);
                        g.setColor(new Color(0.15f + 0.75f * t, 0.25f, 0.30f - 0.10f * t));
                    } else {
                        g.setColor(new Color(0x1A2026));
                    }
                    g.fillRect(px, py, cell - 1, cell - 1);
                }
            }

            // muros
            g.setColor(new Color(0x5A6470));
            for (Coordenada c : muros) preencher(g, c, minX, minY, ox, oy, cell);
            // recursos (verde)
            g.setColor(new Color(0x2ECC71));
            for (Coordenada c : recursos) ponto(g, c, minX, minY, ox, oy, cell, 0.55);
            // cofres (dourado)
            g.setColor(new Color(0xF1C40F));
            for (Coordenada c : cofres) ponto(g, c, minX, minY, ox, oy, cell, 0.7);
            // rivais (vermelho)
            g.setColor(new Color(0xE74C3C));
            for (RobotRival r : rivais) if (r.getPosicao() != null) ponto(g, r.getPosicao(), minX, minY, ox, oy, cell, 0.65);
            // eu (azul)
            if (eu != null) {
                g.setColor(new Color(0x3498DB));
                ponto(g, eu, minX, minY, ox, oy, cell, 0.8);
            }

            // Equipa Kage Bunshin — anéis roxos com label "#i". Cada clone é desenhado
            // por cima do mestre mas por baixo dos cofres/rivais para não ocluir nada
            // crítico; usa cores HSB determinísticas pelo cloneId para serem estáveis.
            if (!posicoesClones.isEmpty()) {
                Font fClone = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, cell));
                g.setFont(fClone);
                int idx = 0;
                for (Map.Entry<String, Coordenada> e : posicoesClones.entrySet()) {
                    Coordenada c = e.getValue();
                    if (c == null) continue;
                    idx++;
                    // Tom roxo Naruto (gradient por índice, estável por cloneId via hash)
                    float hue = 0.78f + ((idx * 7 + Math.abs(e.getKey().hashCode())) % 9) * 0.012f;
                    g.setColor(Color.getHSBColor(hue, 0.65f, 0.95f));
                    // Anel oco: contorno grosso para destacar da grelha
                    int d = (int) (cell * 0.55);
                    int px = ox + (c.getX() - minX) * cell + (cell - d) / 2;
                    int py = oy + (c.getY() - minY) * cell + (cell - d) / 2;
                    g.fillOval(px, py, d, d);
                    g.setColor(Color.WHITE);
                    g.drawOval(px, py, d - 1, d - 1);
                    String label = "K" + idx;
                    int tw = g.getFontMetrics().stringWidth(label);
                    g.setColor(Color.WHITE);
                    g.drawString(label, px + (d - tw) / 2, py + d - 2);
                }
            }

            // legenda
            g.setColor(Color.LIGHT_GRAY);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("azul=eu  roxo=Kage Bunshin  dourado=cofre  verde=recurso  vermelho=rival  cinza=muro  (calor=visitas)",
                    12, getHeight() - 8);
        }

        private Set<Coordenada> todas() {
            Set<Coordenada> s = new HashSet<>(muros);
            s.addAll(cofres);
            s.addAll(visitas.keySet());
            s.addAll(recursos);
            for (RobotRival r : rivais) if (r.getPosicao() != null) s.add(r.getPosicao());
            if (eu != null) s.add(eu);
            return s;
        }

        private void preencher(Graphics2D g, Coordenada c, int minX, int minY, int ox, int oy, int cell) {
            int px = ox + (c.getX() - minX) * cell, py = oy + (c.getY() - minY) * cell;
            g.fillRect(px, py, cell - 1, cell - 1);
        }

        private void ponto(Graphics2D g, Coordenada c, int minX, int minY, int ox, int oy, int cell, double frac) {
            int d = (int) (cell * frac);
            int px = ox + (c.getX() - minX) * cell + (cell - d) / 2;
            int py = oy + (c.getY() - minY) * cell + (cell - d) / 2;
            g.fillOval(px, py, d, d);
        }
    }
}
