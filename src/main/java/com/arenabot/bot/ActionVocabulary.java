package com.arenabot.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps each {@link Action} to a list of candidate tokens to submit to
 * {@code /arena/action}. Per ARENA_API.md §6 the verb set is still speculative
 * because {@code game_started=false} returns the same {@code bloqueado}
 * envelope for every input, so we ship a prioritised fuzz list at connect.
 */
public final class ActionVocabulary {

    private static final Map<Action, List<String>> TABLE = new LinkedHashMap<>();
    static {
        TABLE.put(Action.MOVER,    List.of("mover", "andare", "andar"));
        TABLE.put(Action.AVANCAR,   List.of("avancar", "avançar", "forward"));
        TABLE.put(Action.RECUAR,    List.of("recuar", "back"));
        TABLE.put(Action.GIRAR_ESQ, List.of("girar_esq", "girar esquerda", "left"));
        TABLE.put(Action.GIRAR_DIR, List.of("girar_dir", "girar direita", "right"));
        TABLE.put(Action.SUBIR,     List.of("subir", "up"));
        TABLE.put(Action.DESCER,    List.of("descer", "down"));
        TABLE.put(Action.SALTAR,    List.of("saltar", "jump"));
        TABLE.put(Action.ATACAR,    List.of("atacar", "attack"));
        TABLE.put(Action.ABRIR,     List.of("abrir", "open"));
        TABLE.put(Action.RECOLHER,  List.of("recolher", "pickup"));
        TABLE.put(Action.USAR,      List.of("usar", "use"));
        TABLE.put(Action.STATUS,    List.of("status"));
        TABLE.put(Action.FUZZ,      List.of());
    }

    public static List<String> tokens(Action a) { return TABLE.getOrDefault(a, List.of()); }

    /** Single first-pass token for each action, useful for the tick loop. */
    public static String primary(Action a) {
        List<String> t = tokens(a);
        return t.isEmpty() ? a.name().toLowerCase() : t.get(0);
    }

    /** Auto-fuzz set exposed in {@code ui.auto_fuzz_actions} of config.json. */
    public static List<String> autoFuzz(List<String> cfgList) {
        return cfgList == null ? List.of() : List.copyOf(cfgList);
    }
}
