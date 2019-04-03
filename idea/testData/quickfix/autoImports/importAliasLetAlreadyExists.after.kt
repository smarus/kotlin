// "Import" "true"
// ERROR: Unresolved reference: let
// ERROR: Unresolved reference: it
// WITH_RUNTIME

import kotlin.let as let1

fun main() {
    1.let1 {
        println(it)
    }
}