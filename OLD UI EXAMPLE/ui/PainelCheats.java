package com.arena.ui;

import com.arena.agente.AgenteExplorador;
import com.arena.cheats.Cheat;
import com.arena.cheats.CheatController;
import com.arena.cheats.CheatPrefs;
import com.arena.cheats.CheatServerHttp;
import com.arena.cheats.VaultFuzzer;
import com.arena.config.Config;
import com.arena.rag.MotorRag;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Painel de Cheats — Cheater Control Panel (CCP) do doc §8.
 *
 * <p>Implementa a topologia completa da §8.1:</p>
 * <ul>
 *   <li><b>Top status bar</b> — room id, game_started, storm indicator, última ação.</li>
 *   <li><b>Tab Cheats</b> (collapsible) — uma linha por cheat com:
 *     <ul>
 *       <li><b>Toggle OFF/ARMED/ON</b> — tri-state via JComboBox.</li>
 *       <li><b>Intensity slider</b> (0–100) para cheats com {@link Cheat.IntensityParam}.</li>
 *       <li><b>Risk badge</b> — chip color (verde/amarelo/vermelho) conforme ToS.</li>
 *       <li><b>Descrição</b> completa em tooltip.</li>
 *     </ul>
 *   </li>
 *   <li><b>Tab Vault Fuzzer</b> — JTextArea para o operador digitar o pool;
 *       JList mostra o histórico de usados/falhados.</li>
 *   <li><b>Tab Audit Ribbon</b> — read-only preview dos próximos
 *       {@code rag_chunk}/{@code llm_raw} a enviar (per §8.1 + §8.6 read-only).</li>
 *   <li><b>Banner warning</b> quando qualquer HIGH-risk cheat é armado (§8.6).</li>
 *   <li><b>Bot&atilde;o "Lock and Ship"</b> (§8.5) — fecha painel, pede ack,
 *       e limpa linhas de log com menção a cheats (cleanup para screenshots).</li>
 * </ul>
 *
 * <p>O painel fala com o agente por referências in-memory ({@link CheatPrefs} +
 * {@link VaultFuzzer}); o servidor {@link CheatServerHttp} em 127.0.0.1:7860 é
 * apenas o canal side-car para tooling externo.</p>
 */
public final class PainelCheats {

    private final CheatPrefs prefs;
    private final VaultFuzzer fuzzer;
    private final CheatController controller;
    private final CheatServerHttp httpServer;

    // Componentes principais (Swing)
    private JFrame frame;
    private JPanel barraStatus;
    private JLabel lblSala, lblStorm, lblUltimaAcao;
    private JLabel lblBannerRisco; // banner para HIGH-risk armed (§8.6)
    private final Object[] combos = new Object[Cheat.values().length];
    private final Object[] sliders = new Object[Cheat.values().length];
    private final Object[] badges = new Object[Cheat.values().length];
    private final List<JLabel> linhasAudit = new CopyOnWriteArrayList<>();
    private JTextArea txtPoolVault;
    private JProgressBar barraProgressoAcoes;
    /** Coluna do Audit Ribbon — re-renderizada pelo refresher para mostrar novas linhas. */
    private JPanel auditCol;
    private int auditRenderizadas = -1;

    private Timer refresher;
    private volatile boolean locked = false;

    public PainelCheats(CheatPrefs prefs, VaultFuzzer fuzzer,
                        CheatController controller, CheatServerHttp httpServer) {
        this.prefs = prefs;
        this.fuzzer = fuzzer;
        this.controller = controller;
        this.httpServer = httpServer;
    }

    // ======================================================================
    // Montagem & visibilidade
    // ======================================================================

    public void mostrar() {
        SwingUtilities.invokeLater(() -> {
            construir();
            frame.setVisible(true);
        });
    }

    /** Callback invocado pelo AgenteExplorador a cada evento relevante. */
    public void onTick(String sala, boolean storm, String ultimaAcao) {
        SwingUtilities.invokeLater(() -> {
            if (lblSala != null) lblSala.setText("Sala: " + (sala == null ? "—" : sala));
            if (lblStorm != null) {
                lblStorm.setText(storm ? "IN-STORM (P08 GROUND-0)" : "Calmo");
                lblStorm.setForeground(storm ? new Color(0xB00020) : new Color(0x1B7F3B));
            }
            if (lblUltimaAcao != null && ultimaAcao != null) lblUltimaAcao.setText("Última: " + ultimaAcao);
        });
    }

