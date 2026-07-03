package com.arenabot.ui;

import com.arenabot.memory.MemorySnapshot;
import com.arenabot.model.CofreNoMundo;
import com.arenabot.model.MeuEstado;
import com.arenabot.model.ObjetoFixo;
import com.arenabot.model.OutroRobot;
import com.arenabot.model.RecursoNoMundo;
import com.arenabot.pathfinding.GridPos;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mapa Graphics2D com fog-of-war acumulado e mapa de calor de visitas —
 * visual portado de {@code OLD UI EXAMPLE/ui/PainelMapaCalor.MapaPanel}.
 *
 * <p>Paleta (legenda no rodapé do painel):
 * azul=eu · dourado=cofre · verde=recurso · vermelho=rival · cinza=muro ·
 * intensidade de fundo = calor (nº de visitas).</p>
 *
 * <p>Os muros são acumulados entre updates (fog-of-war: nada do que já foi
 * descoberto desaparece); recursos/rivais/cofres refletem o último snapshot.
 * Chamar apenas na EDT — o {@link ArenaDashboard} atualiza via Swing Timer.</p>
 */
public final class HeatMapPanel extends JPanel {

    private static final Color BG          = new Color(0x101418);
    private static final Color CELL_EMPTY  = new Color(0x1A2026);
    private static final Color COL_WALL    = new Color(0x5A6470);
    private static final Color COL_RESOURCE= new Color(0x2ECC71);
    private static final Color COL_VAULT   = new Color(0xF1C40F);
    private static final Color COL_RIVAL   = new Color(0xE74C3C);
    private static final Color COL_ME      = new Color(0x3498DB);

    private final Set<GridPos> muros = new HashSet<>();
    private final Set<GridPos> cofres = new HashSet<>();
    private final Map<GridPos, Integer> visitas = new HashMap<>();
    private final List<GridPos> recursos = new CopyOnWriteArrayList<>();
    private final List<GridPos> rivais = new CopyOnWriteArrayList<>();
    private volatile GridPos eu;

    public HeatMapPanel() {
        setBackground(BG);
    }

    /** Reset total — chamado quando o operador liga o agente a uma sala nova. */
    public void limpar() {
        muros.clear();
        cofres.clear();
        visitas.clear();
        recursos.clear();
        rivais.clear();
        eu = null;
        repaint();
    }

    /** Ingesta um snapshot novo (EDT). Muros e visitas acumulam; o resto substitui. */
    public void atualizar(MemorySnapshot snap, MeuEstado me) {
        if (snap == null) { repaint(); return; }
        for (ObjetoFixo w : snap.walls()) {
            if (w.isWall()) muros.add(GridPos.of(w.x(), w.y()));
        }
        cofres.clear();
        for (CofreNoMundo c : snap.vaults()) cofres.add(GridPos.of(c.x(), c.y()));
        recursos.clear();
        for (RecursoNoMundo r : snap.resources()) recursos.add(GridPos.of(r.x(), r.y()));
        rivais.clear();
        for (OutroRobot o : snap.robots().values()) rivais.add(GridPos.of(o.x(), o.y()));
        if (me != null) {
            GridPos pos = GridPos.of(me.x(), me.y());
            if (!pos.equals(eu)) visitas.merge(pos, 1, Integer::sum);
            eu = pos;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Limites conhecidos do mundo (auto-fit ao que já foi descoberto).
        Integer minX = null, minY = null, maxX = null, maxY = null;
        for (GridPos c : todas()) {
            if (minX == null || c.x() < minX) minX = c.x();
            if (maxX == null || c.x() > maxX) maxX = c.x();
            if (minY == null || c.y() < minY) minY = c.y();
            if (maxY == null || c.y() > maxY) maxY = c.y();
        }
        if (minX == null) {
            g.setColor(Color.GRAY);
            g.drawString("Sem telemetria ainda — liga o agente.", 20, 30);
            return;
        }
        // Margem de 1 célula.
        minX--; minY--; maxX++; maxY++;
        int cols = maxX - minX + 1, rows = maxY - minY + 1;
        int cell = Math.max(6, Math.min((getWidth() - 20) / cols, (getHeight() - 20) / rows));
        int ox = (getWidth() - cols * cell) / 2;
        int oy = (getHeight() - rows * cell) / 2;

        int maxVisitas = visitas.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        // Grelha + mapa de calor.
        for (int gx = 0; gx < cols; gx++) {
            for (int gy = 0; gy < rows; gy++) {
                int px = ox + gx * cell, py = oy + gy * cell;
                Integer v = visitas.get(new GridPos(minX + gx, minY + gy));
                if (v != null && v > 0) {
                    float t = Math.min(1f, v / (float) maxVisitas);
                    g.setColor(new Color(0.15f + 0.75f * t, 0.25f, 0.30f - 0.10f * t));
                } else {
                    g.setColor(CELL_EMPTY);
                }
                g.fillRect(px, py, cell - 1, cell - 1);
            }
        }

        // Muros.
        g.setColor(COL_WALL);
        for (GridPos c : muros) preencher(g, c, minX, minY, ox, oy, cell);
        // Recursos (verde).
        g.setColor(COL_RESOURCE);
        for (GridPos c : recursos) ponto(g, c, minX, minY, ox, oy, cell, 0.55);
        // Cofres (dourado).
        g.setColor(COL_VAULT);
        for (GridPos c : cofres) ponto(g, c, minX, minY, ox, oy, cell, 0.7);
        // Rivais (vermelho).
        g.setColor(COL_RIVAL);
        for (GridPos c : rivais) ponto(g, c, minX, minY, ox, oy, cell, 0.65);
        // Eu (azul), por cima de tudo.
        GridPos self = eu;
        if (self != null) {
            g.setColor(COL_ME);
            ponto(g, self, minX, minY, ox, oy, cell, 0.8);
        }

        // Legenda.
        g.setColor(Color.LIGHT_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("azul=eu  dourado=cofre  verde=recurso  vermelho=rival  cinza=muro  (calor=visitas)",
                12, getHeight() - 8);
    }

    private Set<GridPos> todas() {
        Set<GridPos> s = new HashSet<>(muros);
        s.addAll(cofres);
        s.addAll(visitas.keySet());
        s.addAll(recursos);
        s.addAll(rivais);
        GridPos self = eu;
        if (self != null) s.add(self);
        return s;
    }

    private void preencher(Graphics2D g, GridPos c, int minX, int minY, int ox, int oy, int cell) {
        int px = ox + (c.x() - minX) * cell, py = oy + (c.y() - minY) * cell;
        g.fillRect(px, py, cell - 1, cell - 1);
    }

    private void ponto(Graphics2D g, GridPos c, int minX, int minY, int ox, int oy, int cell, double frac) {
        int d = (int) (cell * frac);
        int px = ox + (c.x() - minX) * cell + (cell - d) / 2;
        int py = oy + (c.y() - minY) * cell + (cell - d) / 2;
        g.fillOval(px, py, d, d);
    }
}
