// !WITH_NEW_INFERENCE
// !DIAGNOSTICS: -UNUSED_VARIABLE

fun <T> foo(array: Array<Array<T>>): Array<Array<T>> = array

fun test(array: Array<Array<out Int>>) {
    <!OI;TYPE_INFERENCE_PARAMETER_CONSTRAINT_ERROR!>foo<!>(<!NI;NEW_INFERENCE_ERROR!>array<!>)

    val f: Array<out Array<out Int>> = <!OI;TYPE_INFERENCE_PARAMETER_CONSTRAINT_ERROR!>foo<!>(<!NI;NEW_INFERENCE_ERROR, OI;TYPE_MISMATCH!>array<!>)
}
