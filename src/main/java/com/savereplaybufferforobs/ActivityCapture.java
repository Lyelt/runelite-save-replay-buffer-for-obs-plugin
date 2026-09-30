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
import java.util.regex.Pattern;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/** Tracks one activity at a time and, when it ends, asks Replay Buffer Pro to save a clip covering it. */
final class ActivityCapture
{
    // A save that fires this late (for example, after the computer slept) is dropped rather than saving the wrong footage.
    private static final long MAX_AGE = TimeUnit.MINUTES.toNanos(10);

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
    private static final Pattern TOB_ENTRY = Pattern.compile("^(?:You enter the Theatre of Blood \\(\\w+ Mode\\)\\.\\.\\.|.+ has entered the Theatre of Blood \\(\\w+ Mode\\)\\. Step inside to join (?:her|him|them)\\.\\.\\.)$");
    private static final Pattern INFERNO_TIME = Pattern.compile("^Duration:\\s*[0-9]+:[0-5][0-9](?::[0-5][0-9])?(?:\\.[0-9]{1,3})?(?=\\s|$|\\.(?![0-9]))");

    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;
    private final IntConsumer save;
    private final Consumer<String> debug;
    private final Consumer<String> chat;
    private final Set<Session> scheduledCaptures = new HashSet<>();
    private Session pending;
    private Activity location;

    private static final class Session
    {
        final Activity activity;
        final long started;
        boolean requested;
        boolean entered;
        boolean teamWiped;
        ScheduledFuture<?> future;

        Session(Activity activity, long started)
        {
            this.activity = activity;
            this.started = started;
        }
    }

    /** {@code debug} receives detailed diagnostics; {@code chat} receives the few messages players see. */
    ActivityCapture(ScheduledExecutorService scheduler, LongSupplier clock, IntConsumer save, Consumer<String> debug, Consumer<String> chat)
    {
        this.scheduler = scheduler;
        this.clock = clock;
        this.save = save;
        this.debug = debug;
        this.chat = chat;
    }

    synchronized void chatMessage(String message, SaveReplayBufferForObsConfig config)
    {
        String plain = Text.removeTags(message);
        if (config.captureTob() && TOB_ENTRY.matcher(plain).matches())
        {
            start(Activity.TOB, false, "chat message \"" + plain + "\"", config);
        }
        if (pending != null && pending.activity == Activity.INFERNO && !pending.requested && INFERNO_TIME.matcher(plain).find())
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
            String where = inCox ? "the CoX raid (in-raid varbit set)" : "region " + point.getRegionID();
            // Exiting is an ending, regardless of the destination or reason for leaving.
            if (location != null)
            {
                finish(location, "left the activity area for " + where, config);
            }
            if (next != null && next.enabled.test(config))
            {
                start(next, true, "entered " + where, config);
            }
        }
        location = next;
    }

    private void start(Activity activity, boolean entered, String reason, SaveReplayBufferForObsConfig config)
    {
        if (pending != null && pending.activity != activity)
        {
            finish(pending.activity, activity.label + " started", config);
        }
        if (pending == null || pending.activity != activity || pending.requested)
        {
            pending = new Session(activity, clock.getAsLong());
            debug.accept(activity.label + " capture started: " + reason + (entered ? "." : ". It is kept only if you enter."));
        }
        else if (entered && !pending.entered)
        {
            debug.accept(activity.label + " capture continues: " + reason + ".");
        }
        if (entered && !pending.entered)
        {
            chat.accept("Recording " + activity.label + " for a replay clip.");
        }
        pending.entered |= entered;
    }

    synchronized void theatreStateChanged(int state, SaveReplayBufferForObsConfig config)
    {
        if (state == 2 && location != Activity.TOB && config.captureTob()) // 2: inside the Theatre.
        {
            start(Activity.TOB, true, "ToB party status became 2 (inside the Theatre)", config);
        }
        else if (state < 2 && location != Activity.TOB)
        {
            finish(Activity.TOB, "ToB party status became " + state + " (outside the Theatre)", config);
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
                finish(Activity.TOB, "team wipe (every party health orb shows dead)", config);
            }
            else if (fighting && occupied && pending != null && pending.teamWiped)
            {
                // Entry-mode retry after a wipe starts another attempt.
                start(Activity.TOB, true, "retry after a team wipe (party fighting again)", config);
            }
        }
    }

    synchronized boolean playerDied(SaveReplayBufferForObsConfig config)
    {
        boolean recording = pending != null && !pending.requested;
        Activity activity = location != null ? location : recording ? pending.activity : null;
        if (activity == null || !activity.enabled.test(config))
        {
            return false;
        }
        if (activity == Activity.COX || activity == Activity.TOB || activity == Activity.TOA)
        {
            // Raids continue after a death, so it never saves; only a wipe or leaving ends the capture.
            debug.accept(activity.label + " death ignored: raids continue after a death"
                + (recording && pending.activity == activity ? "; capture continues." : "; no capture is recording."));
            return true;
        }
        if (pending == null || pending.activity != activity)
        {
            return false;
        }
        finish(activity, "player death", config);
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
        if (pending != null && pending.activity == activity && !pending.requested
            && (!pending.entered || !activity.enabled.test(config)))
        {
            debug.accept(activity.label + " capture discarded (" + reason + "): "
                + (pending.entered ? "capture was turned off mid-activity." : "you never entered."));
            pending = null;
            return;
        }
        end(activity, reason, config.activityPrePercent(), config.activityPostPercent(), config.rewardsDelay());
    }

    synchronized boolean end(Activity activity, String reason, int prePercent, int postPercent, int delaySeconds)
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
        double activitySeconds = Math.max(0, clock.getAsLong() - session.started) / 1_000_000_000.0;
        double pre = activitySeconds * percent(prePercent);
        double post = activitySeconds * percent(postPercent);
        long delayMillis = (long) Math.ceil((post + Math.max(0, delaySeconds)) * 1000);
        debug.accept(String.format("%s capture ended: %s. Activity lasted %.0fs; saving it plus %.1fs pre-padding after"
            + " %.1fs post-padding and %ds Rewards delay (%.1fs).",
            activity.label, reason, activitySeconds, pre, post, Math.max(0, delaySeconds), delayMillis / 1000.0));
        chat.accept("Saving your " + activity.label + " replay clip (" + duration(activitySeconds) + ") in "
            + (long) Math.ceil(delayMillis / 1000.0) + " seconds.");
        long due = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        scheduledCaptures.add(session);
        session.future = scheduler.schedule(() -> {
            synchronized (ActivityCapture.this)
            {
                if (scheduledCaptures.remove(session) && clock.getAsLong() - due < MAX_AGE)
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

    private static double percent(int value)
    {
        return Math.max(0, Math.min(10, value)) / 100.0;
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
