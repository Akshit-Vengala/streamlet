allprojects {
    if (path == ":library") {
        afterEvaluate {
            tasks.register("exportJvmRuntimeClasspath") {
                val cp = configurations.findByName("jvmRuntimeClasspath")
                val out = file("${rootDir}/../spike/libs")
                doLast {
                    out.mkdirs()
                    cp!!.resolve().forEach { it.copyTo(File(out, it.name), overwrite = true) }
                }
            }
        }
    }
}
