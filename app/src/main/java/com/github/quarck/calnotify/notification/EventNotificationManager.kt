//
//   Calendar Notifications Plus  
//   Copyright (C) 2016 Sergey Parshin (s.parshin.sc@gmail.com)
//
//   This program is free software; you can redistribute it and/or modify
//   it under the terms of the GNU General Public License as published by
//   the Free Software Foundation; either version 3 of the License, or
//   (at your option) any later version.
// 
//   This program is distributed in the hope that it will be useful,
//   but WITHOUT ANY WARRANTY; without even the implied warranty of
//   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//   GNU General Public License for more details.
//
//   You should have received a copy of the GNU General Public License
//   along with this program; if not, write to the Free Software Foundation,
//   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
//

package com.github.quarck.calnotify.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.text.format.DateUtils
import com.github.quarck.calnotify.*
import com.github.quarck.calnotify.calendar.*
import com.github.quarck.calnotify.eventsstorage.EventsStorage
import com.github.quarck.calnotify.logs.DevLog
//import com.github.quarck.calnotify.logs.Logger
import com.github.quarck.calnotify.pebble.PebbleUtils
import com.github.quarck.calnotify.prefs.PreferenceUtils
import com.github.quarck.calnotify.quiethours.QuietHoursManager
import com.github.quarck.calnotify.reminders.ReminderState
import com.github.quarck.calnotify.textutils.EventFormatter
import com.github.quarck.calnotify.textutils.EventFormatterInterface
import com.github.quarck.calnotify.ui.MainActivity
import com.github.quarck.calnotify.ui.SnoozeActivityNoRecents
import com.github.quarck.calnotify.utils.*

@Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
class EventNotificationManager : EventNotificationManagerInterface {

    var lastSoundTimestamp = 0L;
    var lastVibrationTimestamp = 0L;

    override fun onEventAdded(ctx: Context, formatter: EventFormatterInterface, event: EventAlertRecord) {

        postEventNotifications(ctx, formatter, false, event.eventId)

        if (Settings(ctx).notificationPlayTts) {
            val text = "${event.title}\n${formatter.formatNotificationSecondaryText(event)}"
            TextToSpeechService.playText(ctx, text)
        }
    }

    override fun onEventRestored(context: Context, formatter: EventFormatterInterface, event: EventAlertRecord) {
        EventsStorage(context).use {
            it.updateEvent(event,
                    // do not update last event visibility, so preserve original sorting order in the activity
                    // lastStatusChangeTime = System.currentTimeMillis(),
                    displayStatus = EventDisplayStatus.Hidden)
        }

        postEventNotifications(context, formatter, true, event.eventId)
    }

    override fun onEventDismissed(context: Context, formatter: EventFormatterInterface, eventId: Long, notificationId: Int) {
        removeNotification(context, notificationId)
        postEventNotifications(context, formatter, false, null)
    }

    override fun onEventsDismissed(context: Context, formatter: EventFormatterInterface, events: Collection<EventAlertRecord>, postNotifications: Boolean, hasActiveEvents: Boolean) {

        for (event in events) {
            removeNotification(context, event.notificationId)
        }

        if (!hasActiveEvents) {
            removeNotification(context, Consts.NOTIFICATION_ID_BUNDLED_GROUP)
        }

        if (postNotifications) {
            postEventNotifications(context, formatter, false, null)
        }
    }

    override fun onEventSnoozed(context: Context, formatter: EventFormatterInterface, eventId: Long, notificationId: Int) {
        removeNotification(context, notificationId)
        postEventNotifications(context, formatter, false, null)
    }

    override fun onAllEventsSnoozed(context: Context) {
        context.notificationManager.cancelAll()
    }

    @Suppress("DEPRECATION")
    fun wakeScreenIfRequired(ctx: Context, settings: Settings) {

        if (settings.notificationWakeScreen) {
            //
            backgroundWakeLocked(
                    ctx.powerManager,
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    SCREEN_WAKE_LOCK_NAME) {
                // Screen would actually be turned on for a duration of screen timeout set by the user
                // So don't need to keep wakelock for too long
                Thread.sleep(Consts.WAKE_SCREEN_DURATION)
            }
        }

    }

    fun arrangeEvents(
            db: EventsStorage,
            currentTime: Long,
            settings: Settings
    ): Pair<List<EventAlertRecord>, List<EventAlertRecord>> {

        // events with snoozedUntil == 0 are currently visible ones
        // events with experied snoozedUntil are the ones to beep about
        // everything else should be hidden and waiting for the next alarm

        val events =
                db.events.filter {
                    ((it.snoozedUntil == 0L)
                            || (it.snoozedUntil < currentTime + Consts.ALARM_THRESHOLD))
                            && it.isNotSpecial
                }

        if (events.size >= Consts.MAX_NUM_EVENTS_BEFORE_COLLAPSING_EVERYTHING) {
            // short-cut to avoid heavy memory load on dealing with lots of events...
            return Pair(listOf<EventAlertRecord>(), events)
        }

        val activeEvents = events.sortedBy { it.lastStatusChangeTime }

        val maxNotifications = settings.maxNotifications
        val collapseEverything = settings.collapseEverything

        var recentEvents = activeEvents.takeLast(maxNotifications - 1)
        var collapsedEvents = activeEvents.take(activeEvents.size - recentEvents.size)

        if (collapsedEvents.size == 1) {
            recentEvents += collapsedEvents
            collapsedEvents = listOf<EventAlertRecord>()
        }
        else if (collapseEverything && !collapsedEvents.isEmpty()) {
            collapsedEvents = recentEvents + collapsedEvents
            recentEvents = listOf<EventAlertRecord>()
        }

        return Pair(recentEvents, collapsedEvents)
    }

