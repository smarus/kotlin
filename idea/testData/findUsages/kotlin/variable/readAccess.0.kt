// PSI_ELEMENT: org.jetbrains.kotlin.psi.JetProperty
// OPTIONS: usages
// OPTIONS: skipWrite
fun foo() {
    var <caret>v = 1
    (@X v) = 2
    print(v)
    ++ @X v
    v--
    print(-v)
    v += 1
    (v) -= 1
}
