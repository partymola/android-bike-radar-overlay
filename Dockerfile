FROM gradle:8.5-jdk17

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends wget unzip ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p $ANDROID_HOME/cmdline-tools; \
    cd /tmp; \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip; \
    unzip -q commandlinetools-linux-11076708_latest.zip -d $ANDROID_HOME/cmdline-tools; \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest; \
    rm commandlinetools-linux-11076708_latest.zip; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"; \
    chmod -R a+rwX $ANDROID_HOME

WORKDIR /workspace
