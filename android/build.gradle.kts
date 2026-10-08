// Root build file. Plugins are declared here (with `apply false`) so that the
// versions are resolved once for the whole build and the `:app` module can
// apply them without repeating a version string.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}