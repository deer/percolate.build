File argfile = new File(basedir, "target/percolate-classify-compile.args")
assert argfile.exists() : "expected argfile at ${argfile}"

String content = argfile.text
assert content.contains("--module-path") : "expected --module-path in argfile:\n${content}"
assert content.contains("assertj-core") : "expected assertj-core jar on module-path:\n${content}"

return true
