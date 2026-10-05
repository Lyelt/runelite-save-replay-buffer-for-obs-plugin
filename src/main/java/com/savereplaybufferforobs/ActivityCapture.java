package com.savereplaybufferforobs;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/** Tracks one activity at a time and, when it ends, asks Replay Buffer Pro to save a clip covering it. */
@Slf4j
final class ActivityCapture
{
    enum Activity
    {
        COX("Chambers of Xeric", InterfaceID.RAIDS_REWARDS, SaveReplayBufferForObsConfig::captureCox),
        TOB("Theatre of Blood", InterfaceID.TOB_CHESTS, SaveReplayBufferForObsConfig::captureTob),
        TOA("Tombs of Amascut", InterfaceID.TOA_CHESTS, SaveReplayBufferForObsConfig::captureToa),
        INFERNO("Inferno", -1, SaveReplayBufferForObsConfig::captureInferno),
        COLOSSEUM("Fortis Colosseum", InterfaceID.COLOSSEUM_REWARD_CHEST_2, SaveReplayBufferForObsConfig::captureColosseum),
        DOOM("Doom of Mokhaiotl", InterfaceID.DOM_END_LEVEL_UI, SaveReplayBufferForObsConfig::captureDoom);

        final String label;
        final int rewards;
        final Predicate<SaveReplayBufferForObsConfig> enabled;

        Activity(String label, int rewards, Predicate<SaveReplayBufferForObsConfig> enabled)
        {
            this.label = label;
            this.rewards = rewards;
            this.enabled = enabled;
        }
    }

    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;
    private final IntConsumer save;
    private final Consumer<String> chat;
    private final Set<Session> scheduledCaptures = new HashSet<>();
    private Session pending;
    private Activity location;

    private static final class Session
    {
        final Activity activity;
        final long started;
        boolean requested;
        boolean teamWiped;
        ScheduledFuture<?> future;

        Session(Activity activity, long started)
        {
            this.activity = activity;
            this.started = started;
        }
    }

    /** {@code chat} receives the few messages players see; details go to the debug log. */
    ActivityCapture(ScheduledExecutorService scheduler, LongSupplier clock, IntConsumer save, Consumer<String> chat)
    {
        this.scheduler = scheduler;
        this.clock = clock;
        this.save = save;
        this.chat = chat;
    }

    synchronized void chatMessage(String message, SaveReplayBufferForObsConfig config)
    {
        String plain = Text.removeTags(message);
        if (pending != null && pending.activity == Activity.INFERNO && plain.startsWith("Duration:"))
        {
            finish(Activity.INFERNO, "completion chat message \"" + plain + "\"", config);
        }
    }

    static Activity activityAt(WorldPoint point)
    {
        switch (point.getRegionID())
        {
            case 12869: case 12613: case 13125: case 13122: case 13123:
            case 13379: case 12612: case 12611: case 12867:
                return Activity.TOB;
            case 14160: case 14672: case 15698: case 15700: case 14162: case 14164:
            case 15186: case 15188: case 14674: case 14676: case 15184: case 15696:
                return Activity.TOA;
            case 9043:
                return Activity.INFERNO;
        }
        if (point.getPlane() == 0 && point.getX() >= 1806 && point.getX() < 1844
            && point.getY() >= 3088 && point.getY() < 3126) { return Activity.COLOSSEUM; }
        if (point.getRegionID() == 13668 || point.getRegionID() == 14180
            || (point.getPlane() == 0 && point.getX() >= 1299 && point.getX() < 1323
                && point.getY() >= 9559 && point.getY() < 9585))
        {
            return Activity.DOOM;
        }
        return null;
    }

    synchronized void locationChanged(WorldPoint point, boolean inCox, SaveReplayBufferForObsConfig config)
    {
        Activity next = inCox ? Activity.COX : activityAt(point);
        if (location == Activity.COLOSSEUM && point.getRegionID() == 7216)
        {
            next = Activity.COLOSSEUM; // Keep the session through the nearby reward chest.
        }
        if (location != next)
        {
            String where = inCox ? "the CoX raid" : "region " + point.getRegionID();
            // Exiting is an ending, regardless of the destination or reason for leaving.
            if (location != null)
            {
                finish(location, "left for " + where, config);
            }
            if (next != null && next.enabled.test(config))
            {
                start(next, "entered " + where, config);
            }
        }
        location = next;
    }

    private void start(Activity activity, String reason, SaveReplayBufferForObsConfig config)
    {
        if (pending != null && pending.activity != activity)
        {
            finish(pending.activity, activity.label + " started", config);
        }
        if (pending == null || pending.activity != activity || pending.requested)
        {
            pending = new Session(activity, clock.getAsLong());
            log.debug("{} capture started: {}", activity.label, reason);
            chat.accept("Recording " + activity.label + " for a replay clip.");
        }
    }

