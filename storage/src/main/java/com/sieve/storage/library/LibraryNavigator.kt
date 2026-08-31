package com.sieve.storage.library

/** Pure back-stack bounded by the tree root. */
class LibraryNavigator(private val rootDocumentId: String) {
    private val stack = ArrayDeque<String>().apply { addLast(rootDocumentId) }
    val current: String get() = stack.last()
    fun canGoUp(): Boolean = stack.size > 1
    fun enter(childDocumentId: String) { stack.addLast(childDocumentId) }
    fun up() { if (stack.size > 1) stack.removeLast() }
    fun reset() { while (stack.size > 1) stack.removeLast() }
}
