package com.arenabot.ui;

import com.arenabot.memory.MemorySnapshot;
import com.arenabot.model.CofreNoMundo;
import com.arenabot.model.MeuEstado;
import com.arenabot.model.ObjetoFixo;
import com.arenabot.model.OutroRobot;
import com.arenabot.model.RecursoNoMundo;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Swing canvas that paints the cached world from {@link MemorySnapshot}.
 * Each tile is {@code gridPixelSize} × {@code gridPixelSize} px so the panel
 * is sized to a multiple of that.
 *
 * <p>Drawing is intentionally trivial: classify each tile by what occupies
 * it most-recently and paint a single colour. There is no projection matrix
 * — exactly matches the 2-D maze in ARENA_API.md §2.
 */
public final class GridPanel extends JPanel {

    private final int pixelSize;
    private final int xMax;
    private final int yMax;
    private volatile MemorySnapshot snap;
    private volatile MeuEstado me;

    public GridPanel(int pixelSize, int xMax, int yMax) {
        this.pixelSize = pixelSize;
        this.xMax = xMax;
        this.yMax = yMax;
        setBackground(ColorScheme.BG);
        setPreferredSize(new Dimension(xMax * pixelSize, yMax * pixelSize));
    }

    public void update(MemorySnapshot s, MeuEstado m) {
        this.snap = s;
        this.me = m;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.setColor(ColorScheme.BG);
        g.fillRect(0, 0, getWidth(), getHeight());

        if (snap == null) return;

        // Walls first — recolour every tile known to contain a parede_* / cubo.glb.
        g.setColor(ColorScheme.TILE_WALL);
        for (ObjetoFixo w : snap.walls()) {
            if (!w.isWall()) continue; // decor objects are not obstacles
            fillTile(g, (int) Math.round(w.x()), (int) Math.round(w.y()));
        }

        // Chests (purple), energy (yellow), vaults (red) — drawn over walls
        // because the server treats chests as floor cells, not walls.
        for (RecursoNoMundo r : snap.resources()) {
            Color c = r.isChest() ? ColorScheme.TILE_CHEST
                    : r.isEnergy() ? ColorScheme.TILE_ENERGY
                    : ColorScheme.TILE_EMPTY;
            g.setColor(c);
            fillTile(g, (int) Math.round(r.x()), (int) Math.round(r.y()));
        }
        for (CofreNoMundo v : snap.vaults()) {
            g.setColor(ColorScheme.TILE_VAULT);
            fillTile(g, (int) Math.round(v.x()), (int) Math.round(v.y()));
        }
        // Other robots.
        g.setColor(ColorScheme.TILE_OTHER_ROBOT);
        for (OutroRobot o : snap.robots().values()) {
            fillTile(g, (int) Math.round(o.x()), (int) Math.round(o.y()));
        }
        // Self last so we always see the bot on top.
        if (me != null) {
            g.setColor(ColorScheme.TILE_BOT);
            int bx = (int) Math.round(me.x());
            int by = (int) Math.round(me.y());
            fillTile(g, bx, by);
            // Outline so it stays visible on any background.
            g.setColor(ColorScheme.FG_TEXT);
            g.drawRect(bx * pixelSize, by * pixelSize, pixelSize - 1, pixelSize - 1);
        }

        // Grid lines on top.
        g.setColor(ColorScheme.GRID_LINE);
        for (int x = 0; x <= xMax; x++) g.drawLine(x * pixelSize, 0, x * pixelSize, getHeight());
        for (int y = 0; y <= yMax; y++) g.drawLine(0, y * pixelSize, getWidth(), y * pixelSize);
    }

    private void fillTile(Graphics g, int tx, int ty) {
        if (tx < 0 || ty < 0 || tx >= xMax || ty >= yMax) return;
        g.fillRect(tx * pixelSize + 1, ty * pixelSize + 1, pixelSize - 2, pixelSize - 2);
    }
}
