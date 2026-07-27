File marker = new File(basedir, "target/exec-ran.marker")
assert marker.exists() : "expected marker file at ${marker}, exec goal did not run the forked JVM"

String content = marker.text
assert content.contains("module=it.exec.basic") : "expected forked process to run as named module it.exec.basic:\n${content}"

return true
