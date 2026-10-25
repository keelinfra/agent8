# Licenses

[MIT](../LICENSE) for this project's own code. The agent8 jars also contain:

- Jenkins remoting (MIT), downgraded and with one patched class;
- ASM (BSD-3-Clause);
- classes from [JvmDowngrader](https://github.com/unimined/JvmDowngrader) (LGPL-2.1), shaded into the jar by `build.sh`: the stand-ins for newer JDK APIs that the downgraded remoting calls. They are unmodified apart from the package rename (at run time agent8 replaces one stub method, `InputStream.readNBytes(int)`, see `src/io/keelinfra/agent8/StubFixes.java`); their source is in the JvmDowngrader repository at the version `build.sh` pins (2.0.1), and `build.sh` rebuilds the jars from it, so you can swap in your own build of JvmDowngrader.

`jvmdg.jar` is JvmDowngrader 2.0.1 itself, distributed unmodified under the [LGPL-2.1](https://github.com/unimined/JvmDowngrader/blob/main/LICENSE.md).
