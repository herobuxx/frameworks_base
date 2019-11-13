/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bubbles;

import static android.app.Notification.EXTRA_MESSAGES;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED;

import static com.android.systemui.bubbles.BubbleController.canLaunchIntentInActivityView;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_EXPERIMENTS;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Common class for experiments controlled via secure settings.
 */
public class BubbleExperimentConfig {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleController" : TAG_BUBBLES;

    private static final String SHORTCUT_DUMMY_INTENT = "bubble_experiment_shortcut_intent";
    private static PendingIntent sDummyShortcutIntent;

    private static final int BUBBLE_HEIGHT = 10000;

    private static final String ALLOW_ANY_NOTIF_TO_BUBBLE = "allow_any_notif_to_bubble";
    private static final boolean ALLOW_ANY_NOTIF_TO_BUBBLE_DEFAULT = false;

    private static final String ALLOW_MESSAGE_NOTIFS_TO_BUBBLE = "allow_message_notifs_to_bubble";
    private static final boolean ALLOW_MESSAGE_NOTIFS_TO_BUBBLE_DEFAULT = false;

    private static final String ALLOW_SHORTCUTS_TO_BUBBLE = "allow_shortcuts_to_bubble";
    private static final boolean ALLOW_SHORTCUT_TO_BUBBLE_DEFAULT = false;

