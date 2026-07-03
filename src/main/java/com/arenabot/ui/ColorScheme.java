package com.arenabot.ui;

import java.awt.Color;

/**
 * Static palette constants for the Swing dashboard. Colours chosen to match
 * the spec verbs in {@code context/PROMPT.txt} §3: purple chests, yellow
 * energy, walls/empty distinguished by shade, and the bot in cyan so it
 * stands out against a dense map.
 */
public final class ColorScheme {

    private ColorScheme() {}

    public static final Color BG = new Color(0x12, 0x12, 0x18);
    public static final Color GRID_LINE = new Color(0x2d, 0x2d, 0x3d);
    public static final Color TILE_EMPTY = new Color(0x1c, 0x1c, 0x24);
    public static final Color TILE_WALL = new Color(0x55, 0x55, 0x66);
    public static final Color TILE_CHEST = new Color(0xa8, 0x55, 0xf7);
    public static final Color TILE_ENERGY = new Color(0xfa, 0xcc, 0x15);
    public static final Color TILE_VAULT = new Color(0xee, 0x33, 0x55);
    public static final Color TILE_BOT = new Color(0x22, 0xd3, 0xee);
    public static final Color TILE_OTHER_ROBOT = new Color(0x9b, 0x59, 0xb6);
    public static final Color FG_TEXT = new Color(0xe6, 0xe6, 0xff);
}
