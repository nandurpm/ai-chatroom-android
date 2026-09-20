# Gradle wrapper source notice

Bundled wrapper sources and launch scripts originate from https://github.com/gradle/gradle at tag v8.11.1.
Copyright the original Gradle authors. Licensed under Apache License 2.0; see GRADLE-LICENSE.

Source directories: platforms/core-runtime/wrapper-main, wrapper-shared, cli and files.
The standalone wrapper JAR was compiled from the included source with Java 17 targeting Java 8.
The NonNullApi annotation and import were removed from WrapperDistributionUrlConverter.java.
No runtime logic was changed. This is a source-built JAR, not Gradle's official prebuilt binary.

To reproduce with a JDK, from the project root on Linux/macOS:

```bash
mkdir -p /tmp/ai-chatroom-wrapper-classes
javac --release 8 -d /tmp/ai-chatroom-wrapper-classes $(find gradle/wrapper-source -name '*.java')
jar cf gradle/wrapper/gradle-wrapper.jar -C /tmp/ai-chatroom-wrapper-classes .
```
