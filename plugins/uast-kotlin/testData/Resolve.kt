class A {
    fun foo() {}
    inline fun inlineFoo() {

    }
}

fun bar() {
    A().foo()
    A().inlineFoo()
    listOf(A()).forEach { println(it) } // inline from stdlib
    listOf("").joinToString() // not inline from stdlib
    listOf("").size // property from stdlib
    listOf("").last() // overloaded exntension from stdlib
}
