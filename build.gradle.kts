plugins {
    id("java")
    id("application")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Dependencia de JitPack correctamente escrita para Kotlin DSL
    implementation("com.github.JRJimenezReyes:entrada:1.0.3")
}

application {
    // Clase principal: permite ejecutar la aplicación con "gradlew run"
    mainClass.set("org.example.GestionDepartamentos")
}

tasks.withType<JavaCompile> {
    // Para que las tildes y la "ñ" de los mensajes se compilen bien en cualquier sistema
    options.encoding = "UTF-8"
}

tasks.named<JavaExec>("run") {
    // Necesario para poder leer del teclado al ejecutar con "gradlew run"
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
