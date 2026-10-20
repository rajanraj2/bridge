plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    api(libs.protobuf.java)
    testImplementation(libs.junit)
    testImplementation(libs.protobuf.java.util)
}
