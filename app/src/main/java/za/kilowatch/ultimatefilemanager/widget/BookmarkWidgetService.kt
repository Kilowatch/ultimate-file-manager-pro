package za.kilowatch.ultimatefilemanager.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * RemoteViewsService that supplies the BookmarkWidgetFactory to the widget's ListView.
 *
 * The system calls onGetViewFactory() when it needs to populate the widget list.
 * The service must be declared in the manifest with
 * android:permission="android.permission.BIND_REMOTEVIEWS".
 */
class BookmarkWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return BookmarkWidgetFactory(applicationContext, intent)
    }
}
