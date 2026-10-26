# Hot-Reload Agent

A standalone Java agent that hot-reloads compiled classes into a running JVM
without restarting the application. Includes targeted reloaders for:

* **Spring beans** (`@Service`, `@Controller`, `@Repository`, `@Component`)
* **Spring configuration** (`application.properties` / `application.yml`)
* **Hibernate entities** (`@Entity` — cache eviction)

---

## Project layout

```
hot-reload-agent/
├── pom.xml
├── README.md
├── agent/                          # Core Java agent (packaging: jar)
│   ├── pom.xml
│   ├── src/main/java/com/example/hotreload/
│   │   ├── HotReloadAgent.java              # JVM agent entry (premain + agentmain)
│   │   ├── HotReloadClassFileTransformer.java
│   │   ├── ReloadService.java               # Watches target/classes for .class changes
│   │   ├── HotReloadClassFileTransformer.java
│   │   ├── spring/
│   │   │   ├── SpringBeanReloader.java      # Evicts + recreates Spring beans
│   │   │   ├── SpringConfigReloader.java    # Reloads application.properties
│   │   │   ├── SpringAgentIntegration.java  # @Component: wires Spring reloaders
│   │   │   └── SpringStructuralIntegration.java # @Component + BeanPostProcessor
│   │   ├── hibernate/
│   │   │   ├── HibernateAgentIntegration.java # @Component: wires SessionFactory
│   │   │   └── HibernateEntityReloader.java # Evicts @Entity from cache
│   │   ├── structural/
│   │   │   ├── StructuralClassLoader.java   # Custom URLClassLoader for isolated versions
│   │   │   ├── StructuralHotSwapManager.java # Orchestrates structural hot-swap
│   │   │   ├── HotSwapProxy.java            # Indirection proxy for transparent reload
│   │   │   └── ByteBuddyClassRewriter.java   # Bytecode transformation helper
│   │   ├── instrument/
│   │   │   ├── HotReloadDispatcher.java     # Runtime dispatch for bytecode-instrumented calls
│   │   │   ├── ByteBuddyAgentTransformer.java # Installs ByteBuddy AgentBuilder
│   │   │   ├── ClassFileInstrumenter.java   # Build-time bytecode instrumentation
│   │   │   └── InstrumentingMethodVisitor.java # ASM transformer for .class files
│   │   ├── remote/
│   │   │   └── RemoteAttachClient.java      # Dynamically attach agent to a running JVM
│   │   └── build/
│   │       └── SourceCompileWatcher.java    # Auto-compiles .java on change into watched dir
│   ├── src/native/jvmti_agent.c             # Native JVMTI agent (C)
│   └── src/test/...                         # 31 tests across 8 classes
├── maven-plugin/                          # Maven plugin for hot-reload
│   ├── pom.xml
│   └── src/main/java/com/example/hotreload/maven/plugin/
│       ├── AttachMojo.java      # Attaches agent to running JVM by PID
│       └── WatchMojo.java       # Watches sources and auto-compiles on change
├── gradle-plugin/                        # Gradle plugin for hot-reload
│   ├── pom.xml
│   └── src/main/java/com/example/hotreload/gradle/plugin/
│       └── HotReloadPlugin.java  # Gradle plugin with hotReloadWatch / hotReloadAttach tasks
├── intellij-plugin/                       # IntelliJ plugin skeleton
│   ├── pom.xml
│   ├── src/main/resources/META-INF/plugin.xml
│   └── src/main/java/com/example/hotreload/intellij/HotReloadAction.java
├── eclipse-plugin/                        # Eclipse plugin skeleton
│   ├── pom.xml
│   ├── src/main/resources/META-INF/MANIFEST.MF
│   ├── src/main/resources/plugin.xml
│   └── src/main/java/com/example/hotreload/eclipse/
│       ├── HotReloadActivator.java
│       ├── HotReloadAction.java
│       └── HotReloadBuilder.java
└── target/
    ├── hot-reload-agent-1.0.0-SNAPSHOT.jar  # Built agent jar
    └── libjvmti_agent.so                  # Built native JVMTI agent
```

