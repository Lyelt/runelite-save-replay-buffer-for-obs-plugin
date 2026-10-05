package com.savereplaybufferforobs;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.runelite.api.GameState;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityCaptureTest
{
    private final SaveReplayBufferForObsConfig config = new SaveReplayBufferForObsConfig()
    {
        public boolean captureCox() { return true; }
        public boolean captureTob() { return true; }
        public boolean captureToa() { return true; }
        public boolean captureInferno() { return true; }
        public boolean captureColosseum() { return true; }
        public boolean captureDoom() { return true; }
        public int activityPostPercent() { return 1; } // Expected clip lengths below assume 1%.
    };
    private static final SaveReplayBufferForObsConfig NO_PADDING = new SaveReplayBufferForObsConfig()
    {
        public int activityPrePercent() { return 0; }
        public int activityPostPercent() { return 0; }
    };
    private long now;
    private final List<Runnable> callbacks = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<Long> dueTimes = new ArrayList<>();
    private final List<Integer> requests = new ArrayList<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1)
    {
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
        {
            callbacks.add(command);
            long delayMillis = unit.toMillis(delay);
            delays.add(delayMillis);
            dueTimes.add(now + TimeUnit.MILLISECONDS.toNanos(delayMillis));
            return super.schedule(command, 1, TimeUnit.DAYS);
        }
    };
    private final ActivityCapture capture = new ActivityCapture(scheduler, () -> now, requests::add, message -> { });

    @After
    public void close()
    {
        scheduler.shutdownNow();
    }

    @Test
    public void allSixActivitiesFinishOnExitToAnyDestination()
    {
        ActivityCapture.Activity[] activities = ActivityCapture.Activity.values();
        for (ActivityCapture.Activity activity : activities)
        {
            capture.cancel();
            callbacks.clear();
            delays.clear();
            dueTimes.clear();
            now = 0;
            start(activity);
            now = TimeUnit.SECONDS.toNanos(60);
            capture.locationChanged(new WorldPoint(3200, 3200, 0), false, config);
            assertEquals(activity.toString(), 1, callbacks.size());
            run(0);
            assertEquals(activity.toString(), Integer.valueOf(62), requests.get(requests.size() - 1));
        }
    }

    @Test
    public void coxVarbitStartsAndToaRoomTransitionsContinueUntilLobbyEjection()
    {
        capture.locationChanged(new WorldPoint(3200, 3200, 0), true, config);
        now = TimeUnit.MINUTES.toNanos(5);
        capture.locationChanged(new WorldPoint(3200, 3200, 0), false, config);
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(306), requests.get(0));

        capture.cancel();
        callbacks.clear();
        delays.clear();
        dueTimes.clear();
        requests.clear();
        now = 0;
        capture.locationChanged(region(15698), false, config);
        now = TimeUnit.MINUTES.toNanos(2);
        capture.locationChanged(region(15700), false, config); // Room transition.
        capture.locationChanged(region(15184), false, config);
        assertTrue(callbacks.isEmpty());
        now = TimeUnit.MINUTES.toNanos(20);
        capture.locationChanged(region(13454), false, config); // Ejected to the ToA lobby.
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(1224), requests.get(0));
    }

    @Test
    public void theatrePartialWipeContinuesAndFullWipeRetryStartsNewSession()
    {
        capture.locationChanged(region(12869), false, config);
        capture.theatrePartyChanged(true, new int[]{30, 16, 0}, config);
        assertTrue(callbacks.isEmpty());

        now = TimeUnit.MINUTES.toNanos(10);
        capture.theatrePartyChanged(true, new int[]{30, 30, 0}, config);
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(612), requests.get(0));

        now = TimeUnit.MINUTES.toNanos(11);
        capture.theatrePartyChanged(true, new int[]{26, 0, 0}, config); // Entry-mode retry.
        now = TimeUnit.MINUTES.toNanos(16);
        capture.locationChanged(region(14642), false, config);
        assertEquals(2, callbacks.size());
        run(1);
        assertEquals(Integer.valueOf(306), requests.get(1));
    }

    @Test
    public void individualRaidDeathsDoNotEndTheSession()
    {
        for (ActivityCapture.Activity activity : new ActivityCapture.Activity[]{ActivityCapture.Activity.COX,
            ActivityCapture.Activity.TOB, ActivityCapture.Activity.TOA})
        {
            capture.cancel();
            callbacks.clear();
            delays.clear();
            dueTimes.clear();
            start(activity);
            assertTrue(capture.playerDied(config));
            assertTrue(callbacks.isEmpty());
        }
    }

    @Test
    public void raidDeathsNeverSaveEvenWithoutARecordingSession()
    {
        SaveReplayBufferForObsConfig tobOnly = new SaveReplayBufferForObsConfig()
        {
            public boolean captureTob() { return true; }
        };
        start(ActivityCapture.Activity.TOB);
        capture.theatrePartyChanged(true, new int[]{30, 30, 0}, config); // Wipe ends the capture.
        assertEquals(1, callbacks.size());
        assertTrue(capture.playerDied(tobOnly)); // A later death in the same Theatre still never saves.

        capture.locationChanged(region(15698), false, tobOnly); // ToA capture disabled.
        assertFalse(capture.playerDied(tobOnly)); // The regular death setting applies.
        assertEquals(1, callbacks.size());
    }

    @Test
    public void proDeathAndDuplicateDoomDeathRequestOnlyOneSave()
    {
        capture.locationChanged(region(9043), false, config);
        now = TimeUnit.SECONDS.toNanos(30);
        assertTrue(capture.playerDied(config));
        assertTrue(capture.playerDied(config));
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(31), requests.get(0));

        capture.cancel();
        callbacks.clear();
        delays.clear();
        dueTimes.clear();
        requests.clear();
        start(ActivityCapture.Activity.DOOM);
        now = TimeUnit.SECONDS.toNanos(60);
        assertTrue(capture.playerDied(config));
        assertTrue(capture.playerDied(config));
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(31), requests.get(0));
    }

    @Test
    public void pluginProDeathOverridesGlobalDeathSettingAndDeduplicatesExit() throws Exception
    {
        Player localPlayer = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (proxy, method, args) ->
                method.getName().equals("getLocalPlayer") ? localPlayer : defaultValue(method.getReturnType()));
        SaveReplayBufferForObsPlugin plugin = new SaveReplayBufferForObsPlugin();
        setField(plugin, "activityCapture", capture);
        setField(plugin, "config", config); // savePlayerDeath() remains false.
        setField(plugin, "client", client);
        capture.locationChanged(region(9043), false, config);
        now = TimeUnit.SECONDS.toNanos(30);
        ActorDeath death = new ActorDeath(localPlayer);
        plugin.onActorDeath(death);
        plugin.onActorDeath(death);
        capture.locationChanged(new WorldPoint(3200, 3200, 0), false, config);
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(31), requests.get(0));
    }

    @Test
    public void defaultPaddingRewardsDelayAndSchedulingLagAreIncluded()
    {
        capture.locationChanged(region(15698), false, config);
        now = TimeUnit.SECONDS.toNanos(100);
        SaveReplayBufferForObsConfig rewardsDelay = new SaveReplayBufferForObsConfig()
        {
            public int activityPostPercent() { return 1; }
            public int rewardsDelay() { return 5; }
        };
        assertTrue(capture.end(ActivityCapture.Activity.TOA, "rewards interface opened", rewardsDelay));
        assertEquals(Long.valueOf(6000), delays.get(0));
        assertTrue(requests.isEmpty());
        now = TimeUnit.MILLISECONDS.toNanos(106_500); // 500 ms scheduler lag.
        run(0);
        assertEquals(Integer.valueOf(108), requests.get(0));
    }

    @Test
    public void completedSaveSurvivesImmediateNewActivityAndDuplicateEndSignals()
    {
        capture.locationChanged(region(15698), false, config);
        now = TimeUnit.SECONDS.toNanos(100);
        capture.locationChanged(region(13454), false, config);
        capture.locationChanged(new WorldPoint(1300, 9560, 0), false, config);
        assertEquals(1, callbacks.size());
        now = TimeUnit.SECONDS.toNanos(102);
        run(0);
        assertEquals(Integer.valueOf(103), requests.get(0));

        now = TimeUnit.SECONDS.toNanos(120);
        capture.locationChanged(new WorldPoint(3200, 3200, 0), false, config);
        assertEquals(2, callbacks.size());
        run(1);
        assertEquals(Integer.valueOf(21), requests.get(1));
    }

    @Test
    public void disabledActivitiesNeverStartSessions()
    {
        SaveReplayBufferForObsConfig disabled = new SaveReplayBufferForObsConfig() { };
        capture.locationChanged(region(15698), false, disabled);
        capture.locationChanged(region(13454), false, disabled);
        assertFalse(capture.end(ActivityCapture.Activity.TOA, "rewards interface opened", NO_PADDING));
        assertFalse(capture.end(ActivityCapture.Activity.TOB, "rewards interface opened", NO_PADDING));
        assertTrue(callbacks.isEmpty());
    }

    @Test
    public void infernoCompletionCapturesFullElapsedTimeFromEntry()
    {
        capture.chatMessage("Duration: 104:31.20 (new personal best)", config);
        assertTrue(callbacks.isEmpty());
        capture.locationChanged(region(9043), false, config);
        now = TimeUnit.SECONDS.toNanos(3610);
        capture.chatMessage("Duration: <col=ff0000>40:00</col>. Personal best: 35:00", config);
        assertEquals(1, callbacks.size());
        assertEquals(Long.valueOf(36_100), delays.get(0));
        now += TimeUnit.SECONDS.toNanos(36);
        run(0);
        assertEquals(Integer.valueOf(3683), requests.get(0));
    }

    @Test
    public void doomDelveTransitionsPreserveStartAndExitFinishes()
    {
        start(ActivityCapture.Activity.DOOM);
        now = TimeUnit.MINUTES.toNanos(10);
        capture.locationChanged(new WorldPoint(3400, 6400, 0), false, config);
        now = TimeUnit.MINUTES.toNanos(20);
        capture.locationChanged(new WorldPoint(3520, 6400, 0), false, config);
        capture.locationChanged(new WorldPoint(3200, 3200, 0), false, config);
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(1224), requests.get(0));
    }

    @Test
    public void doomClaimWidgetAndScriptHookRemainIdempotent() throws Exception
    {
        SaveReplayBufferForObsPlugin plugin = new SaveReplayBufferForObsPlugin();
        Field state = SaveReplayBufferForObsPlugin.class.getDeclaredField("activityCapture");
        state.setAccessible(true);
        state.set(plugin, capture);
        Field settings = SaveReplayBufferForObsPlugin.class.getDeclaredField("config");
        settings.setAccessible(true);
        settings.set(plugin, config);
        start(ActivityCapture.Activity.DOOM);
        WidgetLoaded widget = new WidgetLoaded();
        widget.setGroupId(InterfaceID.DOM_END_LEVEL_UI);
        plugin.onWidgetLoaded(widget);
        assertTrue(callbacks.isEmpty());
        ScriptPostFired claim = new ScriptPostFired(ScriptID.DOM_LOOT_CLAIM);
        plugin.onScriptPostFired(claim);
        assertEquals(1, callbacks.size());
        plugin.onScriptPostFired(claim);
        assertEquals(1, callbacks.size());
    }

    @Test
    public void exitPreservesScheduledSaveButCancelAndPluginShutdownCancelIt() throws Exception
    {
        start(ActivityCapture.Activity.TOA);
        now = TimeUnit.SECONDS.toNanos(10);
        assertTrue(capture.end(ActivityCapture.Activity.TOA, "rewards interface opened", NO_PADDING));
        capture.exited("logged out", config);
        run(0);
        assertEquals(Integer.valueOf(10), requests.get(0));

        callbacks.clear();
        delays.clear();
        dueTimes.clear();
        requests.clear();
        start(ActivityCapture.Activity.TOA);
        capture.end(ActivityCapture.Activity.TOA, "rewards interface opened", NO_PADDING);
        capture.cancel();
        run(0);
        assertTrue(requests.isEmpty());

        SaveReplayBufferForObsPlugin plugin = new SaveReplayBufferForObsPlugin()
        {
            @Override
            public void clearObsException() { }
        };
        Field pending = SaveReplayBufferForObsPlugin.class.getDeclaredField("activityCapture");
        pending.setAccessible(true);
        pending.set(plugin, capture);
        Field settings = SaveReplayBufferForObsPlugin.class.getDeclaredField("config");
        settings.setAccessible(true);
        settings.set(plugin, config);
        start(ActivityCapture.Activity.TOA);
        capture.end(ActivityCapture.Activity.TOA, "rewards interface opened", NO_PADDING);
        GameStateChanged logout = new GameStateChanged();
        logout.setGameState(GameState.LOGIN_SCREEN);
        plugin.onGameStateChanged(logout);
        assertEquals(2, callbacks.size());
        now += TimeUnit.SECONDS.toNanos(1);
        run(1);
        assertEquals(1, requests.size());

        start(ActivityCapture.Activity.TOA);
        capture.end(ActivityCapture.Activity.TOA, "rewards interface opened", NO_PADDING);
        plugin.shutDown();
        run(2);
        assertEquals(1, requests.size());
    }

    @Test
    public void logoutAndHopFinishButLostConnectionKeepsTheSession() throws Exception
    {
        SaveReplayBufferForObsPlugin plugin = new SaveReplayBufferForObsPlugin();
        setField(plugin, "activityCapture", capture);
        setField(plugin, "config", config);
        GameStateChanged state = new GameStateChanged();
        start(ActivityCapture.Activity.TOB);
        now = TimeUnit.SECONDS.toNanos(30);
        state.setGameState(GameState.CONNECTION_LOST);
        plugin.onGameStateChanged(state);
        start(ActivityCapture.Activity.TOB); // Reconnected into the same raid.
        assertTrue(callbacks.isEmpty());

        now = TimeUnit.SECONDS.toNanos(60);
        state.setGameState(GameState.HOPPING);
        plugin.onGameStateChanged(state);
        assertEquals(1, callbacks.size());
        run(0);
        assertEquals(Integer.valueOf(62), requests.get(0));

        start(ActivityCapture.Activity.INFERNO);
        state.setGameState(GameState.LOGIN_SCREEN);
        plugin.onGameStateChanged(state);
        assertEquals(2, callbacks.size());
    }

    @Test
    public void loggingOutToPauseSavesAndLoggingBackInStartsANewCapture() throws Exception
    {
        SaveReplayBufferForObsPlugin plugin = new SaveReplayBufferForObsPlugin();
        setField(plugin, "activityCapture", capture);
        setField(plugin, "config", config);
        GameStateChanged logout = new GameStateChanged();
        logout.setGameState(GameState.LOGIN_SCREEN);
        for (ActivityCapture.Activity activity : new ActivityCapture.Activity[]{ActivityCapture.Activity.INFERNO, ActivityCapture.Activity.COX})
        {
            capture.cancel();
            callbacks.clear();
            dueTimes.clear();
            requests.clear();
            now = 0;
            start(activity);
            now = TimeUnit.MINUTES.toNanos(30);
            plugin.onGameStateChanged(logout); // Logging out to pause saves the first part.
            assertEquals(1, callbacks.size());
            run(0);
            assertEquals(Integer.valueOf(1836), requests.get(0));

            now = TimeUnit.MINUTES.toNanos(60);
            start(activity); // Logged back in, still inside: a fresh capture from here.
            now = TimeUnit.MINUTES.toNanos(70);
            capture.locationChanged(new WorldPoint(3200, 3200, 0), false, config);
            assertEquals(2, callbacks.size());
            run(1);
            assertEquals(Integer.valueOf(612), requests.get(1));
        }
    }

    @Test
    public void disablingCaptureMidActivityDropsTheSession()
    {
        start(ActivityCapture.Activity.TOA);
        SaveReplayBufferForObsConfig disabled = new SaveReplayBufferForObsConfig() { };
        assertFalse(capture.capturing(ActivityCapture.Activity.TOA, disabled));
        capture.locationChanged(new WorldPoint(3200, 3200, 0), false, disabled);
        assertTrue(callbacks.isEmpty());
    }

    @Test
    public void screenshotsOnlyDeferToAnActiveSession()
    {
        assertFalse(capture.capturing(ActivityCapture.forName("Tombs of Amascut: Expert Mode(12)"), config));
        start(ActivityCapture.Activity.TOA);
        assertTrue(capture.capturing(ActivityCapture.forName("Tombs of Amascut: Expert Mode(12)"), config));
        assertFalse(capture.capturing(ActivityCapture.forName("TzKal-Zuk(3)"), config));
    }

    private void start(ActivityCapture.Activity activity)
    {
        switch (activity)
        {
            case COX:
                capture.locationChanged(new WorldPoint(3200, 3200, 0), true, config);
                break;
            case TOB:
                capture.locationChanged(region(12869), false, config);
                break;
            case TOA:
                capture.locationChanged(region(15698), false, config);
                break;
            case INFERNO:
                capture.locationChanged(region(9043), false, config);
                break;
            case COLOSSEUM:
                capture.locationChanged(new WorldPoint(1820, 3100, 0), false, config);
                break;
            case DOOM:
                capture.locationChanged(new WorldPoint(1300, 9560, 0), false, config);
                break;
            default:
                throw new AssertionError(activity);
        }
    }

    private void run(int index)
    {
        now = Math.max(now, dueTimes.get(index));
        callbacks.get(index).run();
    }

    private static void setField(Object object, String name, Object value) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive()) { return null; }
        if (type == boolean.class) { return false; }
        if (type == char.class) { return '\0'; }
        return 0;
    }

    private static WorldPoint region(int id)
    {
        return new WorldPoint((id >> 8) * 64, (id & 255) * 64, 0);
    }
}
