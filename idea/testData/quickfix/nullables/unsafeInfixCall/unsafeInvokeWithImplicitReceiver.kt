// "Replace with safe (?.) call" "false"
// ACTION: Convert to block body
// ACTION: Replace overloaded operator with function call
// ACTION: Wrap with '?.let { ... }' call
// ERROR: Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type String?

fun String?.foo(exec: (String.() -> Unit)) = exec<caret>()