    synchronized void theatrePartyChanged(boolean fighting, int[] health, SaveReplayBufferForObsConfig config)
    {
        boolean occupied = false;
        boolean allDead = true;
        for (int value : health)
        {
            occupied |= value != 0;
            allDead &= value == 0 || value == 30; // 0 is an empty slot, 30 a dead player.
        }
        if (location == Activity.TOB && config.captureTob())
        {
            if (occupied && allDead)
            {
                if (pending != null && pending.activity == Activity.TOB)
                {
                    pending.teamWiped = true;
                }
                finish(Activity.TOB, "team wipe", config);
            }
            else if (fighting && occupied && pending != null && pending.teamWiped)
            {
                // Entry-mode retry after a wipe starts another attempt.
                start(Activity.TOB, "retry after a team wipe", config);
            }
        }
    }

    synchronized boolean playerDied(SaveReplayBufferForObsConfig config)
    {
        if (location == null || !location.enabled.test(config))
        {
            return false;
        }
        if (location == Activity.COX || location == Activity.TOB || location == Activity.TOA)
        {
            // Raids continue after a death, so it never saves; only a wipe or leaving ends the capture.
            log.debug("{} death ignored: raids continue after a death", location.label);
            return true;
        }
        if (pending == null || pending.activity != location)
        {
            return false;
        }
        finish(location, "player death", config);
        return true;
    }

    synchronized void exited(String reason, SaveReplayBufferForObsConfig config)
    {
        if (pending != null)
        {
            finish(pending.activity, reason, config);
        }
        location = null;
    }

    private void finish(Activity activity, String reason, SaveReplayBufferForObsConfig config)
    {
        if (pending != null && pending.activity == activity && !pending.requested && !activity.enabled.test(config))
        {
            log.debug("{} capture discarded ({}): capture was turned off mid-activity", activity.label, reason);
            pending = null;
            return;
        }
        end(activity, reason, config);
    }

    /** Schedules the save for the current session; returns false when no session for this activity exists. */
    synchronized boolean end(Activity activity, String reason, SaveReplayBufferForObsConfig config)
    {
        if (pending == null || pending.activity != activity)
        {
            return false;
        }
        if (pending.requested)
        {
            return true;
        }
        Session session = pending;
        session.requested = true;
        double activitySeconds = (clock.getAsLong() - session.started) / 1_000_000_000.0;
        double pre = activitySeconds * config.activityPrePercent() / 100.0;
        double post = activitySeconds * config.activityPostPercent() / 100.0;
        long delayMillis = (long) Math.ceil((post + config.rewardsDelay()) * 1000);
        log.debug("{} capture ended: {}. Activity lasted {}s; saving it plus {}s pre-padding after {}s post-padding and {}s Rewards delay",
            activity.label, reason, Math.round(activitySeconds), Math.round(pre), Math.round(post), config.rewardsDelay());
        chat.accept("Saving your " + activity.label + " replay clip (" + duration(activitySeconds) + ") in "
            + (long) Math.ceil(delayMillis / 1000.0) + " seconds.");
        scheduledCaptures.add(session);
        session.future = scheduler.schedule(() -> {
            synchronized (ActivityCapture.this)
            {
                if (scheduledCaptures.remove(session))
                {
                    save.accept(Math.max(1, (int) Math.ceil((clock.getAsLong() - session.started) / 1_000_000_000.0 + pre)));
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /** Formats seconds as m:ss, or h:mm:ss from an hour. */
    static String duration(double seconds)
    {
        long total = Math.round(seconds);
        return total >= 3600
            ? String.format("%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String.format("%d:%02d", total / 60, total % 60);
    }

    synchronized boolean capturing(Activity activity, SaveReplayBufferForObsConfig config)
    {
        return activity != null && activity.enabled.test(config) && pending != null && pending.activity == activity;
    }

    static Activity forRewards(int group)
    {
        for (Activity activity : Activity.values())
        {
            if (activity.rewards == group && group != -1) { return activity; }
        }
        return null;
    }

    /** Matches boss kill names and screenshot file names, such as "Sol Heredit(12)". */
    static Activity forName(String name)
    {
        if (name.startsWith("Sol Heredit")) { return Activity.COLOSSEUM; }
        if (name.startsWith("TzKal-Zuk")) { return Activity.INFERNO; }
        for (Activity activity : Activity.values())
        {
            if (name.startsWith(activity.label)) { return activity; }
        }
        return null;
    }

    synchronized void cancel()
    {
        for (Session session : scheduledCaptures)
        {
            session.future.cancel(false);
        }
        scheduledCaptures.clear();
        pending = null;
        location = null;
    }
}
