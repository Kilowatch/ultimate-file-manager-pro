package za.kilowatch.ultimatefilemanager.storage

/**
 * In-memory store for the batch-rename item list.
 *
 * The batch-rename dialog used to receive its items via fragment `arguments`
 * (`putParcelableArray("items", ...)`). Fragment arguments are re-serialized
 * into the host Activity's saved-instance-state parcel on every
 * `onSaveInstanceState`, and that parcel is sent to the system server on
 * `activityStopped`. When the user selects a large number of files (e.g.
 * "Select All" in a big folder, thousands of items each carrying a path and
 * network-share context), the arguments bundle can exceed the Binder
 * transaction limit and crash with
 * `android.os.TransactionTooLargeException: data parcel size ... bytes`
 * at `android.app.ActivityClient.activityStopped`.
 *
 * The items now travel through this cache instead: the caller stores the list
 * and only a small cache key string is put into fragment `arguments`. The
 * dialog peeks the list in `onCreate` and removes the entry once the dialog is
 * truly finished (not during a configuration change, so rotation keeps working).
 *
 * Uses a [java.util.concurrent.ConcurrentHashMap] so rapid back-to-back opens
 * do not race.
 */
object BatchRenameItemsCache {
    private val store = java.util.concurrent.ConcurrentHashMap<String, List<BatchRenameItem>>()

    /** Store [items] and return the unique key to pass via the fragment arguments. */
    fun put(items: List<BatchRenameItem>): String {
        val key = System.currentTimeMillis().toString() + "_" + items.hashCode()
        store[key] = items
        return key
    }

    /**
     * Retrieve the items for [key] without removing them (the dialog may be
     * re-created on a configuration change and needs to read them again).
     * Returns `null` if [key] is blank or no entry exists (e.g. process was
     * restarted, in which case the dialog falls back to closing itself).
     */
    fun peek(key: String): List<BatchRenameItem>? {
        if (key.isBlank()) return null
        return store[key]
    }

    /** Remove the entry for [key] once the dialog is finished with it. */
    fun remove(key: String) {
        if (key.isBlank()) return
        store.remove(key)
    }
}