---

## Build

```bash
cd /home/devlin/IdeaProjects/hot-reload-agent
mvn clean package
```

This produces:

| Artifact | Path | Purpose |
|----------|------|---------|
| Agent JAR | `target/hot-reload-agent-1.0.0-SNAPSHOT.jar` | Java agent with ByteBuddy shaded in |
| Native agent | `target/libjvmti_agent.so` | JVMTI native agent (Linux x86_64) |

The native library is compiled automatically during `process-resources` via the `exec-maven-plugin`, so it survives `mvn clean`.

---

## Run your application with the agent

### Without bytecode instrumentation (basic hot-reload)

```bash
java -javaagent:target/hot-reload-agent-1.0.0-SNAPSHOT.jar=target/classes \
     -cp target/classes:libs/* com.example.myapp.Main
```

### With ByteBuddy bytecode instrumentation (method-call interception)

The agent includes a build-time bytecode instrumenter (`ClassFileInstrumenter`)
that instruments compiled classes so that field access and method calls route
through `HotReloadDispatcher`. To instrument your application's classes:

```bash
java -cp target/hot-reload-agent-1.0.0-SNAPSHOT.jar:target/classes \
     com.example.hotreload.instrument.ClassFileInstrumenter target/classes

java -javaagent:target/hot-reload-agent-1.0.0-SNAPSHOT.jar=target/classes \
     -cp target/classes:libs/* com.example.myapp.Main
```

The agent argument (`=target/classes`) is the directory the watcher scans for
changed `.class` files. Override with `-Dhot.reload.dir=/path/to/classes`.

---

### With dynamic remote attach (no -javaagent startup)

Instead of starting the application with `-javaagent`, you can attach the
agent to an already-running JVM at runtime using `RemoteAttachClient`:

```bash
# Attach to a running JVM by PID
java -cp target/hot-reload-agent-1.0.0-SNAPSHOT.jar \
     com.example.hotreload.remote.RemoteAttachClient 12345 \
     target/hot-reload-agent-1.0.0-SNAPSHOT.jar target/classes
```

This is the foundation for remote server support — the agent can be loaded
into a running JVM without restarting it. Note: the target JVM must not have
`-XX:+DisableAttachMechanism` enabled, and this is a local attach (true
remote attach over SSH/HTTP requires an additional listener, not implemented
in this starter).

---

### With automatic source compilation

The `SourceCompileWatcher` watches your `.java` source files and compiles them
on every change, writing `.class` files directly into the directory the
agent's `ReloadService` is already watching. This closes the build/watch
loop — no manual `mvn compile` required:

```bash
# Run alongside your hot-reloaded application
java -cp target/hot-reload-agent-1.0.0-SNAPSHOT.jar:target/classes \
     com.example.hotreload.build.SourceCompileWatcher \
     src/main/java target/classes
```

Now edit any `.java` file → it auto-compiles → `ReloadService` detects the
new `.class` → the class is hot-reloaded into the running JVM.

---

## Build tool plugins

### Maven plugin

The `hot-reload-maven-plugin` provides two goals:

**Attach to a running JVM:**
```bash
mvn com.example.hotreload:hot-reload-maven-plugin:1.0.0-SNAPSHOT:attach \
  -DhotReload.pid=12345 \
  -DhotReload.agentJar=agent/target/agent-1.0.0-SNAPSHOT.jar \
  -DhotReload.watchDir=agent/target/classes
```

**Watch sources and auto-compile on change:**
```bash
mvn com.example.hotreload:hot-reload-maven-plugin:1.0.0-SNAPSHOT:watch \
  -DhotReload.sourceDir=agent/src/main/java \
  -DhotReload.outputDir=agent/target/classes
```

### Gradle plugin

