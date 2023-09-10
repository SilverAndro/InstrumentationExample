plugins {
    id("java")
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {

}

tasks {
    jar {
        manifest.attributes(
            "Agent-Class" to "com.example.instrumentation_example_mod.InstrumentationGetter",
            "Can-Redefine-Classes" to true,
            "Can-Retransform-Classes" to true
        )
    }
}