    /** Atualiza a ribbon do audit com o resultado da próxima unlock (read-only). */
    public void onProximoAudit(MotorRag.ResultadoRag r) {
        if (r == null) return;
        SwingUtilities.invokeLater(() -> {
            // Limita histórico a 5 linhas ($(audit.window) na §8.1).
            JLabel novo = new JLabel(String.format(
                    "<html><body style=\"width:520px\">[%s] rag_chunk=%s<br>score=%.3f key=%s</body></html>",
                    java.time.LocalTime.now().withNano(0),
                    abreviar(r.getChunk(), 60),
                    r.getScore(),
                    r.getChave()));
            novo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            linhasAudit.add(novo);
            while (linhasAudit.size() > 5) linhasAudit.remove(0);
        });
    }

    public boolean isLocked() { return locked; }

    public void parar() {
        SwingUtilities.invokeLater(() -> {
            if (refresher != null) refresher.stop();
            if (frame != null) frame.dispose();
            if (httpServer != null) httpServer.parar();
        });
    }

    // ======================================================================
    // Construção
    // ======================================================================

    @SuppressWarnings("unchecked")
    private void construir() {
        frame = new JFrame("Cheater Control Panel — Arena 3D RAG");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout(8, 8));
        frame.setSize(940, 720);
        frame.setLocationRelativeTo(null);