Apply the plugin in your `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'com.example.hotreload' version '1.0.0-SNAPSHOT'
}
```

Tasks:

```bash
# Watch Java sources and auto-compile on change
./gradlew hotReloadWatch

# Attach agent to a running JVM
./gradlew hotReloadAttach \
  -PhotReload.pid=12345 \
  -PhotReload.agentJar=../agent/target/agent-1.0.0-SNAPSHOT.jar \
  -PhotReload.watchDir=build/classes/java/main
```

---

## IDE plugins

### IntelliJ IDEA plugin (skeleton)

The `intellij-plugin` module provides an IntelliJ Platform plugin skeleton with a
"Compile and Hot-Reload" action bound to `Ctrl+Shift+R`.

**To build and install:**
1. Open `intellij-plugin/` in IntelliJ IDEA
2. Build the plugin via Gradle (`gradle-intellij-plugin` is configured)
3. Install the produced `.zip` plugin into IntelliJ

The action triggers compilation via `CompilerManager`. Wiring it to push compiled
class bytes to a running agent is left as an integration step.

### Eclipse plugin (skeleton)

The `eclipse-plugin` module provides an Eclipse plugin skeleton with:
- `HotReloadActivator` — plugin lifecycle
- `HotReloadAction` — "Compile and Hot-Reload" action on the Build menu
- `HotReloadBuilder` — incremental builder that detects changed `.java` files

**To build and install:**
1. Import `eclipse-plugin/` into Eclipse as an existing Maven project
2. Build with Maven or Tycho
3. Deploy the produced JAR to your Eclipse `dropins/` folder

Wiring the builder to push compiled class bytes to a running agent is left as
an integration step.

---

## How reloading works

1. You compile a changed class (IDE or `mvn compile`) → new `.class` appears
   in `target/classes`.
2. `ReloadService` (WatchService) detects the file change and reads the new
   bytecode.
3. `HotReloadAgent.reloadClass()` first tries
   `Instrumentation.redefineClasses(...)` for method-body changes — no restart.
4. If `redefineClasses` throws (structural change: add/remove method/field),
   the agent falls back to `StructuralClassLoader`, which loads the new class
   version in an isolated classloader, creates a new instance, and updates
   `HotReloadAgent` proxy instances so callers transparently see the new
   version.
5. Listeners are notified:
   - **SpringBeanReloader**: destroys stale Spring singleton beans and
     re-acquires them via `getBean()`. **No `context.refresh()`.**
   - **SpringConfigReloader**: reloads `application.properties` into the
     environment.
   - **HibernateEntityReloader**: evicts `@Entity` from 2nd-level + collection
     caches.
   - **StructuralHotSwapManager**: loads the new class version in a custom
     classloader, creates a new instance, and updates `HotProxy` instances so
     all callers transparently see the new version.

---

## JVM Instrumentation limitations

`java.lang.instrument` has hard limits that apply to **all JVMs** (JBR,
Temurin, OpenJDK):

| Operation | Supported? |
|-----------|-----------|
| Change method bodies | ✅ Yes — via `redefineClasses` |
| Add methods | ❌ No — JVM rejects: "attempted to add a method" |
| Remove methods | ❌ No — JVM rejects |
| Add/remove fields | ❌ No — JVM rejects |
| Change method signatures | ❌ No — JVM rejects |

**Structural changes** (add/remove methods/fields) are handled via the
custom classloader approach (`StructuralClassLoader` + `HotSwapProxy`),
which loads a new version of the class in isolation and swaps instances via
proxy indirection.

### What this means:
- **Method-body edits** (fixing logic inside a method) → hot-reloaded live ✅
- **Adding a new method to a class** → hot-reloaded via custom classloader ✅
- **Adding a new field to a Spring `@Entity`** → hot-reloaded via custom classloader ✅
- **Direct field access on the old instance** → still points to old class ⚠️
- **`@Value` re-injection into live beans** ❌ Needs Spring Cloud `@RefreshScope`