    override fun postEventNotifications(context: Context, formatter: EventFormatterInterface, force: Boolean, primaryEventId: Long?) {
        //
        val settings = Settings(context)

        val currentTime = System.currentTimeMillis()

        val isQuietPeriodActive = QuietHoursManager.getSilentUntil(settings) != 0L

        var updatedAnything = false

        EventsStorage(context).use {
            db ->

            val (recentEvents, collapsedEvents) = arrangeEvents(db, currentTime, settings)


            if (recentEvents.isNotEmpty()) {

                val ongoingSummary =
                        (settings.showDismissButton && !settings.allowSwipeToSnooze) || recentEvents.isNotEmpty()

                updatedAnything =
                        postDisplayedEventNotifications(
                                context, db, settings,
                                formatter,
                                recentEvents,
                                force, isQuietPeriodActive,
                                primaryEventId,
                                ongoingSummary,
                                numTotalEvents = recentEvents.size + collapsedEvents.size
                                )
            }

            if (!recentEvents.isEmpty())
                collapseDisplayedNotifications(context, db, collapsedEvents, settings, force, isQuietPeriodActive)
            else {
                if (postEverythingCollapsed(context, db, collapsedEvents, settings, null, force, isQuietPeriodActive, primaryEventId, false))
                    updatedAnything = true
            }

            if (recentEvents.isEmpty() && collapsedEvents.isEmpty()) {
                removeNotification(context, Consts.NOTIFICATION_ID_BUNDLED_GROUP)
            }
        }

        // If this is a new notification -- wake screen when required
        if (primaryEventId != null || updatedAnything)
            wakeScreenIfRequired(context, settings)
    }

    override fun fireEventReminder(context: Context, itIsAfterQuietHoursReminder: Boolean) {

        val settings = Settings(context)
        val isQuietPeriodActive = QuietHoursManager.getSilentUntil(settings) != 0L

        EventsStorage(context).use {
            db ->

            val notificationSettings =
                    settings.notificationSettingsSnapshot.copy(
                            ringtoneUri = settings.reminderRingtoneURI,
                            vibrationOn = settings.reminderVibraOn,
                            vibrationPattern = settings.reminderVibrationPattern
                    )

            val currentTime = System.currentTimeMillis()

            val activeEvents =
                    db.events
                            .filter {
                                ((it.snoozedUntil == 0L)
                                        || (it.snoozedUntil < currentTime + Consts.ALARM_THRESHOLD))
                                        && it.isNotSpecial
                            }

            val numActiveEvents = activeEvents.count()
            val lastVisibility = activeEvents.map { it.lastStatusChangeTime }.max() ?: 0L

            if (numActiveEvents > 0) {

                if (itIsAfterQuietHoursReminder && settings.ledNotificationOn)
                    postEventNotifications(context, EventFormatter(context), true, null) // Re-post everything to enable LEDs

                postReminderNotification(
                        context,
                        numActiveEvents,
                        lastVisibility,
                        notificationSettings,
                        isQuietPeriodActive,
                        itIsAfterQuietHoursReminder
                )

                wakeScreenIfRequired(context, settings)

            }
            else {
                context.notificationManager.cancel(Consts.NOTIFICATION_ID_REMINDER)
            }
        }
    }

    override fun cleanupEventReminder(context: Context) {
        context.notificationManager.cancel(Consts.NOTIFICATION_ID_REMINDER)
    }

    private fun postEverythingCollapsed(
            context: Context, db: EventsStorage,
            events: List<EventAlertRecord>, settings: Settings,
            notificationsSettingsIn: NotificationSettingsSnapshot?,
            force: Boolean, isQuietPeriodActive: Boolean, primaryEventId: Long?, playReminderSound: Boolean): Boolean {

        if (events.isEmpty()) {
            hideCollapsedEventsNotification(context)
            return false
        }
        DevLog.debug(context, LOG_TAG, "Posting ${events.size} notifications in collapsed view")

        val notificationsSettings = notificationsSettingsIn ?: settings.notificationSettingsSnapshot

        var postedNotification = false

        var shouldPlayAndVibrate = false

        val currentTime = System.currentTimeMillis()


        // make sure we remove full notifications
        if (force)
            removeNotifications(context, events)
        else
            removeVisibleNotifications(context, events)

        val eventsToUpdate = events
                .filter {
                    it.snoozedUntil != 0L || it.displayStatus != EventDisplayStatus.DisplayedCollapsed
                }

        db.updateEvents(
                eventsToUpdate,
                snoozedUntil = 0L,
                displayStatus = EventDisplayStatus.DisplayedCollapsed
        )


        for (event in events) {

            if (event.snoozedUntil == 0L) {

                if ((event.displayStatus != EventDisplayStatus.DisplayedCollapsed) || force) {
                    // currently not displayed or forced -- post notifications
//                    DevLog.debug(LOG_TAG, "Posting notification id ${event.notificationId}, eventId ${event.eventId}")

                    var shouldBeQuiet = false

                    if (force) {
                        // If forced to re-post all notifications - we only have to actually display notifications
                        // so not playing sound / vibration here
                        //DevLog.debug(LOG_TAG, "event ${event.eventId}: 'forced' notification - staying quiet")
                        shouldBeQuiet = true

                    }
                    else if (event.displayStatus == EventDisplayStatus.DisplayedNormal) {

                        //DevLog.debug(LOG_TAG, "event ${event.eventId}: notification was displayed, not playing sound")
                        shouldBeQuiet = true

                    }
                    else if (isQuietPeriodActive) {

                        // we are in a silent period, normally we should always be quiet, but there
                        // are a few exclusions
                        if (primaryEventId != null && event.eventId == primaryEventId) {
                            // this is primary event -- play based on use preference for muting
                            // primary event reminders
                            //DevLog.debug(LOG_TAG, "event ${event.eventId}: quiet period and this is primary notification - sound according to settings")
                            shouldBeQuiet = settings.quietHoursMutePrimary
                        }
                        else {
                            // not a primary event -- always silent in silent period
                            //DevLog.debug(LOG_TAG, "event ${event.eventId}: quiet period and this is NOT primary notification quiet")
                            shouldBeQuiet = true
                        }
                    }

                    postedNotification = true
                    shouldPlayAndVibrate = shouldPlayAndVibrate || !shouldBeQuiet

                }
            }
            else {
                // This event is currently snoozed and switching to "Shown" state

                //DevLog.debug(LOG_TAG, "Posting snoozed notification id ${event.notificationId}, eventId ${event.eventId}, isQuietPeriodActive=$isQuietPeriodActive")

                postedNotification = true
                shouldPlayAndVibrate = shouldPlayAndVibrate || !isQuietPeriodActive
            }
        }

        if (playReminderSound)
            shouldPlayAndVibrate = shouldPlayAndVibrate || !isQuietPeriodActive


        // now build actual notification and notify
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, MAIN_ACTIVITY_EVERYTHING_COLLAPSED_CODE, intent, 0)

