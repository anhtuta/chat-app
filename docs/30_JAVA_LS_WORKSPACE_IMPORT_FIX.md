# Java LS Workspace Import Fix

## Current Problem

Cursor showed `Project configuration is not up-to-date with pom.xml, requires an update` on Java `pom.xml` files, and stopped recognizing the three Maven projects:

- `chat-app-backend` (artifact `chat-app`)
- `bot-simulator`
- `media-processing`

Moreover, Java extensions stopped recognizing the Java packages in the three projects. We cannot navigate to classes by Cmd+Click.

Root causes:

1. An empty `java.project.sourcePaths: []` override in `.vscode/settings.json` conflicted with Maven’s source layout.
2. A temporary invalid root aggregator `pom.xml` (`chatapp-workspace`) left stale Eclipse/JDT project metadata after it was deleted.
3. The Java Language Server was running on embedded JRE 21 while these projects target JDK 25 (Micronaut’s Maven plugin needs class file version 69).
4. Multi-root workspace folders (repo root `.` plus nested Java folders) could double-import the same Maven projects.

## Recommendation

Clean the Java LS cache, remove conflicting IDE overrides, configure JDK 25, and keep multi-root import exclusions in `chat-app.code-workspace`. Prefer opening the repo via `chat-app.code-workspace`.

## Implementation details

### What changed

- Removed `java.project.sourcePaths: []` from `.vscode/settings.json`.
- Deleted the invalid root aggregator `pom.xml`.
- Cleared Cursor Java LS cache by renaming/removing:

  `~/Library/Application Support/Cursor/User/workspaceStorage/<workspace-id>/redhat.java`

  (equivalent to **Java: Clean Java Language Server Workspace → Restart and delete**).

- Set JDK 25 at `/Users/mac/Programs/jdk-25.0.1.jdk/Contents/Home` via:
  - `java.jdt.ls.java.home`
  - `java.configuration.runtimes` (`JavaSE-25`, default)
- In `chat-app.code-workspace`, added `java.import.exclusions` for the nested Java folders so the root `.` folder does not re-import projects that are already separate workspace roots.

### Why it changed

The IDE was stuck on stale Maven/JDT metadata and the wrong JVM, so project import and classpath sync failed even though `mvn validate` succeeded for all three modules.

## Lesson (look back here)

- Do not set `java.project.sourcePaths` unless you intentionally override Maven/Gradle layout.
- Do not leave a root aggregator `pom.xml` in a multi-root workspace unless it is valid and intentional.
- Java LS cache lives outside the repo under Cursor `workspaceStorage`; cleaning it is often required after bad Maven imports.
- For JDK 25 projects, the language server itself should run on JDK 25, not only the project runtime.
