// RUN_PIPELINE_TILL: FRONTEND

package foo.bar

import org.bezsahara.customindy.SomeAnnotation

@SomeAnnotation
fun test() {
    val s = MyClass().foo()
    s.<!UNRESOLVED_REFERENCE!>inc<!>() // should be an error
}
