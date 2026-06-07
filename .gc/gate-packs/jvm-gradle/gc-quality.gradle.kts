plugins {
    java
    checkstyle
    pmd
    jacoco
}

checkstyle {
    toolVersion = "10.21.1"
    configFile = rootProject.file(".gc/gate-packs/jvm-gradle/checkstyle.xml")
}

pmd {
    toolVersion = "7.10.0"
    ruleSetFiles = files(rootProject.file(".gc/gate-packs/jvm-gradle/pmd-ruleset.xml"))
    ruleSets = emptyList()
}
