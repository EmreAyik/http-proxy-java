# Multi-stage build: compile with JDK, run with JRE.
# eclipse-temurin images are multi-arch (amd64 + arm64) so this builds
# natively on Apple Silicon and on the grader's Debian/x86_64 machine.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY src/ /build/src/
RUN mkdir -p out && javac -d out src/*.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/out /app/out

ENV PROXY_DATA_DIR=/data
RUN mkdir -p /data

EXPOSE 80 443

CMD ["java", "-cp", "out", "Main", "--headless"]
