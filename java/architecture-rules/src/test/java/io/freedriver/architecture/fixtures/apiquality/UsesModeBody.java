package io.freedriver.architecture.fixtures.apiquality;

/** A method body that names an annotation's member type is a real dependency. */
public class UsesModeBody {
    void m() {
        Marked.Mode mode = Marked.Mode.ON;
    }
}
