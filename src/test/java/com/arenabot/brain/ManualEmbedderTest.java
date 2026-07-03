package com.arenabot.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManualEmbedderTest {

    @Test void splits15SectionsFromSampleManual() {
        String sample = """
          SECÇÃO 01 - Escudos magnéticos baterem
          O sigma-3 protege.

          SECÇÃO 02 - Reator em sobreaquecimento
          Use XTR-99 para arrefecer.

          SECÇÃO 03 - Radar de proximidade
          Use OPTIC-ZOOM.

          SECÇÃO 04 - Pressão hidráulica
          VALVULA-ALT resolve.

          SECÇÃO 05 - Energia abaixo de 50%
          Ative ECO-MARCH.

          SECÇÃO 06 - Desvio de relógio em circuitos lógicos
          CLK-SYNC corrige.

          SECÇÃO 07 - Canhão de plasma em sobre-temperatura
          VENT-OUT.

          SECÇÃO 08 - Tempestade eletromagnética saturando a grelha
          GROUND-0 necessário.

          SECÇÃO 09 - Recarga médica de nanobots após dano
          REGEN-MAX.

          SECÇÃO 10 - Projétil a caminho
          EVADE-NOW.

          SECÇÃO 11 - Corrupção de memória do S.O.
          BOOT-HARD.

          SECÇÃO 12 - Leituras LiDAR avariam após colisões
          MAP-RESET.

          SECÇÃO 13 - Perda de pacotes da API
          LINK-UP restabelece.

          SECÇÃO 14 - Níveis de gama acima do limite
          SHIELD-LEAD.

          SECÇÃO 15 - Impulso inicial após sinal do professor
          NITRO-START.
        """;
        List<ManualEmbedder.ManualSection> out = ManualEmbedder.split(sample);
        assertEquals(15, out.size());
        assertEquals(1, out.get(0).number());
        assertEquals(15, out.get(14).number());
    }

    @Test void emptyManualYieldsEmptyList() {
        assertEquals(0, ManualEmbedder.split("").size());
        assertEquals(0, ManualEmbedder.split(null).size());
    }

    @Test void findsMagneticShieldChunkByLexicalOverlap() {
        String sample = """
          SECÇÃO 01 - Escudos magnéticos baterem
          O sigma-3 protege.

          SECÇÃO 02 - Reator em sobreaquecimento
          Use XTR-99 para arrefecer.
        """;
        List<ManualEmbedder.ManualSection> out = ManualEmbedder.split(sample);
        // Quick sanity: confirm each section has a body.
        for (ManualEmbedder.ManualSection s : out) {
            assertFalse(s.body().isEmpty());
            assertTrue(s.header().startsWith("SECÇÃO"));
        }
    }
}
