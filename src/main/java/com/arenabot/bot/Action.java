package com.arenabot.bot;

/**
 * Actions the bot can submit. Limited to what we believe (per ARENA_API.md §6
 * hypothesis) the server accepts. Default {@link #UNLOCK} is the literal
 * {@code /arena/{room}/unlock} call with the right code payload.
 */
public enum Action {
    ANDARE, MOVER, AVANCAR, RECUAR, GIRAR_ESQ, GIRAR_DIR, SUBIR, DESCER, SALTAR,
    ATACAR, ABRIR, RECOLHER, USAR, STATUS, UNLOCK, FUZZ
}