        frame.add(barraStatus(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Cheats", construirTabelaCheats());
        tabs.addTab("Vault Fuzzer", construirVaultFuzzer());
        tabs.addTab("Audit Ribbon", construirAuditRibbon());
        tabs.addTab("Alertas", construirAlertas());
        frame.add(tabs, BorderLayout.CENTER);

        frame.add(rodape(), BorderLayout.SOUTH);

        // Refresher 1 Hz mantém storm indicator / barra de progresso em dia.
        refresher = new Timer(1000, e -> atualizarTick());
        refresher.start();
        atualizarBanners();
    }

    private JPanel barraStatus() {
        barraStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        barraStatus.setBorder(BorderFactory.createTitledBorder("Estado do Painel"));
        lblSala = new JLabel("Sala: —");
        lblStorm = new JLabel("Calmo");
        lblStorm.setForeground(new Color(0x1B7F3B));
        lblUltimaAcao = new JLabel("Última: —");
        lblBannerRisco = new JLabel("");
        lblBannerRisco.setForeground(new Color(0xB00020));
        barraStatus.add(lblSala);
        barraStatus.add(new JLabel("|"));
        barraStatus.add(lblStorm);
        barraStatus.add(new JLabel("|"));
        barraStatus.add(lblUltimaAcao);
        barraStatus.add(Box.createHorizontalStrut(20));
        barraStatus.add(lblBannerRisco);
        return barraStatus;
    }

    // ----- Tab: Cheats ------------------------------------------------------

    private JPanel construirTabelaCheats() {
        JPanel raiz = new JPanel(new BorderLayout(4, 4));
        JPanel grelha = new JPanel(new GridLayout(0, 1, 2, 2));
        JScrollPane sc = new JScrollPane(grelha);
        sc.getVerticalScrollBar().setUnitIncrement(16);

        int i = 0;
        for (Cheat c : Cheat.values()) {
            grelha.add(linhaCheat(c, i));
            i++;
        }

        JPanel accoes = new JPanel();
        JButton btnDesligarTodos = new JButton("Desligar todos (OFF)");
        btnDesligarTodos.addActionListener(e -> desligarTodos());
        JButton btnArmarLow = new JButton("Armar LOW-risk (modo seguro)");
        btnArmarLow.addActionListener(e -> armarLowRisk());
        JButton btnSalvarPrefs = new JButton("Gravar prefs no disco");
        btnSalvarPrefs.addActionListener(e -> { prefs.atomicEscrever(); info("Prefs gravadas em " + prefs.getFicheiro()); });
        accoes.add(btnDesligarTodos);
        accoes.add(btnArmarLow);
        accoes.add(btnSalvarPrefs);

        raiz.add(sc, BorderLayout.CENTER);
        raiz.add(accoes, BorderLayout.SOUTH);
        return raiz;
    }

    private JPanel linhaCheat(Cheat c, int idx) {
        JPanel linha = new JPanel(new GridBagLayout());
        linha.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.fill = GridBagConstraints.HORIZONTAL;

        // 1) ID + nome
        JLabel idNome = new JLabel("<html><b>" + c.getId() + "</b> — " + c.getNome() + "</html>");
        idNome.setToolTipText("<html><body style=\"width:400px\">"
                + c.getDescricao()
                + "<br><br>ToS: <b>" + c.getRisco().name() + "</b> — "
                + (c.isAutoPower() ? "Poder legítimo (protocolo do manual)."
                : (c.getRisco() == Cheat.Risk.HIGH ? "Risco ToS ALTO — bannering obrigatório (§8.6)."
                : (c.getRisco() == Cheat.Risk.MEDIUM
                ? "Risco ToS MÉDIO — discutir com o professor antes."
                : "Risco ToS BAIXO.")))
                + "</body></html>");
        idNome.setPreferredSize(new Dimension(220, 28));
        g.gridx = 0; g.gridy = 0; g.weightx = 0;
        linha.add(idNome, g);

        // 2) Toggle OFF/ARMED/ON
        JComboBox<CheatPrefs.Mode> combo = new JComboBox<>(CheatPrefs.Mode.values());
        combo.setSelectedItem(prefs.getModo(c));
        combo.setToolTipText("OFF: nunca dispara — ARMED: só com gatilho — ON: sempre");
        combo.addActionListener(e -> {
            try {
                prefs.setModo(c, (CheatPrefs.Mode) combo.getSelectedItem());
                prefs.atomicEscrever();
                atualizarBanners();
            } catch (Exception ex) { erro("Modo inválido: " + ex.getMessage()); }
        });
        g.gridx = 1; g.gridy = 0; g.weightx = 0;
        linha.add(combo, g);
        combos[idx] = combo;

        // 3) Slider de intensidade (só para cheats com IntensityParam != NONE)
        JComponent sliderOuLabel;
        if (c.temIntensidade()) {
            JSlider sl = new JSlider(0, 100, prefs.getIntensidade(c));
            sl.setPreferredSize(new Dimension(180, 24));
            sl.setMajorTickSpacing(25);
            sl.setPaintTicks(true);
            sl.addChangeListener((ChangeEvent e) -> {
                if (!sl.getValueIsAdjusting()) {
                    prefs.setIntensidade(c, sl.getValue());
                    prefs.atomicEscrever();
                }
            });
            sliderOuLabel = sl;
        } else {
            JLabel lbl = new JLabel("(sem slider)");
            lbl.setForeground(Color.GRAY);
            sliderOuLabel = lbl;
        }
        g.gridx = 2; g.gridy = 0; g.weightx = 1;
        linha.add(sliderOuLabel, g);
        sliders[idx] = sliderOuLabel;

        // 4) Risk badge
        JLabel badge = badge(c.getRisco());
        g.gridx = 3; g.gridy = 0; g.weightx = 0;
        linha.add(badge, g);
        badges[idx] = badge;

        // Cor de fundo da linha se ARMED/ON HIGH
        if (c.getRisco() == Cheat.Risk.HIGH
                && prefs.getModo(c) != CheatPrefs.Mode.OFF) {
            linha.setBackground(new Color(0xFFEBEE));
            linha.setOpaque(true);
        } else if (c.isAutoPower() && prefs.getModo(c) != CheatPrefs.Mode.OFF) {
            linha.setBackground(new Color(0xE3F2FD));
            linha.setOpaque(true);
        }
        return linha;
    }

    private static JLabel badge(Cheat.Risk r) {
        JLabel b = new JLabel("  " + r.name() + "  ");
        b.setOpaque(true);
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        b.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        switch (r) {
            case HIGH:
                b.setBackground(new Color(0xB00020)); b.setForeground(Color.WHITE); break;
            case MEDIUM:
                b.setBackground(new Color(0xFFC107)); b.setForeground(Color.BLACK); break;
            default:
                b.setBackground(new Color(0x1B7F3B)); b.setForeground(Color.WHITE);
        }
        return b;
    }

    // ----- Tab: Vault Fuzzer -----------------------------------------------

    private JPanel construirVaultFuzzer() {
        JPanel raiz = new JPanel(new BorderLayout(4, 4));
        JLabel titulo = new JLabel("<html>Pool de códigos candidatos para <code>POST /unlock</code> — "
                + "editável livremente; aplica-se se C13 (Curve-fitted) estiver ON/ARMED.</html>");
        titulo.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        raiz.add(titulo, BorderLayout.NORTH);

        txtPoolVault = new JTextArea(20, 60);
        txtPoolVault.setToolTipText("Um código por linha. Linhas em branco são ignoradas.");
        txtPoolVault.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        for (String c : fuzzer.getPool()) txtPoolVault.append(c + "\n");
        raiz.add(new JScrollPane(txtPoolVault), BorderLayout.CENTER);

        JPanel accoes = new JPanel();
        JButton btnAplicar = new JButton("Aplicar ao pool");
        btnAplicar.addActionListener(e -> {
            java.util.List<String> linhas = new ArrayList<>();
            for (String l : txtPoolVault.getText().split("\n")) {
                String t = l.trim();
                if (!t.isEmpty()) linhas.add(t);
            }
            fuzzer.definirPool(linhas);
            prefs.atomicEscrever();
            info("Pool atualizado: " + linhas.size() + " códigos.");
        });
        JButton btnSemear = new JButton("Semear (lista clássica)");
        btnSemear.addActionListener(e -> {
            fuzzer.semearComFuzzListClassico(controller.getSala(), java.util.Collections.emptyList());
            // Re-render
            txtPoolVault.setText("");
            for (String c : fuzzer.getPool()) txtPoolVault.append(c + "\n");
            prefs.atomicEscrever();
        });
        JButton btnLimparUsados = new JButton("Limpar tentativas");
        btnLimparUsados.addActionListener(e -> { fuzzer.limparTentativas(); info("Tentativas limpas."); });
        accoes.add(btnAplicar);
        accoes.add(btnSemear);
        accoes.add(btnLimparUsados);
        raiz.add(accoes, BorderLayout.SOUTH);

        return raiz;
    }

    // ----- Tab: Audit Ribbon -----------------------------------------------

    private JPanel construirAuditRibbon() {
        JPanel raiz = new JPanel(new BorderLayout(4, 4));
        JLabel aviso = new JLabel("<html>Aviso: esta ribbon é <b>READ-ONLY</b> por §8.6 — "
                + "operador NÃO pode colar um rag_chunk/llm_raw hand-crafted (C12 *Smoke-and-mirrors*).</html>");
        aviso.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        raiz.add(aviso, BorderLayout.NORTH);

        auditCol = new JPanel();
        auditCol.setLayout(new BoxLayout(auditCol, BoxLayout.Y_AXIS));
        renderizarAudit();
        JScrollPane sc = new JScrollPane(auditCol);
        raiz.add(sc, BorderLayout.CENTER);
        return raiz;
    }

    /** Re-popula a coluna do Audit Ribbon a partir de {@link #linhasAudit}. */
    private void renderizarAudit() {
        if (auditCol == null) return;
        auditCol.removeAll();
        for (JLabel l : linhasAudit) auditCol.add(l);
        auditCol.add(Box.createVerticalGlue());
        auditCol.revalidate();
        auditCol.repaint();
        auditRenderizadas = linhasAudit.size();
    }

    // ----- Tab: Alertas (ler do controller quando disponível) -------------

    private JPanel construirAlertas() {
        JPanel raiz = new JPanel(new BorderLayout(4, 4));
        JTextArea txt = new JTextArea(20, 60);
        txt.setEditable(false);
        txt.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        txt.append("# Alertas do Painel de Cheats\n\n");
        txt.append("## Estado atual dos cheats:\n\n");
        for (Cheat c : Cheat.values()) {
            txt.append(String.format("%s  modo=%-7s risco=%-7s%s\n",
                    c.getId(),
                    prefs.getModo(c).name(),
                    c.getRisco().name(),
                    c.isAutoPower() ? "  [POWER]" : ""));
        }
        txt.append("\n## Notas dos poderes activos:\n");
        txt.append("- P05 ECO-MARCH dispara quando HP < 50%.\n");
        txt.append("- P09 REGEN-MAX quando HP cai 5%+ entre ticks.\n");
        txt.append("- P10 EVADE-NOW quando inimigo ≤ 2 tiles.\n");
        txt.append("- P12 MAP-RESET após 3 colisões seguidas (desligado por C16 se ligado).\n");
        txt.append("- P08 GROUND-0 uma vez por storm (não stackable).\n");
        txt.append("- P15 NITRO-START na transição lobby→jogo (HP > 70%).\n");
        txt.append("\n## Notas de ToS (§3):\n");
        txt.append("- HIGH-risk (C1,C2,C3,C4,C9,C10,C11,C12,C20) — bannering + ack.\n");
        txt.append("- Tournament mode (sala C31233) — cheats HIGH-risk auto-locked.\n");
        JScrollPane sc = new JScrollPane(txt);
        raiz.add(sc, BorderLayout.CENTER);
        return raiz;
    }

    // ----- Rodapé com Lock & Ship + banner ---------------------------------

    private JPanel rodape() {
        JPanel rod = new JPanel(new BorderLayout(4, 4));
        rod.setBorder(BorderFactory.createTitledBorder("Painel — ações globais"));

        barraProgressoAcoes = new JProgressBar(0, 100);
        barraProgressoAcoes.setStringPainted(true);
        barraProgressoAcoes.setValue(0);
        barraProgressoAcoes.setString("Idle");
        rod.add(barraProgressoAcoes, BorderLayout.CENTER);

        JPanel btns = new JPanel();
        JButton btnLockShip = new JButton("Lock & Ship");
        btnLockShip.setBackground(new Color(0xE0F7FA));
        btnLockShip.addActionListener(e -> lockAndShip());
        JButton btnConfess = new JButton("Confirmar HIGH-risk");
        btnConfess.setBackground(new Color(0xFFEBEE));
        btnConfess.addActionListener(e -> PreferencesOverrides.confessHighRisk(prefs));

        btns.add(btnConfess);
        btns.add(btnLockShip);
        rod.add(btns, BorderLayout.EAST);

        JLabel serv = new JLabel("HTTP local: " +
                (httpServer != null && httpServer.estaIniciado()
                        ? ("http://127.0.0.1:" + httpServer.getPorta() + "/cheats")
                        : "(não iniciado)"));
        serv.setFont(serv.getFont().deriveFont(Font.PLAIN, 11f));
        rod.add(serv, BorderLayout.NORTH);
        return rod;
    }

    private void atualizarTick() {
        // Atualiza barra de progresso com # total de ações disparadas no controller.
        if (controller == null) return;
        int total = 0;
        for (Cheat c : Cheat.values()) total += controller.getContador(c.getAcao());
        barraProgressoAcoes.setValue(Math.min(100, total));
        barraProgressoAcoes.setString("Ações disparadas (powers+cheats): " + total);
        // Audit Ribbon: re-renderiza só quando há linhas novas (evita flicker).
        if (auditRenderizadas != linhasAudit.size()) renderizarAudit();
    }

    private void atualizarBanners() {
        // Banner vermelho se qualquer HIGH-risk estiver ARMED/ON sem ack.
        boolean algumHighArmed = false;
        for (Cheat c : Cheat.values()) {
            if (c.getRisco() == Cheat.Risk.HIGH
                    && prefs.getModo(c) != CheatPrefs.Mode.OFF) {
                algumHighArmed = true; break;
            }
        }
        if (algumHighArmed) {
            String ack = prefs.getFlag("tos_ack_highrisk") ? "[ACK]" : "[!] confirmar HIGH-risk";
            lblBannerRisco.setText("⚠ HIGH-risk armed " + ack + " §8.6");
            lblBannerRisco.setForeground(prefs.getFlag("tos_ack_highrisk")
                    ? new Color(0xFFA000)
                    : new Color(0xB00020));
            lblBannerRisco.setVisible(true);
        } else {
            lblBannerRisco.setVisible(false);
        }
    }

    private void desligarTodos() {
        int ok = JOptionPane.showConfirmDialog(frame,
                "Desligar TODOS os cheats e powers? (continua navegação básica.)",
                "Confirmar", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (Cheat c : Cheat.values()) {
            prefs.setModo(c, CheatPrefs.Mode.OFF);
            JComboBox<?> combo = (JComboBox<?>) combos[c.ordinal()];
            if (combo != null) combo.setSelectedItem(CheatPrefs.Mode.OFF);
        }
        prefs.atomicEscrever();
        info("Todos os cheats / powers OFF.");
        atualizarBanners();
    }

    private void armarLowRisk() {
        for (Cheat c : Cheat.values()) {
            if (c.getRisco() == Cheat.Risk.LOW) {
                prefs.setModo(c, CheatPrefs.Mode.ARMED);
                JComboBox<?> combo = (JComboBox<?>) combos[c.ordinal()];
                if (combo != null) combo.setSelectedItem(CheatPrefs.Mode.ARMED);
            }
        }
        prefs.atomicEscrever();
        info("LOW-risk armado (modo seguro §8.4).");
        atualizarBanners();
    }

    private void lockAndShip() {
        int ok = JOptionPane.showConfirmDialog(frame,
                "Lock & Ship: esconde este painel, desliga todos os cheats, e limpa linhas\n"
                        + "de log que mencionam cheats. Para reverter tem de reabrir o painel.\n\n"
                        + "Continuar?",
                "Confirmar (§8.5)", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        // Desliga tudo.
        for (Cheat c : Cheat.values()) prefs.setModo(c, CheatPrefs.Mode.OFF);
        prefs.atomicEscrever();
        // Esconde painel.
        if (frame != null) frame.setVisible(false);
        locked = true;
        // Limpa o servidor HTTP (deixa de poder receber ordens externas).
        if (httpServer != null) httpServer.parar();
        info("[Lock & Ship] Painel escondido, cheats OFF, servidor HTTP parado.");
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(frame, msg, "Painel Cheats", JOptionPane.INFORMATION_MESSAGE);
    }

    private void erro(String msg) {
        JOptionPane.showMessageDialog(frame, msg, "Erro", JOptionPane.ERROR_MESSAGE);
    }

    private static String abreviar(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ======================================================================
    // Helpers estáticos para overrides sensíveis (acknowledgements)
    // ======================================================================

    /** Acções que alteram flags sensíveis (ack. HIGH-risk) — isoladas por SRP. */
    public static final class PreferencesOverrides {
        private PreferencesOverrides() { }

        /** Pede confirmação digital (§8.3) e regista timestamp na flag {@code tos_ack_highrisk}. */
        public static void confessHighRisk(CheatPrefs prefs) {
            if (prefs.getFlag("tos_ack_highrisk")) {
                JOptionPane.showMessageDialog(null,
                        "Acknowledgement JÁ registado. Para revogar, desligue primeiro todos os HIGH-risk.",
                        "Ack HIGH-risk", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String input = JOptionPane.showInputDialog(null,
                    "<html>Bannering acknowledgement (§8.6):<br>"
                            + "Confirma que leu a §3 e entende que HIGH-risk cheats<br>"
                            + "podem violar o ToS do professor. Digite <b>CONFIRMO</b> abaixo:</html>",
                    "Ack HIGH-risk", JOptionPane.PLAIN_MESSAGE);
            if ("CONFIRMO".equalsIgnoreCase(input == null ? "" : input.trim())) {
                prefs.setFlag("tos_ack_highrisk", true);
                prefs.atomicEscrever();
                JOptionPane.showMessageDialog(null,
                        "Acknowledgement registado: " + java.time.Instant.now(),
                        "OK", JOptionPane.INFORMATION_MESSAGE);
            } else if (input != null) {
                JOptionPane.showMessageDialog(null,
                        "Texto incorreto — acknowledgement não registado.",
                        "Cancelado", JOptionPane.WARNING_MESSAGE);
            }
        }
    }
}