    /**
     * When true, if a notification has the information necessary to bubble (i.e. valid
     * contentIntent and an icon or image), then a {@link android.app.Notification.BubbleMetadata}
     * object will be created by the system and added to the notification.
     * <p>
     * This does not produce a bubble, only adds the metadata based on the notification info.
     */
    static boolean allowAnyNotifToBubble(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ALLOW_ANY_NOTIF_TO_BUBBLE,
                ALLOW_ANY_NOTIF_TO_BUBBLE_DEFAULT ? 1 : 0) != 0;
    }

    /**
     * Same as {@link #allowAnyNotifToBubble(Context)} except it filters for notifications that
     * are using {@link Notification.MessagingStyle} and have remote input.
     */
    static boolean allowMessageNotifsToBubble(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ALLOW_MESSAGE_NOTIFS_TO_BUBBLE,
                ALLOW_MESSAGE_NOTIFS_TO_BUBBLE_DEFAULT ? 1 : 0) != 0;
    }

    /**
     * When true, if the notification is able to bubble via {@link #allowAnyNotifToBubble(Context)}
     * or {@link #allowMessageNotifsToBubble(Context)} or via normal BubbleMetadata, then a new
     * BubbleMetadata object is constructed based on the shortcut info.
     * <p>
     * This does not produce a bubble, only adds the metadata based on shortcut info.
     */
    static boolean useShortcutInfoToBubble(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ALLOW_SHORTCUTS_TO_BUBBLE,
                ALLOW_SHORTCUT_TO_BUBBLE_DEFAULT ? 1 : 0) != 0;
    }

    /**
     * If {@link #allowAnyNotifToBubble(Context)} is true, this method creates and adds
     * {@link android.app.Notification.BubbleMetadata} to the notification entry as long as
     * the notification has necessary info for BubbleMetadata.
     */
    static void adjustForExperiments(Context context, NotificationEntry entry,
            boolean previouslyUserCreated) {
        Notification.BubbleMetadata metadata = null;
        boolean addedMetadata = false;

        Notification notification = entry.getSbn().getNotification();
        boolean isMessage = Notification.MessagingStyle.class.equals(
                notification.getNotificationStyle());
        boolean bubbleNotifForExperiment = (isMessage && allowMessageNotifsToBubble(context))
                || allowAnyNotifToBubble(context);

        boolean useShortcutInfo = useShortcutInfoToBubble(context);
        String shortcutId = entry.getSbn().getNotification().getShortcutId();

        boolean hasMetadata = entry.getBubbleMetadata() != null;
        if ((!hasMetadata && (previouslyUserCreated || bubbleNotifForExperiment))
                || useShortcutInfo) {
            if (DEBUG_EXPERIMENTS) {
                Log.d(TAG, "Adjusting " + entry.getKey() + " for bubble experiment."
                        + " allowMessages=" + allowMessageNotifsToBubble(context)
                        + " isMessage=" + isMessage
                        + " allowNotifs=" + allowAnyNotifToBubble(context)
                        + " useShortcutInfo=" + useShortcutInfo
                        + " previouslyUserCreated=" + previouslyUserCreated);
            }
        }

        if (useShortcutInfo && shortcutId != null) {
            // We don't actually get anything useful from ShortcutInfo so just check existence
            ShortcutInfo info = getShortcutInfo(context, entry.getSbn().getPackageName(),
                    entry.getSbn().getUser(), shortcutId);
            if (info != null) {
                metadata = createForShortcut(context, entry);
            }

            // Replace existing metadata with shortcut, or we're bubbling for experiment
            boolean shouldBubble = entry.getBubbleMetadata() != null
                    || bubbleNotifForExperiment
                    || previouslyUserCreated;
            if (shouldBubble && metadata != null) {
                if (DEBUG_EXPERIMENTS) {
                    Log.d(TAG, "Adding experimental shortcut bubble for: " + entry.getKey());
                }
                entry.setBubbleMetadata(metadata);
                addedMetadata = true;
            }
        }

        // Didn't get metadata from a shortcut & we're bubbling for experiment
        if (entry.getBubbleMetadata() == null
                && (bubbleNotifForExperiment || previouslyUserCreated)) {
            metadata = createFromNotif(context, entry);
            if (metadata != null) {
                if (DEBUG_EXPERIMENTS) {
                    Log.d(TAG, "Adding experimental notification bubble for: " + entry.getKey());
                }
                entry.setBubbleMetadata(metadata);
                addedMetadata = true;
            }
        }

        if (previouslyUserCreated && addedMetadata) {
            // Update to a previous bubble, set its flag now so the update goes
            // to the bubble.
            if (DEBUG_EXPERIMENTS) {
                Log.d(TAG, "Setting FLAG_BUBBLE for: " + entry.getKey());
            }
            entry.setFlagBubble(true);
        }
    }

    static Notification.BubbleMetadata createFromNotif(Context context, NotificationEntry entry) {
        Notification notification = entry.getSbn().getNotification();
        final PendingIntent intent = notification.contentIntent;
        Icon icon = null;
        // Use the icon of the person if available
        List<Person> personList = getPeopleFromNotification(entry);
        if (personList.size() > 0) {
            icon = personList.get(0).getIcon();
        }
        if (icon == null) {
            icon = notification.getLargeIcon() != null
                    ? notification.getLargeIcon()
                    : notification.getSmallIcon();
        }
        if (canLaunchIntentInActivityView(context, entry, intent)) {
            return new Notification.BubbleMetadata.Builder()
                    .setDesiredHeight(BUBBLE_HEIGHT)
                    .setIcon(icon)
                    .setIntent(intent)
                    .build();
        }
        return null;
    }

    static Notification.BubbleMetadata createForShortcut(Context context, NotificationEntry entry) {
        // ShortcutInfo does not return an icon, instead a Drawable, lets just use
        // notification icon for BubbleMetadata.
        Icon icon = entry.getSbn().getNotification().getSmallIcon();

        // ShortcutInfo does not return the intent, lets make a fake but identifiable
        // intent so we can still add bubbleMetadata
        if (sDummyShortcutIntent == null) {
            Intent i = new Intent(SHORTCUT_DUMMY_INTENT);
            sDummyShortcutIntent = PendingIntent.getActivity(context, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return new Notification.BubbleMetadata.Builder()
                .setDesiredHeight(BUBBLE_HEIGHT)
                .setIcon(icon)
                .setIntent(sDummyShortcutIntent)
                .build();
    }

    static ShortcutInfo getShortcutInfo(Context context, String packageName, UserHandle user,
            String shortcutId) {
        LauncherApps launcherAppService =
                (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
        if (packageName != null) {
            query.setPackage(packageName);
        }
        if (shortcutId != null) {
            query.setShortcutIds(Arrays.asList(shortcutId));
        }
        query.setQueryFlags(FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED | FLAG_MATCH_MANIFEST);
        List<ShortcutInfo> shortcuts = launcherAppService.getShortcuts(query, user);
        return shortcuts != null && shortcuts.size() > 0
                ? shortcuts.get(0)
                : null;
    }

    static boolean isShortcutIntent(PendingIntent intent) {
        return intent.equals(sDummyShortcutIntent);
    }

    static List<Person> getPeopleFromNotification(NotificationEntry entry) {
        Bundle extras = entry.getSbn().getNotification().extras;
        ArrayList<Person> personList = new ArrayList<>();
        if (extras == null) {
            return personList;
        }

        List<Person> p = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);

        if (p != null) {
            personList.addAll(p);
        }

        if (Notification.MessagingStyle.class.equals(
                entry.getSbn().getNotification().getNotificationStyle())) {
            final Parcelable[] messages = extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                for (Notification.MessagingStyle.Message message :
                        Notification.MessagingStyle.Message
                                .getMessagesFromBundleArray(messages)) {
                    personList.add(message.getSenderPerson());
                }
            }
        }
        return personList;
    }
}
