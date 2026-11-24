package io.max.paperplay.ui.logic

/** Keeps one [V] for the owner in use, made once and handed out again until it is no longer alive or another owner takes over. */
class OnePerOwner<O : Any, V : Any> {
    private var owner: O? = null
    private var value: V? = null

    @Synchronized
    fun of(o: O, alive: (V) -> Boolean, make: () -> V): V {
        value?.let { if (owner === o && alive(it)) return it }
        return make().also { owner = o; value = it }
    }
}
