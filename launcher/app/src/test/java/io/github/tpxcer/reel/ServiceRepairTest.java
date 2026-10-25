package io.github.tpxcer.reel;
import org.junit.Test;
import static org.junit.Assert.*;
public class ServiceRepairTest {
    @Test public void treatsUnsetAndClearedSlotsAsEmpty() {
        assertTrue(ServiceRepair.isEmptySetting(null));
        assertTrue(ServiceRepair.isEmptySetting(""));
        assertTrue(ServiceRepair.isEmptySetting("null"));
    }
    @Test public void leavesAChosenAssistantAlone() {
        assertFalse(ServiceRepair.isEmptySetting("com.example.assistant/.AssistantService"));
    }
}
