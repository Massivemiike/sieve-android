package com.sieve.queue.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes the notification's Pause/Resume/Cancel actions to the QueueRepository. */
class QueueCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(QueueNotification.EXTRA_ACTION) ?: return
        val id = intent.getStringExtra(QueueNotification.EXTRA_ITEM) ?: return
        val repo = QueueRepository.get(context)
        when (NotifAction.valueOf(action)) {
            NotifAction.PAUSE -> repo.pause(id)
            NotifAction.RESUME -> repo.resume(id)
            NotifAction.CANCEL -> repo.cancel(id)
        }
    }
}
