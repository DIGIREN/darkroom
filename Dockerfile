FROM --platform=linux/amd64 debian:bookworm-slim
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
      openjdk-17-jdk-headless curl unzip zip ca-certificates >/dev/null && \
    rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/sdk
RUN mkdir -p /sdk/cmdline-tools && cd /sdk/cmdline-tools && \
    curl -sSL -o t.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q t.zip && mv cmdline-tools latest && rm t.zip
RUN yes | /sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
RUN /sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1
ENV PATH=$PATH:/sdk/build-tools/34.0.0:/sdk/cmdline-tools/latest/bin
WORKDIR /app