---

## JVMTI Native Agent (optional)

A native JVMTI agent is included at `src/native/jvmti_agent.c`. It provides
heap inspection capabilities:

| Capability | Status |
|-----------|--------|
| Get all loaded classes | ✅ |
| Heap iteration (find all instances) | ✅ |
| Redefine classes with structural changes | ❌ Same JVM limitation as Instrumentation |
| Thread-safe redefinition | ✅ (RawMonitor) |

Build (Linux):
```bash
gcc -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
    -o libjvmti_agent.so src/native/jvmti_agent.c
```

Load:
```bash
java -agentpath:/path/to/libjvmti_agent.so \
     -javaagent:target/hot-reload-agent-1.0.0-SNAPSHOT.jar=target/classes \
     -cp target/classes:libs/* com.example.myapp.Main
```

The JVMTI agent is **optional** — the agent works without it using only
`java.lang.instrument` + `StructuralClassLoader` + `HotSwapProxy`.

---

## Limitations

| Area | What works | What doesn't work |
|------|-----------|-------------------|
| Method bodies | ✅ Changed via `redefineClasses` | — |
| Add/remove methods | ✅ Via `StructuralClassLoader` + `HotSwapProxy` | Direct field reference to old instance still points to old class |
| Add/remove fields | ✅ Via `StructuralClassLoader` + `HotSwapProxy` | Direct field reference to old instance still points to old class |
| Spring beans | ✅ Evict + recreate single bean via `SpringBeanReloader` | `@Value` re-injection (needs `@RefreshScope`) |
| Spring config | ✅ Reload property source via `SpringConfigReloader` | — |
| Hibernate entities | ✅ Evict from 2nd-level/collection caches | Remap `@Entity` fields/associations (needs SessionFactory rebuild) |
| JDK classes | ❌ Never reloaded | — |
| Remote servers | ⚠️ Local dynamic attach via `RemoteAttachClient` (JVM PID); no remote over SSH/HTTP | — |
| Build / IDE integration | ⚠️ Maven + Gradle plugins available; `SourceCompileWatcher` auto-compiles `.java`; IntelliJ/Eclipse plugin skeletons exist but need IDE SDK to build | — |

## Next steps to make it production-grade

1. **IDE plugin completion** — IntelliJ/Eclipse plugin skeletons exist; wire them
   to push compiled class bytes to a running agent and build with the respective
   IDE SDK.
2. **Maven/Gradle plugin completion** — plugins exist with `attach` and `watch`
   goals/tasks; tighter integration with `compile` phase (auto-trigger reload on
   successful compilation) is still pending.
3. **ByteBuddy programmatic rewrite** — wire `ByteBuddyClassRewriter` to
   generate structural changes programmatically without javac.
4. **CGLIB proxy support** — currently only interface-based beans can be
   proxied; add support for classes without interfaces.
5. **True remote server support** — `RemoteAttachClient` handles local
   attach by PID; add an agent-side listener for remote attach over SSH/HTTP.

---

## License

MIT-style: use freely.

---

## Testing

Run the full test suite:

```bash
mvn test
```

The suite covers 31 tests across 8 classes:

| Test class | What it covers |
|---|---|
| `HotReloadAgentTest` | Agent instrumentation state, `findLoadedClass`, no-op reload |
| `HotSwapProxyTest` | Proxy delegation, instance swapping, interface checks |
| `StructuralClassLoaderTest` | Loading classes from `.class` files, version counters |
| `HotReloadDispatcherTest` | Field get/set, method invoke, static invoke |
| `SpringBeanReloaderTest` | `@Service`/`@Controller`/`@Repository`/`@Component` detection |
| `HibernateEntityReloaderTest` | `@Entity` annotation detection |
| `SourceCompileWatcherTest` | Auto-compilation of `.java` sources to `.class` |
| `RemoteAttachClientTest` | Argument validation |