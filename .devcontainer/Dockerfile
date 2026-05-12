# Java 26 early-access on a devcontainer-friendly base.
# Uses Microsoft's devcontainers Java image (Debian + 'vscode' user, sudo, common tooling)
# and overlays an Eclipse Temurin 26 JDK. JetBrains Gateway requires glibc + a non-root user,
# both of which this base provides.
FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# Install Temurin JDK 26 (EA) into /opt/java/openjdk, replacing the bundled JDK.
ARG TEMURIN_URL=https://download.java.net/java/early_access/jdk26/latest/GPL/openjdk-26-ea_linux-x64_bin.tar.gz
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) url="https://download.java.net/java/early_access/jdk26/latest/GPL/openjdk-26-ea_linux-x64_bin.tar.gz" ;; \
      arm64) url="https://download.java.net/java/early_access/jdk26/latest/GPL/openjdk-26-ea_linux-aarch64_bin.tar.gz" ;; \
      *) echo "Unsupported arch: $arch" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "$url" -o /tmp/jdk.tar.gz; \
    rm -rf /opt/java/openjdk; \
    mkdir -p /opt/java/openjdk; \
    tar -xzf /tmp/jdk.tar.gz -C /opt/java/openjdk --strip-components=1; \
    rm /tmp/jdk.tar.gz; \
    update-alternatives --install /usr/bin/java  java  /opt/java/openjdk/bin/java  100; \
    update-alternatives --install /usr/bin/javac javac /opt/java/openjdk/bin/javac 100

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=$JAVA_HOME/bin:$PATH