        val numEvents = events.size

        val title = java.lang.String.format(
                context.getString(R.string.multiple_events_single_notification),
                numEvents)

        val text = context.getString(com.github.quarck.calnotify.R.string.multiple_events_details)

        val bigText =
                events
                        .sortedByDescending { it.instanceStartTime }
                        .take(30)
                        .fold(
                                StringBuilder(), {
                            sb, ev ->

                            val flags =
                                    if (DateUtils.isToday(ev.displayedStartTime))
                                        DateUtils.FORMAT_SHOW_TIME
                                    else
                                        DateUtils.FORMAT_SHOW_DATE

                            sb.append("${DateUtils.formatDateTime(context, ev.displayedStartTime, flags)}: ${ev.title}\n")
                        })
                        .toString()

        val builder =
                NotificationCompat.Builder(context)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(com.github.quarck.calnotify.R.drawable.stat_notify_calendar)
                        .setPriority(
                                if (notificationsSettings.headsUpNotification && shouldPlayAndVibrate)
                                    NotificationCompat.PRIORITY_HIGH
                                else
                                    NotificationCompat.PRIORITY_DEFAULT
                        )
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                        .setNumber(numEvents)
                        .setShowWhen(false)

        if (shouldPlayAndVibrate) {
            if (notificationsSettings.ringtoneUri != null
                    && (currentTime - lastSoundTimestamp > Consts.MIN_INTERVAL_BETWEEN_SOUNDS)) {

                lastSoundTimestamp = currentTime

                if (notificationsSettings.useAlarmStream)
                    builder.setSound(notificationsSettings.ringtoneUri, AudioManager.STREAM_ALARM)
                else
                    builder.setSound(notificationsSettings.ringtoneUri)
            }

            if (notificationsSettings.vibrationOn
                    && (currentTime - lastVibrationTimestamp > Consts.MIN_INTERVAL_BETWEEN_VIBRATIONS)) {

                lastVibrationTimestamp = currentTime

                builder.setVibrate(notificationsSettings.vibrationPattern)
            }
            else {
                builder.setVibrate(longArrayOf(0))
            }

            if (notificationsSettings.ledNotificationOn && (!isQuietPeriodActive || !settings.quietHoursMuteLED)) {
                if (notificationsSettings.ledPattern.size == 2)
                    builder.setLights(notificationsSettings.ledColor, notificationsSettings.ledPattern[0], notificationsSettings.ledPattern[1])
                else
                    builder.setLights(notificationsSettings.ledColor, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF)
            }
        }

        val notification = builder.build()

        context.notificationManager.notify(Consts.NOTIFICATION_ID_COLLAPSED, notification) // would update if already exists

        val reminderState = ReminderState(context)

        if (shouldPlayAndVibrate) {
            context.persistentState.notificationLastFireTime = System.currentTimeMillis()
            reminderState.numRemindersFired = 0;
        }

        if (isQuietPeriodActive && events.isNotEmpty() && !shouldPlayAndVibrate) {

            DevLog.debug(LOG_TAG, "Would remind after snooze period")

            if (!reminderState.quietHoursOneTimeReminderEnabled)
                reminderState.quietHoursOneTimeReminderEnabled = true
        }

        if (shouldPlayAndVibrate && notificationsSettings.forwardToPebble)
            PebbleUtils.forwardNotificationToPebble(context, title, "", true)

