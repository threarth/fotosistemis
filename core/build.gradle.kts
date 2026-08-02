plugins {
    id("org.jetbrains.kotlin.jvm")
}

/*
 * Domain and data layer, shared by every front end.
 *
 * Deliberately free of Android types: the same code has to serve the phone
 * app and a desktop build. Anything platform specific reaches this module
 * through the interfaces in the port package.
 */
kotlin {
    jvmToolchain(11)
}

dependencies {
    testImplementation(libs.junit)
}
