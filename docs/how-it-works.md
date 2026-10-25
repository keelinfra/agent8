# How agent8 works

Jenkins agents run two kinds of Java code: remoting itself (`agent.jar`), and classes the controller sends while builds run — core and plugin code for `FilePath`, `junit`, `stash`, launching processes and so on. Both are compiled for Java 17 or 21.

1. **remoting is downgraded ahead of time.** [JvmDowngrader](https://github.com/unimined/JvmDowngrader) rewrites `agent.jar` to Java 8 bytecode (or 11 or 17) and adds stand-ins for newer JDK APIs. One call it cannot handle is patched in the remoting source (`patches/`; older remoting versions do not need it).
2. **Classes from the controller are downgraded as they load**, by JvmDowngrader's Java agent (`jvmdg.jar`).
3. **The agent8 jar is a drop-in for `agent.jar`.** When started with plain `java -jar`, it starts the same Java again with both Java agents and the same JVM options.
4. **It is also a Java agent** that keeps Java serialization compatible with the controller:
   - Downgrading adds synthetic members, which changes the default `serialVersionUID` of classes that do not declare one. The controller would then reject every call. The agent pins the UID the controller computes, before downgrading. Records get UID 0 unless they declare one.
   - It adds `java.util.CollSer`, the serial form of `List.of`, `Set.of`, `Map.of` and `Stream.toList()`, which Java 8 does not have. On Java 9 to 15 it teaches the JDK's own `CollSer` the form `Stream.toList()` uses since Java 16.
   - On OpenJ9 it adds a stand-in for HotSpot's internal `ClassLoader$ParallelLoaders`, which JvmDowngrader's `ClassLoader` stubs need.
   - It fixes JvmDowngrader stubs that behave differently from the JDK: `InputStream.readNBytes(int)` allocated the full length up front, so `readNBytes(Integer.MAX_VALUE)`, which JGit uses to read Git config files, failed on Java 8.
   - It keeps signed jars working. JvmDowngrader drops the signers of every class loaded after it starts, so a signed jar that was already in use before that fails with "signer information does not match". Security providers are such jars: SunPKCS11 / OracleUcrypto on Solaris (the first TLS or digest call failed), and IBMJCE on IBM Java, the only provider of the `JKS` key store there (remoting could not build its TLS context, so the agent never connected). agent8 and the classes the controller sends are not signed, so agent8 turns that part of JvmDowngrader off.