        return postedNotification
    }


    private fun collapseDisplayedNotifications(
            context: Context, db: EventsStorage,
            events: List<EventAlertRecord>, settings: Settings,
            force: Boolean,
            isQuietPeriodActive: Boolean) {

        DevLog.debug(LOG_TAG, "Hiding notifications for ${events.size} notification")

        if (events.isEmpty()) {
            hideCollapsedEventsNotification(context)
            return
        }

        for (event in events) {
            if ((event.displayStatus != EventDisplayStatus.Hidden) || force) {
                //DevLog.debug(LOG_TAG, "Hiding notification id ${event.notificationId}, eventId ${event.eventId}")
                removeNotification(context, event.notificationId)
            }
            else {
                //DevLog.debug(LOG_TAG, "Skipping collapsing notification id ${event.notificationId}, eventId ${event.eventId} - already collapsed")
            }

            if (event.snoozedUntil != 0L || event.displayStatus != EventDisplayStatus.DisplayedCollapsed) {
                db.updateEvent(event,
                        snoozedUntil = 0L,
                        displayStatus = EventDisplayStatus.DisplayedCollapsed)
            }
        }

        postNumNotificationsCollapsed(context, db, settings, events, isQuietPeriodActive)
    }

    // force - if true - would re-post all active notifications. Normally only new notifications are posted to
    // avoid excessive blinking in the notifications area. Forced notifications are posted without sound or vibra
    private fun postDisplayedEventNotifications(
            context: Context,
            db: EventsStorage,
            settings: Settings,
            formatter: EventFormatterInterface,
            events: List<EventAlertRecord>,
            force: Boolean,
            isQuietPeriodActive: Boolean,
            primaryEventId: Long?,
            summaryNotificationIsOngoing: Boolean,
            numTotalEvents: Int
    ): Boolean {

        DevLog.debug(context, LOG_TAG, "Posting ${events.size} notifications")

        val notificationsSettings = settings.notificationSettingsSnapshot

        val notificationsSettingsQuiet =
                notificationsSettings.copy(ringtoneUri = null, vibrationOn = false, forwardToPebble = false)

        var postedNotification = false
        var playedAnySound = false

        val snoozePresets = settings.snoozePresets

        if (isMarshmallowOrAbove && settings.useBundledNotifications) {
            postGroupNotification(
                    context,
                    notificationsSettingsQuiet,
                    snoozePresets,
                    summaryNotificationIsOngoing,
                    numTotalEvents
            )
        }

        var currentTime = System.currentTimeMillis()

        for (event in events) {

            currentTime++ // so last change times are not all the same

            if (event.snoozedUntil == 0L) {
                // snooze zero could mean
                // - this is a new event -- we have to display it, it would have displayStatus == hidden
                // - this is an old event returning from "collapsed" state
                // - this is currently potentially displayed event but we are doing "force re-post" to
                //   ensure all events are displayed (like at boot or after app upgrade

                var wasCollapsed = false

                if ((event.displayStatus != EventDisplayStatus.DisplayedNormal) || force) {
                    // currently not displayed or forced -- post notifications
                    DevLog.info(context, LOG_TAG, "Posting notification id ${event.notificationId}, eventId ${event.eventId}")

                    var shouldBeQuiet = false

                    if (force) {
                        // If forced to re-post all notifications - we only have to actually display notifications
                        // so not playing sound / vibration here
                        DevLog.info(context, LOG_TAG, "event ${event.eventId}: 'forced' notification - staying quiet")
                        shouldBeQuiet = true
                    }
                    else if (event.displayStatus == EventDisplayStatus.DisplayedCollapsed) {
                        // This event was already visible as "collapsed", user just removed some other notification
                        // and so we automatically expanding some of the events, this one was lucky.
                        // No sound / vibration should be played here
                        DevLog.info(context, LOG_TAG, "event ${event.eventId}: notification was collapsed, not playing sound")
                        shouldBeQuiet = true
                        wasCollapsed = true

                    }
                    else if (isQuietPeriodActive) {
                        // we are in a silent period, normally we should always be quiet, but there
                        // are a few exclusions
                        if (primaryEventId != null && event.eventId == primaryEventId) {
                            // this is primary event -- play based on use preference for muting
                            // primary event reminders
                            DevLog.info(context, LOG_TAG, "event ${event.eventId}: quiet period and this is primary notification - sound according to settings")
                            shouldBeQuiet = settings.quietHoursMutePrimary
                        }
                        else {
                            // not a primary event -- always silent in silent period
                            DevLog.info(context, LOG_TAG, "event ${event.eventId}: quiet period and this is NOT primary notification quiet")
                            shouldBeQuiet = true
                        }
                    }

                    DevLog.debug(LOG_TAG, "event ${event.eventId}: shouldBeQuiet = $shouldBeQuiet")

                    postNotification(
                            context,
                            settings,
                            formatter,
                            event,
                            if (shouldBeQuiet) notificationsSettingsQuiet else notificationsSettings,
                            force,
                            wasCollapsed,
                            snoozePresets,
                            isQuietPeriodActive)

                    // Update db to indicate that this event is currently actively displayed
                    db.updateEvent(event, displayStatus = EventDisplayStatus.DisplayedNormal)

                    postedNotification = true
                    playedAnySound = playedAnySound || !shouldBeQuiet

                }
                else {
                    DevLog.info(context, LOG_TAG, "Not re-posting notification id ${event.notificationId}, eventId ${event.eventId} - already on the screen")
                }
            }
            else {
                // This event is currently snoozed and switching to "Shown" state

                DevLog.info(context, LOG_TAG, "Posting snoozed notification id ${event.notificationId}, eventId ${event.eventId}, isQuietPeriodActive=$isQuietPeriodActive")

                postNotification(
                        context,
                        settings,
                        formatter,
                        event,
                        if (isQuietPeriodActive) notificationsSettingsQuiet else notificationsSettings,
                        force,
                        false,
                        snoozePresets,
                        isQuietPeriodActive)

                if (event.snoozedUntil + Consts.ALARM_THRESHOLD < currentTime) {

                    val warningMessage = "snooze alarm is very late: expected at ${event.snoozedUntil}, " +
                            "received at $currentTime, late by ${currentTime - event.snoozedUntil} us"

                    DevLog.warn(context, LOG_TAG, warningMessage)

                    if (settings.debugAlarmDelays)
                        postNotificationsAlarmDelayDebugMessage(context,
                                "Snooze alarm was late!", "Late by ${(currentTime - event.snoozedUntil) / 1000L}s")

                }
                // Update Db to indicate that event is currently displayed and no longer snoozed
                // Since it is displayed now -- it is no longer snoozed, set snoozedUntil to zero
                // also update 'lastVisible' time since event just re-appeared
                db.updateEvent(event,
                        snoozedUntil = 0,
                        displayStatus = EventDisplayStatus.DisplayedNormal,
                        lastStatusChangeTime = currentTime)

                postedNotification = true
                playedAnySound = playedAnySound || !isQuietPeriodActive
            }
        }

        val reminderState = ReminderState(context)

        if (playedAnySound) {
            context.persistentState.notificationLastFireTime = System.currentTimeMillis()
            reminderState.numRemindersFired = 0;
        }

        if (isQuietPeriodActive && events.isNotEmpty() && !playedAnySound) {

            DevLog.info(context, LOG_TAG, "Was quiet due to quiet hours - would remind after snooze period")

            if (!reminderState.quietHoursOneTimeReminderEnabled)
                reminderState.quietHoursOneTimeReminderEnabled = true
        }

        return postedNotification
    }

    private fun lastStatusChangeToSortingKey(lastStatusChangeTime: Long): String {

        val sb = StringBuffer(20);

        var temp = lastStatusChangeTime

        while (temp > 0) {

            val chr = 24 - temp % 24;
            temp /= 24

            sb.append(('A'.toInt() + chr).toChar())
        }

        return sb.reverse().toString()
    }

    @Suppress("DEPRECATION")
    private fun postReminderNotification(
            ctx: Context,
            numActiveEvents: Int,
            lastVisibility: Long,
            notificationSettings: NotificationSettingsSnapshot,
            isQuietPeriodActive: Boolean, itIsAfterQuietHoursReminder: Boolean
    ) {
        val notificationManager = NotificationManagerCompat.from(ctx)

        val intent = Intent(ctx, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(ctx, MAIN_ACTIVITY_REMINDER_CODE, intent, 0)

        val persistentState = ctx.persistentState

        val currentTime = System.currentTimeMillis()
        val msAgo: Long = (currentTime - persistentState.notificationLastFireTime)

        val resources = ctx.resources

        val title =
            when {
                itIsAfterQuietHoursReminder ->
                        resources.getString(R.string.reminder_after_quiet_time)
                numActiveEvents == 1 ->
                    resources.getString(R.string.reminder_you_have_missed_event)
                else ->
                    resources.getString(R.string.reminder_you_have_missed_events)
            }

        var text = ""

        if (!itIsAfterQuietHoursReminder) {

            val currentReminder = ReminderState(ctx).numRemindersFired + 1
            val totalReminders = Settings(ctx).maxNumberOfReminders

            val textTemplate = resources.getString(R.string.last_notification_s_ago)

            text = String.format(textTemplate,
                    EventFormatter(ctx).formatTimeDuration(msAgo),
                    currentReminder, totalReminders)
        }
        else {
            val textTemplate = resources.getString(R.string.last_event_s_ago)

            text = String.format(textTemplate,
                    EventFormatter(ctx).formatTimeDuration(msAgo))
        }

        val builder = NotificationCompat.Builder(ctx)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.stat_notify_calendar)
                .setPriority(
                        NotificationCompat.PRIORITY_LOW
                )
                .setContentIntent(
                        pendingIntent
                )
                .setAutoCancel(true)
                .setOngoing(false)
                .setStyle(
                        NotificationCompat.BigTextStyle().bigText(text)
                )
                .setWhen(
                        lastVisibility
                )
                .setShowWhen(false)
                .setSortKey(
                        lastStatusChangeToSortingKey(lastVisibility)
                )
                .setCategory(
                        NotificationCompat.CATEGORY_REMINDER
                )
                .setOnlyAlertOnce(false)

        if (notificationSettings.ringtoneUri != null
                && (currentTime - lastSoundTimestamp > Consts.MIN_INTERVAL_BETWEEN_SOUNDS)) {

            lastSoundTimestamp = currentTime

            if (notificationSettings.useAlarmStream)
                builder.setSound(notificationSettings.ringtoneUri, AudioManager.STREAM_ALARM)
            else
                builder.setSound(notificationSettings.ringtoneUri)
        }

        if (notificationSettings.vibrationOn
                && (currentTime - lastVibrationTimestamp > Consts.MIN_INTERVAL_BETWEEN_VIBRATIONS)) {

            lastVibrationTimestamp = currentTime

            builder.setVibrate(notificationSettings.vibrationPattern)
        }
        else {
            builder.setVibrate(longArrayOf(0))
        }

        if (notificationSettings.ledNotificationOn && (!isQuietPeriodActive || !notificationSettings.quietHoursMuteLED)) {
            if (notificationSettings.ledPattern.size == 2)
                builder.setLights(notificationSettings.ledColor, notificationSettings.ledPattern[0], notificationSettings.ledPattern[1])
            else
                builder.setLights(notificationSettings.ledColor, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF)
        }

        val notification = builder.build()

        try {
            DevLog.info(ctx, LOG_TAG, "adding reminder notification")

            notificationManager.notify(
                    Consts.NOTIFICATION_ID_REMINDER,
                    notification
            )
        }
        catch (ex: Exception) {
            DevLog.error(ctx, LOG_TAG, "Exception: $ex, reminder notification stack:")
            ex.printStackTrace()
        }

        if (notificationSettings.forwardToPebble) {
            PebbleUtils.forwardNotificationToPebble(ctx, title, text, notificationSettings.pebbleOldFirmware)
        }
    }

    @Suppress("unused")
    private fun isNotificationVisible(ctx: Context, event: EventAlertRecord): Boolean {

        val intent = snoozeIntent(ctx, event.eventId, event.instanceStartTime, event.notificationId)
        val id = event.notificationId * EVENT_CODES_TOTAL + EVENT_CODE_SNOOOZE_OFFSET
        val pendingIntent: PendingIntent? = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_NO_CREATE)
        return pendingIntent != null
    }

    private fun postGroupNotification(
            ctx: Context,
            notificationSettings: NotificationSettingsSnapshot,
            snoozePresets: LongArray,
            summaryNotificationIsOngoing: Boolean,
            numTotalEvents: Int
    ) {
        val notificationManager = NotificationManagerCompat.from(ctx)

        val intent = Intent(ctx, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(ctx, MAIN_ACTIVITY_GROUP_NOTIFICATION_CODE, intent, 0)

        val text = ctx.resources.getString(R.string.N_calendar_events).format(numTotalEvents)

        val groupBuilder = NotificationCompat.Builder(ctx)
                .setSmallIcon(R.drawable.stat_notify_calendar)
                .setContentTitle(ctx.resources.getString(R.string.calendar))
                .setContentText(text)
                .setSubText(text)
                .setGroupSummary(true)
                .setGroup(NOTIFICATION_GROUP)
                .setContentIntent(pendingIntent)
                .setCategory(
                        NotificationCompat.CATEGORY_EVENT
                )
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false)

        if (summaryNotificationIsOngoing)
            groupBuilder.setOngoing(true)

        if (notificationSettings.showDismissButton) {
            if (notificationSettings.allowSwipeToSnooze) {

                val snoozeIntent = Intent(ctx, NotificationActionSnoozeService::class.java)
                snoozeIntent.putExtra(Consts.INTENT_SNOOZE_PRESET, snoozePresets[0])
                snoozeIntent.putExtra(Consts.INTENT_SNOOZE_ALL_KEY, true)

                val pendingSnoozeIntent =
                        pendingServiceIntent(ctx, snoozeIntent, EVENT_CODE_DEFAULT_SNOOOZE0_OFFSET)

                groupBuilder.setDeleteIntent(pendingSnoozeIntent)
            }
        }
        else {
            val dismissIntent = Intent(ctx, NotificationActionDismissService::class.java)
            dismissIntent.putExtra(Consts.INTENT_DISMISS_ALL_KEY, true)

            val pendingDismissIntent =
                    pendingServiceIntent(ctx, dismissIntent, EVENT_CODE_DISMISS_OFFSET)

            groupBuilder.setDeleteIntent(pendingDismissIntent)
        }

        try {
            notificationManager.notify(
                    Consts.NOTIFICATION_ID_BUNDLED_GROUP,
                    groupBuilder.build()
            )
        }
        catch (ex: Exception) {
            DevLog.error(ctx, LOG_TAG, "Exception: $ex")
        }
    }

    private fun postNotification(
            ctx: Context,
            settings: Settings,
            formatter: EventFormatterInterface,
            event: EventAlertRecord,
            notificationSettings: NotificationSettingsSnapshot,
            isForce: Boolean,
            wasCollapsed: Boolean,
            snoozePresets: LongArray,
            isQuietPeriodActive: Boolean
    ) {
        val notificationManager = NotificationManagerCompat.from(ctx)

        val calendarIntent = CalendarIntents.getCalendarViewIntent(event)

        val calendarPendingIntent =
                TaskStackBuilder.create(ctx)
                        .addNextIntentWithParentStack(calendarIntent)
                        .getPendingIntent(
                                event.notificationId * EVENT_CODES_TOTAL + EVENT_CODE_OPEN_OFFSET,
                                PendingIntent.FLAG_UPDATE_CURRENT)

        val snoozeActivityIntent =
                pendingActivityIntent(ctx,
                        snoozeIntent(ctx, event.eventId, event.instanceStartTime, event.notificationId),
                        event.notificationId * EVENT_CODES_TOTAL + EVENT_CODE_SNOOOZE_OFFSET
                )

        val dismissPendingIntent =
                pendingServiceIntent(ctx,
                        dismissOrDeleteIntent(ctx, event.eventId, event.instanceStartTime, event.notificationId),
                        event.notificationId * EVENT_CODES_TOTAL + EVENT_CODE_DISMISS_OFFSET
                )


        val currentTime = System.currentTimeMillis()


        val notificationText = formatter.formatNotificationSecondaryText(event)

        var title = event.title

        if (settings.enableMonitorDebug
                && event.origin != EventOrigin.ProviderBroadcast
                && event.timeFirstSeen != 0L) {
            title = "#${event.origin},${(event.timeFirstSeen - event.alertTime) / 60000L}m# ${event.title}"
        }

        val sortKey = lastStatusChangeToSortingKey(event.lastStatusChangeTime)

        DevLog.info(ctx, LOG_TAG, "SortKey: ${event.eventId} -> ${event.lastStatusChangeTime} -> $sortKey")

        val builder = NotificationCompat.Builder(ctx)
                .setContentTitle(title)
                .setContentText(notificationText)
                .setSmallIcon(R.drawable.stat_notify_calendar)
                .setPriority(
                        if (notificationSettings.headsUpNotification && !isForce && !wasCollapsed)
                            NotificationCompat.PRIORITY_HIGH
                        else
                            NotificationCompat.PRIORITY_DEFAULT
                )
                .setContentIntent(
                        if (notificationSettings.notificationOpensSnooze)
                            snoozeActivityIntent
                        else
                            calendarPendingIntent
                )
                .setAutoCancel(
                        false // !notificationSettings.showDismissButton // let user swipe to dismiss even if dismiss button is disabled - otherwise we would not receive any notification on dismiss when user clicks event
                )
                .setOngoing(
                        notificationSettings.showDismissButton && !notificationSettings.allowSwipeToSnooze
                )
                .setStyle(
                        NotificationCompat.BigTextStyle().bigText(notificationText)
                )
                .setWhen(
                        event.lastStatusChangeTime
                )
                .setShowWhen(false)
                .setSortKey(
                        sortKey
                )
                .setCategory(
                        NotificationCompat.CATEGORY_EVENT
                )
                .setGroup(NOTIFICATION_GROUP)

        val defaultSnooze0PendingIntent =
                pendingServiceIntent(ctx,
                        defaultSnoozeIntent(ctx, event.eventId, event.instanceStartTime, event.notificationId, snoozePresets[0]),
                        event.notificationId * EVENT_CODES_TOTAL + EVENT_CODE_DEFAULT_SNOOOZE0_OFFSET
                )

        val snoozeAction =
                NotificationCompat.Action.Builder(
                        R.drawable.ic_update_white_24dp,
                        ctx.getString(com.github.quarck.calnotify.R.string.snooze),
                        snoozeActivityIntent
                ).build()

        val dismissAction =
                NotificationCompat.Action.Builder(
                        R.drawable.ic_clear_white_24dp,
                        ctx.getString(com.github.quarck.calnotify.R.string.dismiss),
                        dismissPendingIntent
                ).build()

        val defaultSnooze0Action =
                NotificationCompat.Action.Builder(
                        R.drawable.ic_update_white_24dp,
                        ctx.getString(com.github.quarck.calnotify.R.string.snooze) + " " +
                                PreferenceUtils.formatSnoozePreset(snoozePresets[0]),
                        defaultSnooze0PendingIntent
                ).build()

        if (!notificationSettings.notificationOpensSnooze) {
            DevLog.debug(LOG_TAG, "adding pending intent for snooze, event id ${event.eventId}, notificationId ${event.notificationId}")
            builder.addAction(snoozeAction)
        }

        if (notificationSettings.showDismissButton) {
            builder.addAction(dismissAction)
            if (notificationSettings.allowSwipeToSnooze)
                builder.setDeleteIntent(defaultSnooze0PendingIntent)
        }
        else {
            builder.setDeleteIntent(dismissPendingIntent)
        }

        val extender =
                NotificationCompat.WearableExtender()
                        .addAction(defaultSnooze0Action)

        for ((idx, snoozePreset) in snoozePresets.withIndex()) {
            if (idx == 0)
                continue;

            if (idx >= EVENT_CODE_DEFAULT_SNOOOZE_MAX_ITEMS)
                break;

            if (snoozePreset <= 0L) {
                val targetTime = event.displayedStartTime - Math.abs(snoozePreset)
                if (targetTime - System.currentTimeMillis() < 5 * 60 * 1000L) // at least minutes left until target
                    continue;
            }

            val snoozeIntent =
                    pendingServiceIntent(ctx,
                            defaultSnoozeIntent(ctx, event.eventId, event.instanceStartTime, event.notificationId, snoozePreset),
                            event.notificationId * EVENT_CODES_TOTAL + EVENT_CODE_DEFAULT_SNOOOZE0_OFFSET + idx
                    )

            val action =
                    NotificationCompat.Action.Builder(
                            R.drawable.ic_update_white_24dp,
                            ctx.getString(com.github.quarck.calnotify.R.string.snooze) + " " +
                                    PreferenceUtils.formatSnoozePreset(snoozePreset),
                            snoozeIntent
                    ).build()

            extender.addAction(action)
        }

        if (notificationSettings.showDismissButton && notificationSettings.allowSwipeToSnooze) {
            // in this case regular "dismiss" would actually snooze
            val dismissEventAction =
                    NotificationCompat.Action.Builder(
                            R.drawable.ic_clear_white_24dp,
                            ctx.getString(com.github.quarck.calnotify.R.string.dismiss),
                            dismissPendingIntent
                    ).build()

            extender.addAction(dismissEventAction)
        }

        builder.extend(extender)

        if (notificationSettings.ringtoneUri != null
                && (currentTime - lastSoundTimestamp > Consts.MIN_INTERVAL_BETWEEN_SOUNDS)) {

            lastSoundTimestamp = currentTime

            if (notificationSettings.useAlarmStream)
                builder.setSound(notificationSettings.ringtoneUri, AudioManager.STREAM_ALARM)
            else
                builder.setSound(notificationSettings.ringtoneUri)
        }

        if (notificationSettings.vibrationOn
                && (currentTime - lastVibrationTimestamp > Consts.MIN_INTERVAL_BETWEEN_VIBRATIONS) ) {

            lastVibrationTimestamp = currentTime

            builder.setVibrate(notificationSettings.vibrationPattern)
        }
        else {
            builder.setVibrate(longArrayOf(0))
        }

        if (notificationSettings.ledNotificationOn && (!isQuietPeriodActive || !notificationSettings.quietHoursMuteLED)) {
            if (notificationSettings.ledPattern.size == 2)
                builder.setLights(notificationSettings.ledColor, notificationSettings.ledPattern[0], notificationSettings.ledPattern[1])
            else
                builder.setLights(notificationSettings.ledColor, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF)
        }

        if (notificationSettings.showColorInNotification) {
            builder.setColor(event.color.adjustCalendarColor(false))
        }

        val notification = builder.build()

        try {
            DevLog.info(ctx, LOG_TAG, "adding: notificationId=${event.notificationId}")

            notificationManager.notify(
                    event.notificationId,
                    notification
            )
        }
        catch (ex: Exception) {
            DevLog.error(ctx, LOG_TAG, "Exception: $ex, notificationId=${event.notificationId}, stack:")
            ex.printStackTrace()
        }

        if (notificationSettings.forwardToPebble && !notificationSettings.pebbleForwardRemindersOnly) {
            PebbleUtils.forwardNotificationToPebble(ctx, event.title, notificationText, notificationSettings.pebbleOldFirmware)
        }
    }


    private fun snoozeIntent(ctx: Context, eventId: Long, instanceStartTime: Long, notificationId: Int): Intent {

        val intent = Intent(ctx, SnoozeActivityNoRecents::class.java)
        intent.putExtra(Consts.INTENT_NOTIFICATION_ID_KEY, notificationId)
        intent.putExtra(Consts.INTENT_EVENT_ID_KEY, eventId)
        intent.putExtra(Consts.INTENT_INSTANCE_START_TIME_KEY, instanceStartTime)

        intent.action = Intent.ACTION_MAIN
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        return intent
    }

    private fun dismissOrDeleteIntent(ctx: Context, eventId: Long, instanceStartTime: Long, notificationId: Int): Intent {

        val intent = Intent(ctx, NotificationActionDismissService::class.java)
        intent.putExtra(Consts.INTENT_NOTIFICATION_ID_KEY, notificationId)
        intent.putExtra(Consts.INTENT_EVENT_ID_KEY, eventId)
        intent.putExtra(Consts.INTENT_INSTANCE_START_TIME_KEY, instanceStartTime)
        return intent
    }

    private fun defaultSnoozeIntent(ctx: Context, eventId: Long, instanceStartTime: Long, notificationId: Int, snoozePreset: Long): Intent {

        val intent = Intent(ctx, NotificationActionSnoozeService::class.java)
        intent.putExtra(Consts.INTENT_NOTIFICATION_ID_KEY, notificationId)
        intent.putExtra(Consts.INTENT_EVENT_ID_KEY, eventId)
        intent.putExtra(Consts.INTENT_INSTANCE_START_TIME_KEY, instanceStartTime)
        intent.putExtra(Consts.INTENT_SNOOZE_PRESET, snoozePreset)
        return intent
    }

    private fun pendingServiceIntent(ctx: Context, intent: Intent, id: Int): PendingIntent
            = PendingIntent.getService(ctx, id, intent, PendingIntent.FLAG_CANCEL_CURRENT)

    private fun pendingActivityIntent(ctx: Context, intent: Intent, id: Int): PendingIntent {

/*      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        val pendingIntent =
            TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(intent)
                .getPendingIntent(id, PendingIntent.FLAG_UPDATE_CURRENT);

        return pendingIntent */

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        return PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)

    }

    private fun removeNotification(ctx: Context, notificationId: Int) {
        val notificationManager = NotificationManagerCompat.from(ctx)
        notificationManager.cancel(notificationId)
    }

    private fun removeNotifications(ctx: Context, events: Collection<EventAlertRecord>) {
        val notificationManager = NotificationManagerCompat.from(ctx)

        for (event in events)
            notificationManager.cancel(event.notificationId)
    }

    private fun removeVisibleNotifications(ctx: Context, events: Collection<EventAlertRecord>) {
        val notificationManager = NotificationManagerCompat.from(ctx)

        for (event in events) {
            if (event.displayStatus != EventDisplayStatus.Hidden)
                notificationManager.cancel(event.notificationId)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun postNumNotificationsCollapsed(
            context: Context,
            db: EventsStorage,
            settings: Settings,
            events: List<EventAlertRecord>,
            isQuietPeriodActive: Boolean
    ) {
        DevLog.debug(LOG_TAG, "Posting collapsed view notification for ${events.size} events")

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, MAIN_ACTIVITY_NUM_NOTIFICATIONS_COLLAPSED_CODE, intent, 0)

        val numEvents = events.size

        val title = java.lang.String.format(context.getString(R.string.multiple_events), numEvents)

        val text = context.getString(com.github.quarck.calnotify.R.string.multiple_events_details)

        val bigText =
                events
                        .sortedByDescending { it.instanceStartTime }
                        .take(30)
                        .fold(
                                StringBuilder(), {
                            sb, ev ->

                            val flags =
                                    if (DateUtils.isToday(ev.displayedStartTime))
                                        DateUtils.FORMAT_SHOW_TIME
                                    else
                                        DateUtils.FORMAT_SHOW_DATE

                            sb.append("${DateUtils.formatDateTime(context, ev.displayedStartTime, flags)}: ${ev.title}\n")
                        })
                        .toString()

        val builder =
                NotificationCompat.Builder(context)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(com.github.quarck.calnotify.R.drawable.stat_notify_calendar)
                        .setPriority(Notification.PRIORITY_LOW) // always LOW regardless of other settings for regular notifications, so it is always last
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                        .setShowWhen(false)
                        .setGroup(NOTIFICATION_GROUP)

        if (settings.ledNotificationOn && (!isQuietPeriodActive || !settings.quietHoursMuteLED)) {
            if (settings.ledPattern.size == 2)
                builder.setLights(settings.ledColor, settings.ledPattern[0], settings.ledPattern[1])
            else
                builder.setLights(settings.ledColor, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF)
        }

        val notification = builder.build()

        context.notificationManager.notify(Consts.NOTIFICATION_ID_COLLAPSED, notification) // would update if already exists
    }

    private fun hideCollapsedEventsNotification(context: Context) {
        DevLog.debug(LOG_TAG, "Hiding collapsed view notification")
        context.notificationManager.cancel(Consts.NOTIFICATION_ID_COLLAPSED)
    }

    fun postDebugNotification(context: Context, notificationId: Int, title: String, text: String) {

        val notificationManager = NotificationManagerCompat.from(context)

        val appPendingIntent = pendingActivityIntent(context,
                Intent(context, MainActivity::class.java), notificationId)

        val builder = NotificationCompat.Builder(context)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.stat_notify_calendar)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(appPendingIntent)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setLights(Consts.DEFAULT_LED_COLOR, Consts.LED_DURATION_ON, Consts.LED_DURATION_OFF)

        val notification = builder.build()

        try {
            notificationManager.notify(notificationId, notification)
        }
        catch (ex: Exception) {
            DevLog.error(context, LOG_TAG, "Exception: $ex, stack:")
            ex.printStackTrace()
        }
    }

    override fun postNotificationsAutoDismissedDebugMessage(context: Context) {

        postDebugNotification(
                context,
                Consts.NOTIFICATION_ID_DEBUG0_AUTO_DISMISS,
                "DEBUG: Events dismissed",
                "DEBUG: Some events were auto-dismissed due to calendar move"
        )

        PebbleUtils.forwardNotificationToPebble(context, "DEBUG:", "Events auto-dismissed", false)
    }

    override fun postNotificationsAlarmDelayDebugMessage(context: Context, title: String, text: String) {

        postDebugNotification(
                context,
                Consts.NOTIFICATION_ID_DEBUG1_ALARM_DELAYS,
                title,
                text
        )

        PebbleUtils.forwardNotificationToPebble(context, title, text, false)
    }

    override fun postNotificationsSnoozeAlarmDelayDebugMessage(context: Context, title: String, text: String) {

        postDebugNotification(
                context,
                Consts.NOTIFICATION_ID_DEBUG2_SNOOZE_ALARM_DELAYS,
                title,
                text
        )

        PebbleUtils.forwardNotificationToPebble(context, title, text, false)
    }


    companion object {
        private const val LOG_TAG = "EventNotificationManager"

        private const val SCREEN_WAKE_LOCK_NAME = "ScreenWakeNotification"

        private const val NOTIFICATION_GROUP = "GROUP_1"

        const val EVENT_CODE_SNOOOZE_OFFSET = 0
        const val EVENT_CODE_DISMISS_OFFSET = 1
        @Suppress("unused")
        const val EVENT_CODE_DELETE_OFFSET = 2
        const val EVENT_CODE_OPEN_OFFSET = 3
        const val EVENT_CODE_DEFAULT_SNOOOZE0_OFFSET = 4
        const val EVENT_CODE_DEFAULT_SNOOOZE_MAX_ITEMS = 10
        const val EVENT_CODES_TOTAL = 16

        const val MAIN_ACTIVITY_EVERYTHING_COLLAPSED_CODE = 0
        const val MAIN_ACTIVITY_NUM_NOTIFICATIONS_COLLAPSED_CODE = 1
        const val MAIN_ACTIVITY_REMINDER_CODE = 2
        const val MAIN_ACTIVITY_GROUP_NOTIFICATION_CODE = 3
    }
}
