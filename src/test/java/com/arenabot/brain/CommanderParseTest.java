package com.arenabot.brain;

import com.arenabot.model.MissionObjective;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommanderParseTest {

    @Test void parsesGoalWithCoordinates() {
        MissionObjective o = CommanderStrategist.parseObjective("GOTO_CHEST 4 7");
        assertNotNull(o);
        assertEquals(MissionObjective.Goal.GOTO_CHEST, o.goal());
        assertEquals(4, (int) o.targetX());
        assertEquals(7, (int) o.targetY());
    }

    @Test void parsesLowercaseAndSkipsChatter() {
        MissionObjective o = CommanderStrategist.parseObjective(
                "Sure! Here is my plan:\nunlock_vault 2 9\nthanks");
        assertNotNull(o);
        assertEquals(MissionObjective.Goal.UNLOCK_VAULT, o.goal());
        assertEquals(2, (int) o.targetX());
    }

    @Test void exploreAndHoldNeedNoTarget() {
        MissionObjective explore = CommanderStrategist.parseObjective("EXPLORE 0 0");
        assertNotNull(explore);
        assertEquals(MissionObjective.Goal.EXPLORE, explore.goal());
        MissionObjective hold = CommanderStrategist.parseObjective("HOLD");
        assertNotNull(hold);
        assertEquals(MissionObjective.Goal.HOLD, hold.goal());
    }

    @Test void rejectsGarbage() {
        assertNull(CommanderStrategist.parseObjective(null));
        assertNull(CommanderStrategist.parseObjective(""));
        assertNull(CommanderStrategist.parseObjective("banana split"));
        assertNull(CommanderStrategist.parseObjective("GOTO_CHEST x y"));
        assertNull(CommanderStrategist.parseObjective("GOTO_CHEST -3 4"));
        assertNull(CommanderStrategist.parseObjective("GOTO_CHEST 9999 4"));
    }
}
