// "Change to 'return@forEach'" "true"
// ACTION: Change to 'return@foo'
// ACTION: Introduce local variable
// ERROR: The integer literal does not conform to the expected type Unit
// ERROR: The integer literal does not conform to the expected type Unit
// ERROR: The integer literal does not conform to the expected type Unit

// WITH_RUNTIME

fun foo(f:()->Int){}

fun bar() {

    foo {
        listOf(1).forEach {
            return<caret> 1
        }
        return@foo 1
    }
}