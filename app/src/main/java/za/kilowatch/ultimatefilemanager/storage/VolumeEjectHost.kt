package za.kilowatch.ultimatefilemanager.storage

/**
 * Identifies the volume being ejected, independently of how any container displays or
 * addresses it.
 *
 * [mountPath] is the canonical filesystem path the volume is mounted at (e.g.
 * `/storage/7DE2-1219`) and is the value every release stage matches against — the
 * descriptor sweep, the memory-map scan, the cache prefix sweeps and the watcher stops all
 * take it. [uuid] is the volume's stable identifier (`7DE2-1219`) and is what the re-entrancy
 * guard and the elevated unmount command key on.
 */
data class VolumeIdentity(
    val mountPath: String,
    val uuid: String,
    val label: String,
    val isRemovable: Boolean
) {
    init {
        require(uuid.isNotBlank()) { "VolumeIdentity.uuid must not be blank" }
    }

    /**
     * Normalized form of [mountPath] for prefix matching: no trailing separator, so
     * `"" + separator` can be appended to build a segment boundary without ever producing
     * a doubled separator.
     */
    val normalizedMountPath: String
        get() = mountPath.trimEnd('/', '\\')

    companion object {
        /**
         * Builds the identity for [item], so all four containers derive it the same way.
         *
         * The uuid is taken from the mount path first (`/storage/7DE2-1219` -> `7DE2-1219`),
         * because that is the form `vold` and `sm list-volumes` report and the form the
         * re-entrancy guard needs to key on. It falls back to the item id with any
         * `unmounted_` prefix stripped — the same normalization `UsbEjectManager` already
         * applies when resolving a volume id — and only then to the label, so the non-blank
         * requirement below cannot be tripped by a mock or not-yet-mounted volume.
         */
        fun from(item: StorageItem): VolumeIdentity {
            val path = item.mountPath.trimEnd('/', '\\')
            val uuid = path.removePrefix("/storage/").substringBefore('/').trim()
                .ifBlank { item.id.removePrefix("unmounted_").trim() }
                .ifBlank { item.label.trim() }
                .ifBlank { path }
            return VolumeIdentity(
                mountPath = path,
                uuid = uuid,
                label = item.label,
                isRemovable = item.isRemovable
            )
        }
    }
}

/**
 * Implemented by each container that can eject a volume, so the release orchestrator can
 * navigate the user out of a volume without knowing what kind of container it is talking to.
 *
 * The four containers have genuinely different exits — the standalone browser finishes, Tab
 * mode closes a tab and exits to the Main Menu if it was the last one, Twin Window collapses
 * a pane, and the Main Menu tile has nothing to leave at all. `UsbEjectManager` must not learn
 * about tabs, panes or activities to handle that, so each container supplies its own
 * behaviour behind this interface.
 */
interface VolumeEjectHost {

    /**
     * Leave the volume being ejected before the unmount is issued (FR-05): close viewers, tabs
     * or panes that are inside the volume, and move the UI off its path.
     *
     * **Must be idempotent** — the release phase is failure-isolated and a retry must not
     * throw or double-navigate.
     *
     * **Must call [onDone]** exactly once, on the main thread, whether or not there was
     * anything to do. The orchestrator wraps the call in a timeout so a container that never
     * calls back cannot hang the eject, but a host that simply returns without calling
     * [onDone] will always pay that timeout — so call it on the empty path too.
     *
     * A host whose target can change while the eject is running — Tab mode, where the active
     * tab may be switched from under the flow — must capture the target when the eject is
     * *started* and remember it, rather than re-reading "the current tab" here. Re-reading
     * would navigate out of whatever tab happens to be focused instead of the one whose volume
     * is being removed.
     */
    fun navigateOutOfVolume(onDone: () -> Unit)
}
