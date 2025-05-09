import org.jetbrains.dokka.gradle.DokkaTaskPartial
import java.net.*

dependencies {
    api(project(":kotlinx-coroutines-reactive"))
    testImplementation("org.reactivestreams:reactive-streams-tck:${version("reactive_streams")}")
    api("io.reactivex.rxjava3:rxjava:${version("rxjava3")}")
}

tasks.withType(DokkaTaskPartial::class) {
    dokkaSourceSets.configureEach {
        externalDocumentationLink {
            url = URL("https://reactivex.io/RxJava/3.x/javadoc/")
            packageListUrl = projectDir.toPath().resolve("package.list").toUri().toURL()
        }
    }
}

val testNG by tasks.registering(Test::class) {
    useTestNG()
    reports.html.outputLocation = file("${layout.buildDirectory.get()}/reports/testng")
    include("**/*ReactiveStreamTckTest.*")
    // Skip testNG when tests are filtered with --tests, otherwise it simply fails
    onlyIf {
        filter.includePatterns.isEmpty()
    }
    doFirst {
        // Classic gradle, nothing works without doFirst
        println("TestNG tests: ($includes)")
    }
}

val test by tasks.getting(Test::class) {
    dependsOn(testNG)
    reports.html.outputLocation = file("${layout.buildDirectory.get()}/reports/junit")
}
