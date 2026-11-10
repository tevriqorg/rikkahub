package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.P14_ACTION_CONNECTION_SETTINGS

/** Reuse existing workspace Activities (and their Compose state) in the current task. */
internal object P14WorkspaceNavigation {
    const val ACTION_OPEN_INBOX = "me.rerere.rikkahub.action.P14_OPEN_INBOX"
    const val ACTION_OPEN_AI = "me.rerere.rikkahub.action.P14_OPEN_AI_WORKBENCH"

    fun inboxIntent(context: Context): Intent =
        Intent(context, P14EntryActivity::class.java).apply {
            action = ACTION_OPEN_INBOX
            putExtra(P14EntryActivity.EXTRA_FROM_LAUNCHER, true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun aiIntent(context: Context): Intent =
        Intent(context, RouteActivity::class.java).apply {
            action = ACTION_OPEN_AI
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun settingsIntent(context: Context): Intent =
        Intent(context, RouteActivity::class.java).apply {
            action = P14_ACTION_CONNECTION_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun openConnectionSettings(context: Context) = context.startActivity(settingsIntent(context))
    fun openInbox(context: Context) = context.startActivity(inboxIntent(context))
    fun openAi(context: Context) = context.startActivity(aiIntent(context))
}
